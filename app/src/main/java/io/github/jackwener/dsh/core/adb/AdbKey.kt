// ADB RSA wire encoding adapted from Shizuku (Apache-2.0). See docs/LOCAL-ADB-NOTICE.md.
package io.github.jackwener.dsh.core.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.Date
import java.io.ByteArrayInputStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt

/** Private material is supplied by DSH SecretStore; this class never persists or logs keys. */
class AdbKey(encoded: String) {
    private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)),
    ) as RSAPrivateKey
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4),
    ) as RSAPublicKey

    val adbPublicKey: ByteArray = (Base64.getEncoder().encodeToString(AdbRsa.encodePublic(publicKey)) + " dsh-local-adb\u0000").toByteArray()
    fun sign(data: ByteArray?): ByteArray = AdbRsa.signToken(privateKey, requireNotNull(data))

    val certificate: X509Certificate by lazy {
        val name = X500Name("CN=dsh-local-adb")
        val certificate = X509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(2461449600000L),
            name, SubjectPublicKeyInfo.getInstance(publicKey.encoded))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(privateKey))
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate.encoded)) as X509Certificate
    }

    /** ADB uses self-signed certificates. This context is used only for literal loopback peers;
     * pairing is authenticated by the code-bound SPAKE2 exchange, not by Web PKI. */
    val sslContext: SSLContext by lazy {
        // adbd's acceptable issuer list is not a Web-PKI CA list. Explicitly offer our
        // paired RSA identity for both socket and SSLEngine client authentication.
        val manager = object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? =
                if (types?.contains("RSA") == true) "adb" else null
            override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?): String? =
                chooseClientAlias(types, issuers, null)
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == "adb") arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?): java.security.PrivateKey? = if (alias == "adb") privateKey else null
            override fun getClientAliases(type: String?, issuers: Array<out java.security.Principal>?): Array<String>? = if (type == "RSA") arrayOf("adb") else null
            override fun getServerAliases(type: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(type: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("This context is a client only")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty() || chain.size > 4) throw CertificateException("Invalid local ADB certificate chain")
                chain[0].checkValidity()
                chain[0].verify(chain[0].publicKey)
            }
        }
        SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(manager), arrayOf<TrustManager>(trust), java.security.SecureRandom())
        }
    }

    companion object {
        fun generate(): String {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().private.encoded)
        }
    }
}

/** Pure JVM encoding/signature helpers, independently testable without Android Keystore. */
object AdbRsa {
    private val sha1Info = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    fun signToken(key: RSAPrivateKey, token: ByteArray): ByteArray {
        require(token.size == 20 && key.modulus.bitLength() == 2048)
        val block = ByteArray(256) { 0xff.toByte() }
        block[0] = 0
        block[1] = 1
        block[220] = 0
        sha1Info.copyInto(block, 221)
        token.copyInto(block, 236)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(block)
        }
    }

    fun encodePublic(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == 2048 && key.publicExponent == RSAKeyGenParameterSpec.F4)
        val word = BigInteger.ONE.shiftLeft(32)
        val n0inv = key.modulus.mod(word).modInverse(word).negate()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(key.modulus)
        val output = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(64).putInt(n0inv.toInt())
        for (number in listOf(key.modulus, rr)) {
            var remaining = number
            repeat(64) {
                output.putInt(remaining.and(word.subtract(BigInteger.ONE)).toInt())
                remaining = remaining.shiftRight(32)
            }
        }
        output.putInt(key.publicExponent.toInt())
        return output.array()
    }
}

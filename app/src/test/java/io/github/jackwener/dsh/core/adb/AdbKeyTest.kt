package io.github.jackwener.dsh.core.adb

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

class AdbKeyTest {
    private fun publicKey(key: AdbKey): java.security.PublicKey {
        val encoded = Base64.getDecoder().decode(String(key.adbPublicKey).substringBefore(' '))
        val words = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, words.int)
        val n0inv = words.int.toLong() and 0xffffffffL
        val modulusBytes = ByteArray(256).also { words.get(it) }
        val rrBytes = ByteArray(256).also { words.get(it) }
        val exponent = words.int
        val modulus = BigInteger(1, modulusBytes.reversedArray())
        assertEquals(BigInteger.ZERO, modulus.multiply(BigInteger.valueOf(n0inv)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), BigInteger(1, rrBytes.reversedArray()))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong())))
    }

    @Test fun `signature contains the raw adbd digest with SHA1 DigestInfo`() {
        val key = AdbKey(AdbKey.generate())
        val token = ByteArray(20) { it.toByte() }
        val verifier = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        verifier.init(Cipher.DECRYPT_MODE, publicKey(key))
        val decoded = verifier.doFinal(key.sign(token))
        assertEquals(35, decoded.size)
        assertArrayEquals(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0, 0x04, 0x14), decoded.copyOfRange(0, 15))
        assertArrayEquals(token, decoded.copyOfRange(15, 35))
    }

    @Test fun `persisted encoded key keeps the same authorization identity`() {
        val encoded = AdbKey.generate()
        assertArrayEquals(AdbKey(encoded).adbPublicKey, AdbKey(encoded).adbPublicKey)
        assertTrue(String(AdbKey(encoded).adbPublicKey).endsWith(" dsh-local-adb\u0000"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an invalid auth challenge`() { AdbKey(AdbKey.generate()).sign(ByteArray(21)) }

    @Test fun `TLS certificate uses the same ADB identity`() {
        val key = AdbKey(AdbKey.generate())
        key.certificate.verify(publicKey(key))
        assertArrayEquals(publicKey(key).encoded, key.certificate.publicKey.encoded)
    }
}

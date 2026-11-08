package io.github.jackwener.dsh.core.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import org.conscrypt.Conscrypt

/** Android pairing protocol. No pairing code is stored or logged. */
class AdbPairingClient(private val key: AdbKey) : Closeable {
    @Volatile private var socket: Socket? = null
    @Volatile private var tls: SSLSocket? = null
    @Volatile private var cancelled = false
    fun pair(port: Int, code: String): String {
        require(port in 1..65535)
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位配对码" }
        check(!cancelled)
        val raw = Socket().also { socket = it }
        try {
            check(!cancelled)
            raw.connect(InetSocketAddress("127.0.0.1", port), 5000)
            raw.soTimeout = 15000
            val secure = (key.sslContext.socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket).also { tls = it }
            check(!cancelled)
            secure.startHandshake()
            val material = Conscrypt.exportKeyingMaterial(secure, "adb-label\u0000", null, 64)
            val password = code.toByteArray(Charsets.US_ASCII) + material
            material.fill(0)
            val pairing = try { PairingContext(password) } finally { password.fill(0) }
            pairing.use {
                val input = DataInputStream(secure.inputStream)
                val output = DataOutputStream(secure.outputStream)
                PairingPacket.write(output, 0, it.message())
                it.initialize(PairingPacket.read(input, 0))
                val info = ByteArray(8192)
                require(key.adbPublicKey.size < info.size)
                key.adbPublicKey.copyInto(info, 1)
                PairingPacket.write(output, 1, it.encrypt(info))
                val peer = it.decrypt(PairingPacket.read(input, 1))
                check(peer.size == 8192 && peer[0] == 1.toByte()) { "Invalid pairing server identity" }
                val guid = peer.copyOfRange(1, peer.size).takeWhile { b -> b != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                check(guid.isNotBlank() && guid.length <= 256) { "Invalid device GUID" }
                return guid
            }
        } finally { close() }
    }
    override fun close() { cancelled = true; runCatching { socket?.close() }; runCatching { tls?.close() } }
}

internal object PairingPacket {
    fun read(input: DataInputStream, type: Int): ByteArray {
        check(input.readUnsignedByte() == 1) { "Unsupported pairing version" }
        check(input.readUnsignedByte() == type) { "Unexpected pairing packet" }
        val size = input.readInt()
        check(size in 1..16384) { "Invalid pairing packet size" }
        return ByteArray(size).also { input.readFully(it) }
    }
    fun write(output: DataOutputStream, type: Int, data: ByteArray) {
        require(type in 0..1 && data.size in 1..16384)
        output.writeByte(1); output.writeByte(type); output.writeInt(data.size); output.write(data); output.flush()
    }
}

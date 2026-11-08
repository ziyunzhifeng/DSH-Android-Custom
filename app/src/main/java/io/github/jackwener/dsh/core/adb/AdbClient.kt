// Adapted from Shizuku+ / Shizuku (Apache-2.0); see docs/LOCAL-ADB-NOTICE.md.
package io.github.jackwener.dsh.core.adb

import io.github.jackwener.dsh.core.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import io.github.jackwener.dsh.core.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import io.github.jackwener.dsh.core.adb.AdbProtocol.ADB_AUTH_TOKEN
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_AUTH
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_CLSE
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_CNXN
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_MAXDATA
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_OKAY
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_OPEN
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_STLS
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_STLS_VERSION
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_VERSION
import io.github.jackwener.dsh.core.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val requireTls: Boolean = false,
    /** Called once, right after the public key was offered to adbd and the connection starts
     *  waiting for the user to accept the "Allow USB debugging?" dialog. */
    private val onAuthorizationPending: (() -> Unit)? = null,
) : Closeable {
    @Volatile
    private var socket: Socket? = null
    private var plainInputStream: DataInputStream? = null
    private var plainOutputStream: DataOutputStream? = null

    @Volatile private var closed = false
    @Volatile private var handshakeComplete = false
    private var nextLocalId = 1
    val isConnected get() = !closed && handshakeComplete && socket?.isConnected == true

    private var useTls = false
    val isTls: Boolean get() = useTls

    private var tlsSocket: SSLSocket? = null
    private var tlsInputStream: DataInputStream? = null
    private var tlsOutputStream: DataOutputStream? = null

    private val inputStream get() =
        (if (useTls) tlsInputStream else plainInputStream)
            ?: throw IllegalStateException("inputStream is null - AdbClient not connected or closed")
    private val outputStream get() =
        (if (useTls) tlsOutputStream else plainOutputStream)
            ?: throw IllegalStateException("outputStream is null - AdbClient not connected or closed")

    fun connect() {
        check(!closed) { "ADB client closed" }
        require(port in 1..65535) { "port out of range: $port" }
        useTls = false // Reset TLS state for each connection attempt
        val s = Socket()
        socket = s
        val address = InetSocketAddress(host, port)

        try {
            check(!closed) { "ADB client closed" }
            s.connect(address, 5000)
            s.tcpNoDelay = true
            s.soTimeout = 15000 // 15 seconds read timeout to prevent infinite hangs
            s.keepAlive = true

            val pin = DataInputStream(s.getInputStream())
            plainInputStream = pin
            val pout = DataOutputStream(s.getOutputStream())
            plainOutputStream = pout

            write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

            var message = read()
            check(!requireTls || message.command == A_STLS) { "Expected wireless TLS ADB; the selected port offers plain TCP ADB." }
            if (message.command == A_STLS) {
                write(A_STLS, A_STLS_VERSION, 0)

                val sslContext = key.sslContext
                val ts = sslContext.socketFactory.createSocket(s, host, port, true) as SSLSocket
                tlsSocket = ts
                ts.startHandshake()
                AdbLog.d("Handshake succeeded.")

                tlsInputStream = DataInputStream(ts.inputStream)
                tlsOutputStream = DataOutputStream(ts.outputStream)
                useTls = true

                message = read()
            } else if (message.command == A_AUTH) {
                if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
                write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

                message = read()
                if (message.command != A_CNXN) {
                    message = offerKeyAndAwaitAuthorization(s)
                }
            }

            if (message.command != A_CNXN) error("not A_CNXN")
            handshakeComplete = true
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** One key offer per explicit call, bounded by the repository deadline. */
    private fun offerKeyAndAwaitAuthorization(s: Socket): AdbMessage {
        s.soTimeout = 90_000
        onAuthorizationPending?.invoke()
        write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
        return try {
            read().also { check(it.command == A_CNXN) { "ADB key not accepted" } }
        } finally {
            runCatching { s.soTimeout = 15_000 }
        }
    }

    fun command(cmd: String, listener: ((ByteArray) -> Unit)? = null) {
        openService(cmd, reuseTransport = true).use { stream ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = stream.input.read(buffer)
                if (count < 0) break
                listener?.invoke(buffer.copyOf(count))
            }
        }
    }

    /** Sequential services have unique IDs; reusable streams close only their ADB channel. */
    fun openService(destination: String, reuseTransport: Boolean = false): AdbServiceStream {
        check(nextLocalId > 0) { "ADB channel ID exhausted" }
        val id = nextLocalId++
        return AdbServiceStream(destination, { readForChannel(id) }, ::write, ::close, id, reuseTransport)
    }

    private fun readForChannel(localId: Int): AdbMessage {
        repeat(128) {
            val packet = read()
            if (packet.arg1 == localId) return packet
            // adbd may acknowledge a retired channel's close after the next OPEN was sent.
            // IDs are never reused, so those frames cannot belong to the current service.
            if (packet.arg1 in 1 until localId) {
                if (packet.command == A_WRTE) write(A_CLSE, packet.arg1, packet.arg0)
            } else {
                close()
                throw IOException("Unexpected ADB channel ${packet.arg1}; expected $localId")
            }
        }
        close()
        throw IOException("Too many retired ADB channel packets")
    }

    private fun write(
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray? = null,
    ) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(
        command: Int,
        arg0: Int,
        arg1: Int,
        data: String,
    ) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        try {
            val os = (if (useTls) tlsOutputStream else plainOutputStream) ?: throw IOException("ADB client closed")
            os.write(message.toByteArray())
            os.flush()
        } catch (error: Exception) { close(); throw error }
    }

    private fun read(): AdbMessage = try {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        require(dataLength in 0..(1024 * 1024)) { "ADB payload exceeds the supported limit" }
        val data: ByteArray?
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        AdbLog.d("read command=%d length=%d", message.command, message.data_length)
        message
    } catch (error: Exception) { close(); throw error }

    override fun close() {
        closed = true
        handshakeComplete = false
        try {
            plainInputStream?.close()
        } catch (_: Throwable) {
        } finally {
            plainInputStream = null
        }
        try {
            plainOutputStream?.close()
        } catch (_: Throwable) {
        } finally {
            plainOutputStream = null
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }

        run {
            try {
                tlsInputStream?.close()
            } catch (_: Throwable) {
            } finally {
                tlsInputStream = null
            }
            try {
                tlsOutputStream?.close()
            } catch (_: Throwable) {
            } finally {
                tlsOutputStream = null
            }
            try {
                tlsSocket?.close()
            } catch (_: Exception) {
            } finally {
                tlsSocket = null
            }
        }
    }
}

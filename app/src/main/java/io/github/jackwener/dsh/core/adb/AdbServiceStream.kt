// Adapted from Shizuku+ / Shizuku (Apache-2.0); see docs/LOCAL-ADB-NOTICE.md.
package io.github.jackwener.dsh.core.adb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque

/** A bounded, synchronous ADB service channel. Use both streams from one worker thread. */
class AdbServiceStream internal constructor(
    destination: String,
    private val receive: () -> AdbMessage,
    private val send: (AdbMessage) -> Unit,
    private val disconnect: () -> Unit,
    private val localId: Int = 1,
    private val reuseTransport: Boolean = false,
) : Closeable {
    private val remoteId: Int
    private var closed = false
    private var closeSent = false
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var current = ByteArray(0)
    private var offset = 0

    init {
        require(destination.isNotEmpty() && '\u0000' !in destination)
        send(AdbMessage(AdbProtocol.A_OPEN, localId, 0, destination))
        val reply = receive()
        if (reply.command != AdbProtocol.A_OKAY || reply.arg1 != localId || reply.arg0 == 0) {
            if (reply.command != AdbProtocol.A_CLSE || reply.arg1 != localId) disconnect()
            throw IOException("adbd refused service: $destination (command=${reply.command}, remote=${reply.arg0}, local=${reply.arg1}, expected=$localId)")
        }
        remoteId = reply.arg0
    }

    private fun packet(): Int = try {
        val message = receive()
        if (message.arg0 != remoteId || message.arg1 != localId) throw IOException("Unexpected ADB channel")
        when (message.command) {
            AdbProtocol.A_WRTE -> {
                val bytes = message.data ?: ByteArray(0)
                if (pendingBytes + bytes.size > 4 * 1024 * 1024) throw IOException("ADB receive buffer limit exceeded")
                if (bytes.isNotEmpty()) {
                    pending.add(bytes)
                    pendingBytes += bytes.size
                }
                send(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId, null))
            }
            AdbProtocol.A_CLSE -> {
                if (!closeSent) send(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, null))
                closeSent = true
                closed = true
            }
            AdbProtocol.A_OKAY -> Unit
            else -> throw IOException("Unexpected ADB service packet")
        }
        message.command
    } catch (error: Exception) { disconnect(); throw error }

    val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }

            override fun read(
                bytes: ByteArray,
                start: Int,
                length: Int,
            ): Int {
                require(start >= 0 && length >= 0 && start <= bytes.size - length)
                if (length == 0) return 0
                while (offset == current.size) {
                    if (pending.isNotEmpty()) {
                        current = pending.removeFirst()
                        pendingBytes -= current.size
                        offset = 0
                    } else if (closed) {
                        return -1
                    } else {
                        packet()
                    }
                }
                val count = minOf(length, current.size - offset)
                current.copyInto(bytes, start, offset, offset + count)
                offset += count
                return count
            }
        }

    val output: OutputStream =
        object : OutputStream() {
            override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

            override fun write(
                bytes: ByteArray,
                start: Int,
                length: Int,
            ) {
                require(start >= 0 && length >= 0 && start <= bytes.size - length)
                var position = start
                while (position < start + length) {
                    if (closed) throw IOException("ADB service closed")
                    val end = minOf(position + AdbProtocol.A_MAXDATA, start + length)
                    send(AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, bytes.copyOfRange(position, end)))
                    while (packet() != AdbProtocol.A_OKAY) {
                        if (closed) throw IOException("ADB service closed before write acknowledgement")
                    }
                    position = end
                }
            }
        }

    override fun close() {
        if (!reuseTransport) { closed = true; disconnect(); return }
        if (closed) return
        try {
            closeSent = true
            send(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, null))
            // Drain in-flight frames before the next OPEN. A broken channel must invalidate the transport.
            repeat(128) {
                if (packet() == AdbProtocol.A_CLSE) return
                pending.clear(); pendingBytes = 0
            }
            throw IOException("ADB channel close exceeded packet limit")
        } catch (error: Exception) { disconnect(); throw error }
        finally { closed = true; pending.clear(); pendingBytes = 0 }
    }
}

package io.github.jackwener.dsh.core.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdbClientTest {
    private fun read(input: DataInputStream): AdbMessage {
        val header = ByteBuffer.wrap(ByteArray(24).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int; val a = header.int; val b = header.int
        val size = header.int; val crc = header.int; val magic = header.int
        require(size in 0..1048576)
        return AdbMessage(command, a, b, size, crc, magic, ByteArray(size).also { input.readFully(it) }).also { it.validateOrThrow() }
    }

    private fun send(output: OutputStream, command: Int, a: Int, b: Int, data: ByteArray? = null) {
        output.write(AdbMessage(command, a, b, data).toByteArray()); output.flush()
    }

    @Test fun `wireless mode rejects plain ADB without offering a key`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        read(input)
                        send(socket.getOutputStream(), AdbProtocol.A_AUTH, 1, 0, ByteArray(20))
                        assertEquals(-1, input.read())
                    }
                }
                var offered = false
                AdbClient("127.0.0.1", server.localPort, AdbKey(AdbKey.generate()), true) { offered = true }.use { client ->
                    assertThrows(IllegalStateException::class.java) { client.connect() }
                }
                assertFalse(offered)
                peer.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `real TCP transport authorizes and exchanges bidirectional service bytes`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        assertEquals(AdbProtocol.A_CNXN, read(input).command)
                        send(output, AdbProtocol.A_AUTH, 1, 0, ByteArray(20))
                        assertEquals(2, read(input).arg0)
                        send(output, AdbProtocol.A_AUTH, 1, 0, ByteArray(20) { 1 })
                        val key = read(input)
                        assertEquals(3, key.arg0)
                        assertTrue(String(key.data!!).endsWith("dsh-local-adb\u0000"))
                        send(output, AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, 4096, "device::\u0000".toByteArray())
                        val open = read(input)
                        assertEquals("localabstract:test\u0000", String(open.data!!))
                        send(output, AdbProtocol.A_OKAY, 7, 1)
                        assertEquals("request", String(read(input).data!!))
                        send(output, AdbProtocol.A_WRTE, 7, 1, "reply".toByteArray())
                        send(output, AdbProtocol.A_OKAY, 7, 1)
                        assertEquals(AdbProtocol.A_OKAY, read(input).command)
                        send(output, AdbProtocol.A_CLSE, 7, 1)
                        assertEquals(AdbProtocol.A_CLSE, read(input).command)
                    }
                }
                var offered = false
                AdbClient("127.0.0.1", server.localPort, AdbKey(AdbKey.generate())) { offered = true }.use { client ->
                    client.connect()
                    client.openService("localabstract:test").use { stream ->
                        stream.output.write("request".toByteArray())
                        assertEquals("reply", String(stream.input.readBytes()))
                    }
                }
                assertTrue(offered)
                peer.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}

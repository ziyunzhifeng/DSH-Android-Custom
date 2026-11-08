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

class AdbConnectionSessionTest {
    private fun read(input: DataInputStream): AdbMessage {
        val header = ByteBuffer.wrap(ByteArray(24).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        val command=header.int; val a=header.int;val b=header.int;val size=header.int;val crc=header.int;val magic=header.int
        require(size in 0..1048576)
        return AdbMessage(command,a,b,size,crc,magic,ByteArray(size).also { input.readFully(it) }).also { it.validateOrThrow() }
    }
    private fun send(output: OutputStream, command: Int, remote: Int, local: Int, data: String? = null) {
        output.write(AdbMessage(command,remote,local,data?.toByteArray()).toByteArray());output.flush()
    }
    private fun handshake(input: DataInputStream, output: OutputStream) {
        assertEquals(AdbProtocol.A_CNXN,read(input).command)
        send(output,AdbProtocol.A_CNXN,AdbProtocol.A_VERSION,4096,"device::\u0000")
    }
    private fun shell(input: DataInputStream, output: OutputStream, local: Int) {
        val open=read(input); assertEquals(AdbProtocol.A_OPEN,open.command);assertEquals(local,open.arg0)
        send(output,AdbProtocol.A_OKAY,7,local)
        send(output,AdbProtocol.A_WRTE,7,local,"done")
        assertEquals(AdbProtocol.A_OKAY,read(input).command)
        send(output,AdbProtocol.A_CLSE,7,local)
        assertEquals(AdbProtocol.A_CLSE,read(input).command)
    }
    @Test fun `shell and devtools channels share one handshake and drain early close`() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            val executor=Executors.newSingleThreadExecutor()
            val session=AdbConnectionSession()
            try {
                val peer=executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                        handshake(input,output);shell(input,output,1)
                        send(output,AdbProtocol.A_CLSE,0,1) // Late close acknowledgement from a retired stream.
                        assertEquals(2,read(input).arg0)
                        send(output,AdbProtocol.A_OKAY,9,2)
                        assertEquals(AdbProtocol.A_WRTE,read(input).command)
                        send(output,AdbProtocol.A_OKAY,9,2)
                        send(output,AdbProtocol.A_WRTE,9,2,"body")
                        assertEquals(AdbProtocol.A_OKAY,read(input).command)
                        assertEquals(AdbProtocol.A_CLSE,read(input).command)
                        send(output,AdbProtocol.A_WRTE,9,2,"in-flight")
                        assertEquals(AdbProtocol.A_OKAY,read(input).command)
                        send(output,AdbProtocol.A_CLSE,9,2)
                        shell(input,output,3)
                        assertEquals(-1,input.read())
                    }
                }
                val key=AdbKey(AdbKey.generate())
                session.withClient(server.localPort,key,false) { it.command("shell:one") }
                session.withClient(server.localPort,key,false) { client ->
                    client.openService("localabstract:test",reuseTransport=true).use {
                        it.output.write("GET".toByteArray());assertEquals('b'.code,it.input.read())
                    }
                }
                session.withClient(server.localPort,key,false) { it.command("shell:three") }
                assertEquals(1,session.connections.get()); assertTrue(session.connected)
                session.close(); assertFalse(session.connected)
                assertThrows(IllegalStateException::class.java) { session.withClient(server.localPort,key,false) {} }
                peer.get(5,TimeUnit.SECONDS)
            } finally { session.close();executor.shutdownNow() }
        }
    }
    @Test fun `unavailable devtools socket does not discard healthy ADB authentication`() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            val executor=Executors.newSingleThreadExecutor(); val session=AdbConnectionSession()
            try {
                val peer=executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                        handshake(input,output)
                        assertEquals(1,read(input).arg0)
                        send(output,AdbProtocol.A_CLSE,0,1)
                        shell(input,output,2)
                    }
                }
                val key=AdbKey(AdbKey.generate())
                assertThrows(java.io.IOException::class.java) {
                    session.withClient(server.localPort,key,false) { it.openService("localabstract:gone",reuseTransport=true) }
                }
                assertTrue(session.connected)
                session.withClient(server.localPort,key,false) { it.command("shell:still-alive") }
                assertEquals(1,session.connections.get())
                peer.get(5,TimeUnit.SECONDS)
            } finally { session.close();executor.shutdownNow() }
        }
    }

    @Test fun `cancellation gets the client before a handshake can block`() {
        val session=AdbConnectionSession()
        try {
            assertThrows(IllegalStateException::class.java) {
                session.withClient(1,AdbKey(AdbKey.generate()),false,onClient={ it.close() }) { fail("cancelled client used") }
            }
            assertEquals(0,session.connections.get())
            assertFalse(session.connected)
        } finally { session.close() }
    }

    @Test fun `broken transport reconnects only on next call without replaying a failed action`() {
        ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).use { server ->
            val executor=Executors.newSingleThreadExecutor(); val session=AdbConnectionSession()
            try {
                val closed=java.util.concurrent.CountDownLatch(1)
                val peer=executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                        handshake(input,output);shell(input,output,1)
                    }
                    closed.countDown()
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                        handshake(input,output);shell(input,output,1)
                    }
                }
                val key=AdbKey(AdbKey.generate())
                session.withClient(server.localPort,key,false) { it.command("shell:first") }
                assertTrue(closed.await(5,TimeUnit.SECONDS))
                assertThrows(java.io.IOException::class.java) { session.withClient(server.localPort,key,false) { it.command("shell:no-replay") } }
                assertEquals(1,session.connections.get())
                session.withClient(server.localPort,key,false) { it.command("shell:next-explicit-call") }
                assertEquals(2,session.connections.get())
                peer.get(5,TimeUnit.SECONDS)
            } finally { session.close();executor.shutdownNow() }
        }
    }
}

package io.github.jackwener.dsh.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class AdbServiceStreamTest {
    private class Peer(
        vararg packets: AdbMessage,
    ) {
        val incoming = ArrayDeque(packets.toList())
        val sent = mutableListOf<AdbMessage>()
        var disconnected = false

        fun open(): AdbServiceStream =
            AdbServiceStream("localabstract:webview_devtools_remote_123", { incoming.removeFirst() }, { sent.add(it) }, { disconnected = true })
    }

    private fun reply(
        command: Int,
        data: ByteArray? = null,
    ) = AdbMessage(command, 7, 1, data)

    @Test
    fun receivesDataWhileWaitingForWriteAcknowledgement() {
        val bytes = "公众号标题".toByteArray()
        val peer = Peer(reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_WRTE, bytes), reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_CLSE))
        peer.open().use { stream ->
            stream.output.write("GET /json/list".toByteArray())
            assertArrayEquals(bytes, stream.input.readBytes())
        }
        assertTrue(peer.disconnected)
        assertEquals(AdbProtocol.A_OPEN, peer.sent.first().command)
        assertEquals(1, peer.sent.count { it.command == AdbProtocol.A_OKAY })
    }

    @Test
    fun splitsWritesAtNegotiatedLegacyPayloadLimit() {
        val bytes = ByteArray(9000) { (it % 251).toByte() }
        val peer = Peer(reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_OKAY))
        peer.open().use { it.output.write(bytes) }
        val writes = peer.sent.filter { it.command == AdbProtocol.A_WRTE }
        assertEquals(listOf(4096, 4096, 808), writes.map { it.data_length })
        assertArrayEquals(bytes, writes.flatMap { it.data!!.toList() }.toByteArray())
    }

    @Test(expected = IOException::class)
    fun refusesUnavailableService() {
        Peer(reply(AdbProtocol.A_CLSE)).open()
    }

    @Test(expected = IOException::class)
    fun rejectsAnotherChannelsData() {
        Peer(reply(AdbProtocol.A_OKAY), AdbMessage(AdbProtocol.A_WRTE, 8, 1, byteArrayOf(1))).open().use {
            it.input.read()
        }
    }

    @Test(expected = IOException::class)
    fun failsWhenPeerClosesBeforeAcknowledgingWrite() {
        Peer(reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_CLSE)).open().use { it.output.write(1) }
    }

    @Test
    fun readsAcrossPacketsAndHandlesZeroLengthRead() {
        val peer = Peer(reply(AdbProtocol.A_OKAY), reply(AdbProtocol.A_WRTE, byteArrayOf(1)), reply(AdbProtocol.A_WRTE, byteArrayOf(2, 3)), reply(AdbProtocol.A_CLSE))
        peer.open().use {
            assertEquals(0, it.input.read(ByteArray(0)))
            assertArrayEquals(byteArrayOf(1, 2, 3), it.input.readBytes())
            assertEquals(-1, it.input.read())
        }
    }

    @Test(expected = IOException::class)
    fun boundsBufferedDataWhileWaitingForAcknowledgement() {
        val packet = reply(AdbProtocol.A_WRTE, ByteArray(1024 * 1024))
        val peer = Peer(reply(AdbProtocol.A_OKAY), packet, packet, packet, packet, packet)
        peer.open().use { it.output.write(1) }
    }
}

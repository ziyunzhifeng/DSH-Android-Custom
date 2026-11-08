package io.github.jackwener.dsh.core.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class PairingPacketTest {
    @Test fun `round trips bounded pairing payload`() {
        val buffer = ByteArrayOutputStream()
        PairingPacket.write(DataOutputStream(buffer), 1, ByteArray(8208) { it.toByte() })
        assertArrayEquals(ByteArray(8208) { it.toByte() }, PairingPacket.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())), 1))
    }
    @Test fun `rejects wrong version type and oversized packets before allocation`() {
        for (header in listOf(byteArrayOf(2,0,0,0,0,1), byteArrayOf(1,1,0,0,0,1), byteArrayOf(1,0,0,1,0,0), byteArrayOf(1,0,0,0,0,0))) {
            assertThrows(IllegalStateException::class.java) { PairingPacket.read(DataInputStream(ByteArrayInputStream(header)), 0) }
        }
    }
    @Test fun `rejects truncated peer payload`() {
        assertThrows(EOFException::class.java) { PairingPacket.read(DataInputStream(ByteArrayInputStream(byteArrayOf(1,0,0,0,0,2,1))), 0) }
    }
}

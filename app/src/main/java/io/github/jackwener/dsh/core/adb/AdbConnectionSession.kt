package io.github.jackwener.dsh.core.adb

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One scan owns one transport. All I/O is serialized by LocalAdbRepository's worker. */
class AdbConnectionSession : Closeable {
    private data class Connection(val port: Int, val tls: Boolean, val client: AdbClient)
    private val current = AtomicReference<Connection?>()
    @Volatile var closed = false
        private set
    val connections = AtomicInteger(0)
    val connected get() = current.get()?.client?.isConnected == true

    fun <T> withClient(port: Int, key: AdbKey, tls: Boolean, pending: () -> Unit = {}, authenticated: () -> Unit = {}, onClient: (AdbClient) -> Unit = {}, block: (AdbClient) -> T): T {
        check(!closed) { "ADB scan session ended" }
        var connection = current.get()
        if (connection != null && (connection.port != port || connection.tls != tls || !connection.client.isConnected)) {
            current.compareAndSet(connection, null)
            connection.client.close()
            connection = null
        }
        if (connection == null) {
            val fresh = Connection(port, tls, AdbClient("127.0.0.1", port, key, tls, pending))
            current.set(fresh)
            try {
                onClient(fresh.client) // Publish before handshake so cancellation can interrupt it.
                check(!closed) { "ADB scan session ended" }
                fresh.client.connect()
                check(!closed) { "ADB scan session ended" }
                connections.incrementAndGet()
                authenticated()
                connection = fresh
            } catch (error: Exception) {
                current.compareAndSet(fresh, null); fresh.client.close(); throw error
            }
        }
        val selected = connection
        try {
            onClient(selected.client)
            check(!closed) { "ADB scan session ended" }
            return block(selected.client)
        } finally {
            if (!selected.client.isConnected) current.compareAndSet(selected, null)
        }
    }
    override fun close() { closed = true; current.getAndSet(null)?.client?.close() }
}

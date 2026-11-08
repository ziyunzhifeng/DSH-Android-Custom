package io.github.jackwener.dsh.core.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface

/** One bounded session, main-thread owned. Only this device's addresses are accepted. */
@Suppress("DEPRECATION")
class LocalAdbDiscovery(context: Context, private val found: (Boolean, Int?) -> Unit, private val failed: () -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var resolving = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val selected = mutableMapOf<Boolean, String>()
    private val lost = mutableSetOf<String>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        for (type in listOf(PAIRING, CONNECT)) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) { if (stopped) runCatching { manager.stopServiceDiscovery(this) } }
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, error: Int) { main.post { if (!stopped) failed() } }
                override fun onStopDiscoveryFailed(type: String, error: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { main.post {
                    if (!stopped && queue.size < 32) { lost.remove(info.serviceName); queue.add(info); resolveNext() }
                } }
                override fun onServiceLost(info: NsdServiceInfo) { main.post {
                    lost.add(info.serviceName)
                    val pairing = info.serviceType.contains("_adb-tls-pairing")
                    if (!stopped && selected[pairing] == info.serviceName) { selected.remove(pairing); found(pairing, null) }
                } }
            }
            listeners.add(listener)
            try { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: Exception) { failed() }
        }
    }
    private fun resolveNext() {
        if (stopped || resolving || queue.isEmpty()) return
        resolving = true
        val info = queue.removeFirst()
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { main.post {
                    resolving = false
                    // Some ROMs serialize NSD resolution across apps. Retry with a bounded session deadline.
                    if (!stopped && info.serviceName !in lost) main.postDelayed({
                        if (!stopped && queue.size < 32) { queue.add(info); resolveNext() }
                    }, 1000)
                    resolveNext()
                } }
                override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                    resolving = false
                    if (!stopped && info.serviceName !in lost && info.port in 1..65535 && isLocal(info.host)) {
                        val pairing = info.serviceType.contains("_adb-tls-pairing")
                        // NSD can briefly return an old port after wireless debugging restarts.
                        if (!pairing && !portBound(info.port)) {
                            main.postDelayed({ if (!stopped && queue.size < 32) { queue.add(info); resolveNext() } }, 500)
                            resolveNext(); return@post
                        }
                        selected[pairing] = info.serviceName
                        found(pairing, info.port)
                    }
                    resolveNext()
                } }
            })
        } catch (_: Exception) { resolving = false; failed() }
    }
    fun stop() {
        stopped = true
        queue.clear()
        main.removeCallbacksAndMessages(null)
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
    }
    private fun portBound(port: Int): Boolean = try {
        java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port), 1) }
        false
    } catch (_: java.io.IOException) { true }
    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp."
        const val CONNECT = "_adb-tls-connect._tcp."
        internal fun isLocal(host: InetAddress?): Boolean = host != null && (host.isLoopbackAddress || runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { iface -> iface.inetAddresses.asSequence().any { it == host } }
        }.getOrDefault(false))
    }
}

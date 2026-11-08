package io.github.jackwener.dsh.core.adb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.data.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WeChatForegroundLostException : IllegalStateException("检测到已离开微信，扫描已暂停；回到采集页面可停止或继续任务。")

@Singleton
class LocalAdbRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences("local-adb", Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dsh-local-adb").apply { isDaemon = true } }
    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()
    val port get() = prefs.getInt("port", 5555)
    val wireless get() = prefs.getBoolean("wireless", false)
    val paired get() = prefs.contains("paired_guid")
    val enabled get() = prefs.getBoolean("enabled", false)
    private var cachedKey: AdbKey? = null // Used only by the serialized worker.
    private data class ScanConnection(val owner: String, val transport: AdbConnectionSession)
    private val scanConnection = AtomicReference<ScanConnection?>()

    suspend fun beginScanConnection(owner: String) = gate.withLock {
        require(owner.isNotBlank())
        val previous = scanConnection.get()
        if (previous?.owner != owner || previous.transport.closed) {
            previous?.transport?.close()
            scanConnection.set(ScanConnection(owner, AdbConnectionSession()))
        }
    }
    suspend fun endScanConnection(owner: String) = gate.withLock {
        val current = scanConnection.get()
        if (current?.owner == owner && scanConnection.compareAndSet(current, null)) current.transport.close()
    }
    fun connectionState(): JSONObject = scanConnection.get()?.let {
        JSONObject().put("persistent", !it.transport.closed).put("connected", it.transport.connected).put("connections_opened", it.transport.connections.get())
    } ?: JSONObject().put("persistent", false).put("connected", false)

    private val activePairing = AtomicReference<AdbPairingClient?>()
    private val activeOperation = AtomicReference<WeChatDebugProbe?>()

    fun configure(port: Int, enabled: Boolean, wireless: Boolean = this.wireless) {
        require(port in 1..65535 && port != 15555) { "Use the real TCP adbd port, not proxy port 15555." }
        check(prefs.edit().putBoolean("wireless", wireless).putInt("port", port).putBoolean("enabled", enabled).remove("authorization_pending").commit()) { "Could not save ADB settings" }
        if (!enabled) { activeOperation.get()?.cancel(); activePairing.get()?.close(); scanConnection.getAndSet(null)?.transport?.close() }
    }

    suspend fun status(): JSONObject = if (!enabled) {
        JSONObject().put("enabled", false).put("port", port).put("connected", false)
    } else execute { it.checkConnection().put("enabled", true) }

    suspend fun targets(): JSONObject = execute { it.targets() }

    suspend fun readArticle(targetId: String? = null, requireVisible: Boolean = false, commentOffset: Int = 0): JSONObject {
        val result = execute { it.run(targetId, requireVisible, commentOffset) }
        withContext(Dispatchers.IO) {
            val directory = File(context.noBackupFilesDir, "wechat").apply { mkdirs() }
            val temporary = File(directory, "latest.tmp")
            temporary.writeText(result.toString(2))
            check(temporary.renameTo(File(directory, "latest.json"))) { "Could not save article snapshot" }
        }
        return result
    }

    suspend fun scanScreen(): JSONObject = execute { probe ->
        checkWeChatForeground(probe)
        val xml = probe.deviceCommand("uiautomator dump /data/local/tmp/dsh-wechat-window.xml >/dev/null 2>&1; cat /data/local/tmp/dsh-wechat-window.xml", 1024 * 1024).toString(Charsets.UTF_8)
        val image = probe.deviceCommand("screencap -p")
        val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size)) { "Screenshot unavailable" }
        val width = bitmap.width; val height = bitmap.height
        val jpeg = java.io.ByteArrayOutputStream()
        try { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, jpeg) } finally { bitmap.recycle() }
        JSONObject().put("width", width).put("height", height).put("xml", if (xml.contains("<hierarchy")) xml else "UI hierarchy unavailable; use the current screenshot with original pixel dimensions.").put("jpeg", android.util.Base64.encodeToString(jpeg.toByteArray(), android.util.Base64.NO_WRAP))
    }

    suspend fun scanNavigate(action: String, x: Int?, y: Int?, allowBringToFront: Boolean = true): JSONObject = execute { probe ->
        if (action == "end" || action == "comments") {
            checkWeChatForeground(probe)
            return@execute probe.run(requireVisible = true, scrollToEnd = action == "end", openComments = action == "comments").let {
                JSONObject().put("ok", true).put("action", action).put("performed", it.getJSONObject("article").optBoolean("navigation_performed"))
            }
        }
        if (action == "open") {
            if (!allowBringToFront) checkWeChatForeground(probe)
            probe.deviceCommand("monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1", 65536)
        } else {
            checkWeChatForeground(probe)
            val command = when (action) {
                "tap" -> {
                    require(x != null && y != null && x in 0..4095 && y in 0..8191)
                    val xml = probe.deviceCommand("uiautomator dump /data/local/tmp/dsh-wechat-window.xml >/dev/null 2>&1; cat /data/local/tmp/dsh-wechat-window.xml", 1024 * 1024).toString(Charsets.UTF_8)
                    io.github.jackwener.dsh.core.wechat.WeChatUiGuard.checkTap(xml, x, y)
                    "input tap $x $y"
                }
                "back" -> "input keyevent 4"
                "down", "up" -> {
                    val dimensions = probe.deviceCommand("wm size", 4096).toString(Charsets.UTF_8)
                    val match = Regex("([0-9]+)x([0-9]+)").findAll(dimensions).lastOrNull() ?: error("Display size unavailable")
                    val width = match.groupValues[1].toInt(); val height = match.groupValues[2].toInt()
                    val start = if (action == "down") height * 3 / 4 else height / 3
                    val end = if (action == "down") height / 3 else height * 3 / 4
                    "input swipe ${width / 2} $start ${width / 2} $end 450"
                }
                else -> error("Unsupported scan navigation")
            }
            probe.deviceCommand(command, 65536)
        }
        JSONObject().put("ok", true).put("action", action)
    }
    private fun checkWeChatForeground(probe: WeChatDebugProbe) {
        val resumed = probe.deviceCommand("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'", 16384).toString(Charsets.UTF_8)
        if (!resumed.contains("com.tencent.mm/")) throw WeChatForegroundLostException()
    }

    private suspend fun execute(block: (WeChatDebugProbe) -> JSONObject): JSONObject = try {
        executeOnce(block)
    } catch (error: java.net.ConnectException) {
        if (!wireless || !enabled) throw error
        val discovered = withContext(Dispatchers.Main) {
            withTimeoutOrNull(7000) {
                suspendCancellableCoroutine<Int> { continuation ->
                    lateinit var discovery: LocalAdbDiscovery
                    discovery = LocalAdbDiscovery(context, found = { pairing, foundPort ->
                        if (!pairing && foundPort != null && continuation.isActive) {
                            discovery.stop(); continuation.resume(foundPort)
                        }
                    }, failed = { if (continuation.isActive) { discovery.stop(); continuation.resumeWithException(error) } })
                    continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { discovery.stop() } }
                    discovery.start()
                }
            }
        }
        check(enabled) { "Local ADB stopped" }
        checkNotNull(discovered) { "未发现本机无线调试端口，请确认系统“无线调试”已开启且手机已连接 WLAN，然后在本机 ADB 页面检查连接。" }
        val result = executeOnce(block, discovered)
        if (enabled) configure(discovered, true, true)
        result
    }

    private suspend fun executeOnce(block: (WeChatDebugProbe) -> JSONObject, overridePort: Int? = null): JSONObject = gate.withLock {
        check(enabled) { "Local ADB is disabled. Open Settings > Phone permissions > Local ADB and enable it first." }
        check(!prefs.getBoolean("authorization_pending", false)) {
            "ADB authorization was not completed. Retry explicitly from Settings > Phone permissions > Local ADB; do not automatically retry."
        }
        check(!wireless || prefs.contains("paired_guid")) { "请先完成无线配对" }
        val selectedPort = overridePort ?: port
        val selectedWireless = wireless
        withTimeout(90_000) {
            suspendCancellableCoroutine { continuation ->
                val cancelled = AtomicBoolean(false)
                val active = AtomicReference<WeChatDebugProbe?>()
                val future = worker.submit {
                    try {
                        if (cancelled.get()) return@submit
                        val key = loadKey()
                        val probe = WeChatDebugProbe(selectedPort, key, requireTls = selectedWireless,
                            progress = { _progress.value = it },
                            authorizationPending = {
                                check(prefs.edit().putBoolean("authorization_pending", true).commit()) { "Could not record pending ADB authorization" }
                            },
                            authenticated = { prefs.edit().remove("authorization_pending").apply() },
                            connectionSession = scanConnection.get()?.transport?.takeUnless { it.closed },
                        )
                        active.set(probe)
                        activeOperation.set(probe)
                        if (cancelled.get() || !enabled) { probe.cancel(); throw CancellationException("Local ADB stopped") }
                        val result = block(probe).put("tls", selectedWireless)
                        _progress.value = "Connection succeeded"
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        active.set(null)
                        activeOperation.set(null)
                    }
                }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    active.get()?.cancel()
                    future.cancel(true)
                }
            }
        }
    }

    private fun loadKey(): AdbKey = cachedKey ?: run {
        val encoded = secrets.read(KEY_SLOT) ?: AdbKey.generate().also { secrets.write(KEY_SLOT, it) }
        AdbKey(encoded).also { cachedKey = it }
    }

    suspend fun pair(pairingPort: Int, code: String, connectionPort: Int? = null): JSONObject = gate.withLock {
        require(pairingPort in 1..65535 && (connectionPort == null || connectionPort in 1..65535) && pairingPort != connectionPort)
        withTimeout(25000) {
            suspendCancellableCoroutine { continuation ->
                val cancelled = AtomicBoolean(false)
                val future = worker.submit {
                    try {
                        val client = AdbPairingClient(loadKey())
                        activePairing.set(client)
                        client.use {
                            check(!cancelled.get()) { "Pairing cancelled" }
                            _progress.value = "正在进行 TLS / SPAKE2 无线配对"
                            val guid = it.pair(pairingPort, code)
                            check(!cancelled.get()) { "Pairing cancelled" }
                            check(prefs.edit().putString("paired_guid", guid).commit())
                            if (connectionPort != null) configure(connectionPort, true, true)
                            _progress.value = "无线配对成功，请测试连接"
                            if (continuation.isActive) continuation.resume(JSONObject().put("paired", true).put("port", connectionPort).put("tls", true))
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { activePairing.set(null) }
                }
                continuation.invokeOnCancellation { cancelled.set(true); activePairing.get()?.close(); future.cancel(true) }
            }
        }
    }

    companion object { private const val KEY_SLOT = "local-adb-rsa-v1" }
}

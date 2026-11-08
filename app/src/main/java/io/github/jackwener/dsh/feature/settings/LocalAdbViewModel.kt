package io.github.jackwener.dsh.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.adb.*
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.capability.Content
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import javax.inject.Inject

data class LocalAdbUiState(
    val busy: Boolean = false,
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val paired: Boolean = false,
    val wireless: Boolean = true,
    val port: Int = 0,
    val message: Int? = null,
    val article: ArticleSummary? = null,
)
data class ArticleSummary(val title: String, val account: String?, val reads: String?)

@HiltViewModel
class LocalAdbViewModel @Inject constructor(
    private val adb: LocalAdbRepository,
    private val capabilities: CapabilityHub,
    val pairing: AdbPairingState,
) : ViewModel() {
    private val mutable = MutableStateFlow(LocalAdbUiState(enabled = adb.enabled, paired = adb.paired, wireless = if (adb.enabled) adb.wireless else true, port = adb.port))
    val ui = mutable.asStateFlow()
    private var operation: Job? = null
    init {
        viewModelScope.launch {
            pairing.status.collect {
                when {
                    it.phase == PairingPhase.Connected -> refresh(true)
                    it.active || it.phase == PairingPhase.Failed -> refresh(false)
                }
            }
        }
    }
    fun refresh(connected: Boolean = mutable.value.connected) {
        mutable.value = mutable.value.copy(enabled = adb.enabled, paired = adb.paired, port = adb.port, wireless = if (adb.enabled) adb.wireless else mutable.value.wireless, connected = connected && adb.enabled)
    }
    fun check(port: String = adb.port.toString(), wireless: Boolean = adb.wireless) {
        val number = port.toIntOrNull()
        if (number == null || number !in 1..65535 || number == 15555) {
            mutable.value = mutable.value.copy(message = R.string.adb_port_error); return
        }
        runOperation(false) { adb.configure(number, true, wireless); invoke("wechat_debug_status") }
    }
    fun read() = runOperation(true) { invoke("wechat_article_read") }
    private suspend fun invoke(tool: String): JSONObject {
        val result = capabilities.invoke(buildJsonObject {
            put("tool", tool); put("sessionId", ""); put("args", JsonObject(emptyMap()))
        })
        check(result.ok)
        val text = result.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
        return JSONObject(text).also { check(!it.has("success") || it.optBoolean("success")) }
    }
    private fun runOperation(read: Boolean, block: suspend () -> JSONObject) {
        if (mutable.value.busy || pairing.status.value.active) return
        pairing.update(PairingPhase.Idle)
        operation = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = if (read) R.string.adb_reading else R.string.adb_checking)
            try {
                val result = block()
                val article = result.optJSONObject("article")?.let {
                    fun value(key: String): String? = if (it.isNull(key)) null else it.optString(key).takeIf(String::isNotBlank)
                    ArticleSummary(it.optString("title"), value("account"), value("read_text"))
                }
                refresh(true)
                mutable.value = mutable.value.copy(message = if (read) R.string.adb_snapshot else null, article = article ?: mutable.value.article)
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(message = R.string.adb_stopped)
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(connected = false, message = if (read) R.string.adb_read_error else R.string.adb_connect_error)
            } finally { refresh(); mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun disable() {
        operation?.cancel()
        adb.configure(adb.port, false)
        refresh(false)
        mutable.value = mutable.value.copy(message = R.string.adb_stopped)
    }
    fun cancel() { operation?.cancel() }
}

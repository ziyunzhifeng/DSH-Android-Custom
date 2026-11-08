package io.github.jackwener.dsh.feature.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.core.adb.LocalAdbRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** Debug builds only; shell/DUMP permission required. Keeps the system pairing dialog visible. */
@AndroidEntryPoint
class LocalAdbValidationReceiver : BroadcastReceiver() {
    @Inject lateinit var adb: LocalAdbRepository
    @Inject lateinit var collection: io.github.jackwener.dsh.core.wechat.WeChatCollection
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val report = try {
                when (intent.getStringExtra("operation")) {
                    "connection" -> adb.connectionState()
                    "begin_connection" -> { adb.beginScanConnection("debug-validation"); adb.status().put("lease", adb.connectionState()) }
                    "end_connection" -> { adb.endScanConnection("debug-validation"); adb.connectionState() }
                    "probe_read" -> adb.readArticle().put("lease", adb.connectionState())
                    "pair" -> adb.pair(intent.getIntExtra("pairing_port", 0), intent.getStringExtra("code") ?: "", intent.getIntExtra("port", 0))
                    "collect" -> collection.capture()
                    "navigate" -> adb.scanNavigate(intent.getStringExtra("action") ?: "open", null, null)
                    "screen" -> adb.scanScreen().apply {
                        File(context.noBackupFilesDir, "scan-screen.jpg").writeBytes(android.util.Base64.decode(getString("jpeg"), android.util.Base64.DEFAULT))
                        remove("jpeg")
                    }
                    "batch" -> JSONObject().put("batch", collection.dao.latestBatch().first()?.let { batch ->
                        JSONObject().put("id",batch.id).put("session",batch.sessionId).put("state",batch.state).put("reason",batch.reason)
                            .put("accounts",org.json.JSONArray(collection.dao.batchScans(batch.id).map {
                                JSONObject().put("account",it.account).put("state",it.state).put("reason",it.reason).put("count",collection.dao.count(it.id))
                            }))
                    } ?: JSONObject.NULL).put("heat",org.json.JSONArray(collection.dao.accountHeat().first().map {
                        JSONObject().put("account",it.account).put("articles",it.articles).put("total_reads",it.totalReads ?: JSONObject.NULL)
                    })).put("connection",adb.connectionState())
                    "ranking" -> JSONObject().put("articles", org.json.JSONArray(collection.dao.hot().first().map {
                        JSONObject().put("title", it.title).put("read_text", it.readText).put("comment_count", it.commentCount ?: JSONObject.NULL).put("loaded_comments", it.collectedComments).put("comment_status", it.commentStatus)
                    })).put("scan", collection.dao.latest().first()?.let { JSONObject().put("state", it.scan.state).put("reason", it.scan.reason).put("count", it.count).put("session", it.scan.sessionId) } ?: JSONObject.NULL)
                    "read" -> { adb.configure(intent.getIntExtra("port", 0), true, true); adb.readArticle() }
                    else -> { adb.configure(intent.getIntExtra("port", 0), true, true); adb.status() }
                }
            } catch (error: Exception) {
                JSONObject().put("success", false).put("error", error.javaClass.simpleName).apply {
                    if (intent.getStringExtra("operation") != "pair") put("detail", error.stackTraceToString().take(5000))
                }
            }
            try { File(context.noBackupFilesDir, "wireless-validation.json").writeText(report.toString(2)) }
            finally { pending.finish() }
        }
    }
}

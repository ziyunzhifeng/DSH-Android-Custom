package io.github.jackwener.dsh.core.capability.tools

import io.github.jackwener.dsh.core.adb.LocalAdbRepository
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.Content
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeChatTools @Inject constructor(private val adb: LocalAdbRepository, private val collection: io.github.jackwener.dsh.core.wechat.WeChatCollection) : ToolSet {
    private suspend fun result(block: suspend () -> JSONObject): ToolResult = try {
        ToolResult.text(block().toString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolResult.error("local_adb_failed", error.message ?: error.javaClass.simpleName)
    }

    private suspend fun progress(session: String): JSONObject {
        val batch = collection.dao.batchForSession(session)
        val scan = collection.dao.forSession(session)
        val response = JSONObject().put("scan_id", scan?.id).put("account", scan?.account).put("limit", scan?.limit)
            .put("state", scan?.state).put("count", scan?.let { collection.dao.count(it.id) })
            .put("visited", org.json.JSONArray(scan?.let { collection.dao.visited(it.id) } ?: emptyList<String>()))
        if (batch != null) response.put("batch_id", batch.id).put("batch_state", batch.state).put("per_account_limit", batch.articleLimit).put("batch_reason", batch.reason)
            .put("accounts", org.json.JSONArray(collection.dao.batchScans(batch.id).map {
                JSONObject().put("account_id", it.accountId).put("account", it.account).put("state", it.state)
                    .put("count", collection.dao.count(it.id)).put("reason", it.reason)
            }))
        return response
    }

    override val tools = listOf(
        object : Tool("wechat_debug_status", ToolGroup.Browser, Risk.read, false,
            "Check configured local ADB.", {}, timeoutMs = 100_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = result { adb.status() }
        },
        object : Tool("wechat_debug_targets", ToolGroup.Browser, Risk.read, false,
            "List WeChat article targets.", {}, timeoutMs = 100_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = result {
                if (io.github.jackwener.dsh.core.wechat.WeChatScanPolicy.applies(call.sessionId)) collection.navigationAllowed(call.sessionId)
                adb.targets()
            }
        },
        object : Tool("wechat_article_read", ToolGroup.Browser, Risk.read, false,
            "Read current CDP article and loaded comments.", {
                string("targetId", "Target ID")
            }, timeoutMs = 100_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = result { adb.readArticle(args.str("targetId")) }
        },
        object : Tool("wechat_scan", ToolGroup.Browser, Risk.write, true,
            "WeChat read navigation and scan checkpoints. XML pixel coordinates.", {
                string("action", "", true, listOf("screen", "open", "tap", "down", "up", "back", "end", "comments", "progress", "finish", "begin_account", "skip_account", "finish_batch"))
                int("x", ""); int("y", ""); string("name", "")
                string("reason", "For finish", enum = listOf("visible_list_end", "blocked", "no_progress", "limit_reached"))
            }, timeoutMs = 100_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = try {
                val action = args.str("action") ?: error("Missing action")
                when (action) {
                    "progress" -> ToolResult.text(progress(call.sessionId).toString())
                    "begin_account" -> ToolResult.text(collection.beginAccount(call.sessionId).toString())
                    "finish", "finish_batch", "skip_account" -> {
                        val reason = args.str("reason") ?: error("Missing reason")
                        require(reason in setOf("visible_list_end", "blocked", "no_progress", "limit_reached"))
                        when (action) {
                            "finish" -> collection.finishAccount(call.sessionId, reason)
                            "finish_batch" -> collection.finishBatch(call.sessionId, reason)
                            else -> collection.skipAccount(call.sessionId, args.str("name") ?: error("Missing name"), reason)
                        }
                        ToolResult.text(progress(call.sessionId).toString())
                    }
                    "screen" -> {
                        collection.navigationAllowed(call.sessionId)
                        val screen = adb.scanScreen()
                        ToolResult(true, listOf(Content.Text(JSONObject().put("width", screen.getInt("width")).put("height", screen.getInt("height")).put("xml", screen.getString("xml")).toString()), Content.Image("image/jpeg", screen.getString("jpeg"))))
                    }
                    else -> {
                        collection.navigationAllowed(call.sessionId)
                        val response = adb.scanNavigate(action, args.int("x"), args.int("y"), allowBringToFront = false)
                        kotlinx.coroutines.delay(1200)
                        ToolResult.text(response.toString())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (error is io.github.jackwener.dsh.core.adb.WeChatForegroundLostException) collection.pauseForForegroundChange(call.sessionId)
                ToolResult.error("wechat_scan_failed", error.message ?: "Scan failed")
            }
        },
        object : Tool("wechat_article_collect", ToolGroup.Browser, Risk.write, false,
            "Save CDP metrics/comments; paginate with comments_next_offset.", { string("targetId", ""); int("offset", "") }, timeoutMs = 100_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = result { collection.capture(call.sessionId, args.str("targetId"), commentOffset = args.int("offset") ?: 0) }
        },
    )
}

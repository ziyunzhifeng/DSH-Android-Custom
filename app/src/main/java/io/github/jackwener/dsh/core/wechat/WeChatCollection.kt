package io.github.jackwener.dsh.core.wechat

import androidx.room.withTransaction
import io.github.jackwener.dsh.core.adb.LocalAdbRepository
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeChatCollection @Inject constructor(val dao: WeChatDao, private val db: WeChatDatabase, private val adb: LocalAdbRepository) {
    suspend fun active(session: String): WeChatScan {
        val scan = checkNotNull(dao.forSession(session)) { "Start a scan from the WeChat collection page first." }
        scan.batchId?.let { check(dao.batch(it)?.state == "running") { "Batch is not running" } }
        check(scan.state == "running") { "Scan is not running; no action was taken." }
        return scan
    }
    suspend fun navigationAllowed(session: String) {
        val batch = dao.batchForSession(session)
        if (batch != null) check(batch.state == "running") { "Batch is not running" } else active(session)
    }
    suspend fun pauseForForegroundChange(session: String) = db.withTransaction {
        val batch = dao.batchForSession(session)
        if (batch?.state == "running") dao.batchState(batch.id, "paused", "left_wechat")
        dao.forSession(session)?.takeIf { it.state == "running" }?.let { dao.state(it.id, "paused", "left_wechat") }
    }
    suspend fun beginAccount(session: String): JSONObject {
        navigationAllowed(session)
        val batch = checkNotNull(dao.batchForSession(session)) { "Not a followed-account task" }
        val result = adb.readArticle(requireVisible = true)
        return registerAccount(batch, result)
    }
    internal suspend fun registerAccount(batch: WeChatBatch, result: JSONObject): JSONObject {
        val article = result.getJSONObject("article")
        check(article.optBoolean("account_identity_verified")) { "Account identity not verified; do not merge by nickname" }
        val identity = article.optString("account_identity").takeIf { it.isNotBlank() && it != "null" }
            ?: error("No account identity; open a loaded article first")
        val account = article.getString("account").takeIf { it.isNotBlank() && it != "null" } ?: error("No account name")
        val accountId = WeChatMetrics.id(identity)
        return db.withTransaction {
            check(dao.batch(batch.id)?.state == "running") { "Batch stopped" }
            val scans = dao.batchScans(batch.id)
            val current = scans.firstOrNull { it.state == "running" }
            check(current == null || current.accountId == accountId) { "Finish the current account first" }
            val existing = scans.firstOrNull { it.accountId == accountId }
            if (existing != null) JSONObject().put("account", existing.account).put("state", existing.state)
                .put("already_visited", existing.state != "running").put("count", dao.count(existing.id))
            else {
                val scan = WeChatScan(UUID.randomUUID().toString(), batch.sessionId, accountId, account, limit = batch.articleLimit, batchId = batch.id)
                dao.save(scan)
                JSONObject().put("account", account).put("state", "running").put("already_visited", false).put("limit", scan.limit)
            }
        }
    }
    suspend fun finishAccount(session: String, reason: String) = db.withTransaction {
        navigationAllowed(session)
        val scan = checkNotNull(dao.forSession(session)) { "Open an article and begin_account first" }
        if (scan.state == "running") dao.state(scan.id,
            if (dao.count(scan.id) >= scan.limit || reason == "visible_list_end") "completed" else "partial", reason)
    }
    suspend fun skipAccount(session: String, name: String, reason: String) = db.withTransaction {
        navigationAllowed(session)
        val batch = checkNotNull(dao.batchForSession(session))
        check(dao.batchScans(batch.id).none { it.state == "running" }) { "Finish current account first" }
        require(name.isNotBlank() && name.length <= 200)
        val key = "unverified:" + WeChatMetrics.id(name)
        if (dao.batchScans(batch.id).none { it.accountId == key }) dao.save(WeChatScan(
            UUID.randomUUID().toString(), session, key, name, state = "skipped", reason = reason, batchId = batch.id))
    }
    suspend fun finishBatch(session: String, reason: String) = db.withTransaction {
        val batch = checkNotNull(dao.batchForSession(session))
        check(batch.state == "running") { "Batch stopped" }
        check(dao.batchScans(batch.id).none { it.state == "running" }) { "Finish current account first" }
        dao.batchState(batch.id, if (reason == "visible_list_end") "completed" else "paused", reason)
    }
    suspend fun capture(session: String? = null, target: String? = null, requireVisible: Boolean = session != null, commentOffset: Int = 0): JSONObject {
        require(commentOffset in 0..100000)
        session?.let { active(it) }
        val result = adb.readArticle(target, requireVisible, commentOffset)
        return persist(result, session)
    }
    internal suspend fun persist(result: JSONObject, session: String? = null): JSONObject {
        val scan = session?.let { active(it) }
        val article = result.getJSONObject("article")
        val title = article.getString("title").trim()
        val account = article.optString("account").takeIf { it.isNotBlank() && it != "null" } ?: error("Account not loaded")
        val accountId = WeChatMetrics.id(article.optString("account_identity").takeIf { it.isNotBlank() } ?: account)
        val identity = article.optString("article_identity").takeIf { it.isNotBlank() } ?: error("No stable article identity; refusing to deduplicate by title alone")
        val id = WeChatMetrics.id(identity)
        if (scan != null) check(scan.accountId == accountId) { "Different account; article was not saved." }
        fun text(name: String) = if (article.isNull(name)) null else article.optString(name).takeIf { it.isNotBlank() && it != "null" }
        val reads = WeChatMetrics.count(text("read_text"))
        val count = WeChatMetrics.count(text("comment_count_text"))
        val now = result.getLong("collected_at_ms")
        val comments = article.optJSONArray("comments")
        val items = (0 until (comments?.length() ?: 0)).take(500).mapNotNull { index ->
            val c = comments!!.getJSONObject(index)
            val content = c.optString("text").trim().take(10000).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val author = c.optString("author").takeIf { it.isNotBlank() && it != "null" }?.take(200)
            val key = c.optString("id").takeIf { it.isNotBlank() } ?: WeChatMetrics.id("$author\n$content")
            CollectedComment(id, key, author, content, c.optString("likes_text").takeIf { it.isNotBlank() && it != "null" }, now)
        }.distinctBy { it.id }
        db.withTransaction {
            if (scan != null) {
                check(active(session).id == scan.id) { "Active account changed while collecting" }
                val visited = dao.visited(scan.id)
                check(id in visited || visited.size < scan.limit) { "Scan limit reached" }
            }
            dao.save(CollectedArticle(id, accountId, account, title, text("read_text"), reads?.value, reads?.lowerBound ?: false,
                text("comment_count_text"), count?.value, items.size, text("comment_status") ?: "not_loaded", now))
            // Raw page values are immutable evidence for each collection, including missing metrics.
            dao.snapshot(ArticleSnapshot(UUID.randomUUID().toString(), id, scan?.id, result.toString(), now))
            dao.comments(items)
            if (scan != null) {
                dao.visit(ScanArticle(scan.id, id))
                if (dao.count(scan.id) >= scan.limit && article.isNull("comments_next_offset")) dao.state(scan.id, "completed", "limit_reached")
            }
        }
        return JSONObject().put("article_id", id).put("account", account).put("title", title)
            .put("read_text", text("read_text") ?: JSONObject.NULL).put("comment_count_text", text("comment_count_text") ?: JSONObject.NULL)
            .put("comments_next_offset", article.opt("comments_next_offset") ?: JSONObject.NULL)
            .put("loaded_comments", items.size).put("comment_status", text("comment_status"))
            .put("saved", true).put("scan_count", scan?.let { dao.count(it.id) } ?: JSONObject.NULL)
    }
}

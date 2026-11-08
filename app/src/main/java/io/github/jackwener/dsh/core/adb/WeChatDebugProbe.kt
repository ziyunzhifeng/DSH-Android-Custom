// Adapted from Shizuku+ / Shizuku (Apache-2.0); see docs/LOCAL-ADB-NOTICE.md.
package io.github.jackwener.dsh.core.adb

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** Local ADB transport, fixed article extraction and bounded device commands owned by the scan repository. */
class WeChatDebugProbe(
    private val port: Int,
    private val key: AdbKey,
    private val requireTls: Boolean = false,
    private val progress: (String) -> Unit = {},
    private val authorizationPending: () -> Unit = {},
    private val authenticated: () -> Unit = {},
    private val connectionSession: AdbConnectionSession? = null,
) {
    @Volatile private var active: AdbClient? = null

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        active?.close()
    }

    private fun <T> connected(block: (AdbClient) -> T): T {
        check(!cancelled) { "Probe cancelled" }
        if (connectionSession != null) {
            try {
                return connectionSession.withClient(port, key, requireTls, authorizationPending, authenticated,
                    onClient = { active = it; if (cancelled) it.close() }) { client ->
                    check(!cancelled) { "Probe cancelled" }
                    block(client)
                }
            } finally { active = null }
        }
        return AdbClient("127.0.0.1", port, key, requireTls) {
            authorizationPending()
            progress("Allow this app's ADB key in the Android debugging dialog.")
        }.use { client ->
            active = client
            try {
                check(!cancelled) { "Probe cancelled" }
                client.connect()
                authenticated()
                block(client)
            } finally {
                active = null
            }
        }
    }

    private fun discoverSockets(): List<String> {
        require(port in 1..65535 && port != 15555) { "Use the real adbd port, not the shell-only proxy (15555)." }
        progress("Connecting to real adbd on 127.0.0.1:$port")
        val discovery = ByteArrayOutputStream()
        connected { client ->
            client.command("shell:cat /proc/net/unix; echo __WECHAT_PS__; ps -A") { bytes ->
                check(discovery.size() + bytes.size <= MAX_BODY) { "Discovery output too large" }
                discovery.write(bytes)
            }
        }
        val text = discovery.toString("UTF-8")
        val processes = text.substringAfter("__WECHAT_PS__", "")
        val pids =
            processes
                .lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size > 2 && (it.last() == "com.tencent.mm" || it.last().startsWith("com.tencent.mm:")) }
                .map { it[1] }
                .toSet()
        val sockets =
            Regex("@([A-Za-z0-9_.-]*devtools[A-Za-z0-9_.-]*)")
                .findAll(text.substringBefore("__WECHAT_PS__"))
                .map { it.groupValues[1] }
                .distinct()
                .filter { it.substringAfterLast('_') in pids }
                .toList()
        check(sockets.isNotEmpty()) { "No WeChat debug socket found. Open an article with XWeb debugging enabled." }
        progress("ADB authenticated; discovered ${sockets.size} WeChat debug socket(s).")

        return sockets
    }

    fun checkConnection(): JSONObject = connected {
        it.command("shell:echo dsh-local-adb-ok")
        JSONObject().put("tls", it.isTls).put("connected", true).put("port", port).put("transport", "on-device-adb-localabstract")
    }

    fun targets(): JSONObject {
        val pages = JSONArray()
        val failures = JSONArray()
        for (socket in discoverSockets()) {
            try {
                val targets = JSONArray(httpGet(socket, "/json/list"))
                for (index in 0 until targets.length()) {
                    val target = targets.getJSONObject(index)
                    val url = Uri.parse(target.optString("url"))
                    if (target.optString("type") == "page" && url.host == "mp.weixin.qq.com" && (url.path == "/s" || url.path.orEmpty().startsWith("/s/"))) {
                        val title = target.optString("title").takeUnless { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
                        pages.put(JSONObject().put("id", target.getString("id")).put("socket", socket)
                            .put("title", title).put("url", "https://mp.weixin.qq.com/s"))
                    }
                }
            } catch (error: Exception) {
                failures.put(JSONObject().put("socket", socket).put("error", error.javaClass.simpleName))
            }
        }
        return JSONObject().put("pages", pages).put("failures", failures)
    }

    fun run(targetId: String? = null, requireVisible: Boolean = false, commentOffset: Int = 0, scrollToEnd: Boolean = false, openComments: Boolean = false): JSONObject {
        val sockets = discoverSockets()
        val attempts = JSONArray()
        for (socket in sockets) {
            try {
                progress("Querying $socket /json/list")
                val targets = JSONArray(httpGet(socket, "/json/list"))
                for (index in 0 until targets.length()) {
                    val target = targets.getJSONObject(index)
                    val url = Uri.parse(target.optString("url"))
                    if (target.optString("type") != "page" || url.host != "mp.weixin.qq.com" || (url.path != "/s" && !url.path.orEmpty().startsWith("/s/"))) continue
                    if (targetId != null && target.optString("id") != targetId) continue
                    val websocket = Uri.parse(target.getString("webSocketDebuggerUrl"))
                    val path = websocket.encodedPath ?: continue
                    require(path.startsWith("/devtools/page/") && path.all { it.code in 33..126 })
                    val article = evaluate(socket, path, commentOffset, scrollToEnd, openComments)
                    if (requireVisible && !article.optBoolean("page_visible")) continue
                    return JSONObject()
                        .put("success", true)
                        .put("transport", "on-device-adb-localabstract")
                        .put("adb_port", port)
                        .put("socket", socket)
                        .put("target_count", targets.length())
                        .put("target_id", target.getString("id"))
                        .put("article", article)
                        .put("collected_at_ms", System.currentTimeMillis())
                        .put("earlier_failures", attempts)
                }
                attempts.put(JSONObject().put("socket", socket).put("error", "No open article target; templates are ignored."))
            } catch (error: Exception) {
                attempts.put(JSONObject().put("socket", socket).put("error", error.javaClass.simpleName))
            }
        }
        throw IOException("No article passed HTTP/WebSocket/CDP validation: $attempts")
    }

    fun deviceCommand(command: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
        val output = ByteArrayOutputStream()
        connected { client -> client.command("shell:$command") { bytes ->
            check(output.size() + bytes.size <= maxBytes) { "Device output too large" }
            output.write(bytes)
        } }
        return output.toByteArray()
    }

    private fun httpGet(
        socket: String,
        path: String,
    ): String =
        connected { client ->
            client.openService("localabstract:$socket", reuseTransport = connectionSession != null).use { stream ->
                stream.output.write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                val headers = headers(stream.input)
                check(headers.first.split(' ').getOrNull(1) == "200") { "DevTools HTTP request failed" }
                val body = ByteArrayOutputStream()
                val input = DataInputStream(stream.input)
                if (headers.second["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
                    while (true) {
                        val count = line(input).substringBefore(';').trim().toInt(16)
                        require(count >= 0 && body.size() + count <= MAX_BODY)
                        if (count == 0) break
                        body.write(ByteArray(count).also { input.readFully(it) })
                        check(line(input).isEmpty())
                    }
                } else if (headers.second.containsKey("content-length")) {
                    val count = headers.second.getValue("content-length").toInt()
                    require(count in 0..MAX_BODY)
                    body.write(ByteArray(count).also { input.readFully(it) })
                } else {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(body.size() + count <= MAX_BODY)
                        body.write(buffer, 0, count)
                    }
                }
                body.toString("UTF-8")
            }
        }

    private fun evaluate(
        socket: String,
        path: String,
        commentOffset: Int = 0,
        scrollToEnd: Boolean = false,
        openComments: Boolean = false,
    ): JSONObject =
        connected { client ->
            client.openService("localabstract:$socket", reuseTransport = connectionSession != null).use { stream ->
                val nonce = ByteArray(16).also { random.nextBytes(it) }
                val token = Base64.encodeToString(nonce, Base64.NO_WRAP)
                stream.output.write(
                    (
                        "GET $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
                            "Connection: Upgrade\r\nSec-WebSocket-Key: $token\r\nSec-WebSocket-Version: 13\r\n\r\n"
                    ).toByteArray(),
                )
                val headers = headers(stream.input)
                check(headers.first.contains(" 101 ")) { "WebSocket upgrade failed" }
                val accept =
                    Base64.encodeToString(
                        MessageDigest.getInstance("SHA-1").digest(
                            (token + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(),
                        ),
                        Base64.NO_WRAP,
                    )
                check(headers.second["sec-websocket-accept"] == accept) { "Invalid WebSocket handshake" }
                progress("WebSocket connected; evaluating read-only article fields.")
                val expression =
                    """
                    (() => {
                      const visible = e => !!e && e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
                      const text = e => e?.innerText?.trim() || null;
                      const first = (root, selectors) => { for (const selector of selectors) { const e = root.querySelector(selector); if (visible(e)) return text(e); } return null; };
                      const title = text(document.querySelector('#activity-name')) || document.title;
                      const account = text(document.querySelector('#js_name'));
                      const area = [...document.querySelectorAll('[id^="js_read_area"]')].find(visible);
                      const readText = text(area?.querySelector('[id^="readNum"]')) || text(area)?.match(/阅读\s*([\d.,万亿+]+)/)?.[1] || null;
                      const url = new URL(location.href);
                      const bizId = url.searchParams.get('__biz') || (typeof biz !== 'undefined' ? String(biz) : null);
                      const messageId = url.searchParams.get('mid') || (typeof mid !== 'undefined' ? String(mid) : null);
                      const item = url.searchParams.get('idx') || (typeof idx !== 'undefined' ? String(idx) : '1');
                      const identity = bizId && messageId ? bizId + ':' + messageId + ':' + item : url.pathname.startsWith('/s/') ? url.pathname : null;
                      const commentText = first(document, ['#js_cmt_num', '#js_comment_num', '#js_comment_bottom_num', '#js_cmt_container .comment-flex_title', '#js_bt_cmt_container .comment-flex_title', '.js_comment_num', '[id^="commentNum"]']);
                      const countText = commentText?.match(/(?:^|[留言评论\s(（])([\d.,万亿+]+)(?:[)）]|$)/)?.[1] || (/^[\d.,万亿+]+$/.test(commentText || '') ? commentText : null);
                      const nodes = [...document.querySelectorAll('.js_comment_item, .comment_item, .discuss_item, .js_reply_item')].filter(visible);
                      const offset = 0;
                      const comments = nodes.slice(offset,offset+500).map(e => {
                        const reply = e.classList?.contains('js_reply_item') || false;
                        const parent = reply ? e.closest?.('.js_comment_item, .comment_item, .discuss_item') : null;
                        return {
                        id: e.getAttribute('data-content-id') || e.getAttribute('data-id') || e.id || e.querySelector('[id^="js_discuss_user_nickname_"]')?.id || null,
                        kind: reply ? 'reply' : 'comment', parent_id: parent?.id || null,
                        author: first(e, ['.nickname', '.nick_name', '.comment_nickname', '.js_comment_nickname', '.discuss_user_nickname']),
                        text: first(e, reply ? ['.discuss_message_content', '.discuss_message .actual-content', '.js_comment_content'] : ['.js_comment_content', '.comment_content', '.discuss_message_content', '.content']),
                        likes_text: first(e, ['.praise_num', '.like_num', '.js_praise_num'])
                      }; }).filter(c => c.text);
                      const commentArea = document.querySelector('#js_cmt_area') || document.querySelector('#js_comment_area') || document.querySelector('#js_cmt_container');
                      const areaText = visible(commentArea) ? text(commentArea) || '' : '';
                      const noComments = !comments.length && /暂无留言|暂无评论|还没有留言|还没有评论/.test(areaText);
                      const end = /已显示全部留言|已显示全部评论|没有更多留言|没有更多评论/.test(areaText) || [...(commentArea?.querySelectorAll('.weui-loadmore') || [])].some(e => visible(e) && /已无更多数据/.test(text(e) || ''));
                      return {title, account, page_visible: document.visibilityState === 'visible', article_identity: identity, account_identity: bizId || account, account_identity_verified: !!bizId,
                        read_text: readText, comment_count_text: countText, comments,
                        comment_nodes_loaded: nodes.length, comments_next_offset: offset + 500 < nodes.length ? offset + 500 : null,
                        comment_status: noComments ? 'explicit_empty' : end ? 'loaded_end' : comments.length ? 'loaded_partial' : 'not_loaded',
                        comment_scope: 'published_page_comments', published_text: text(document.querySelector('#publish_time')),
                        ready_state: document.readyState};
                    })()
                    """.trimIndent().replace("const offset = 0;", "const offset = $commentOffset;")
                val navigation = when { scrollToEnd -> SCROLL_END; openComments -> OPEN_COMMENTS; else -> null }
                val evaluatedExpression = if (navigation == null) expression else
                    "(() => { const performed = $navigation; const article = $expression; article.navigation_performed = performed; return article; })()"
                val request =
                    JSONObject().put("id", 1).put("method", "Runtime.evaluate").put(
                        "params",
                        JSONObject().put("expression", evaluatedExpression).put("returnByValue", true),
                    )
                frame(stream, 1, request.toString().toByteArray())
                val input = DataInputStream(stream.input)
                val fragments = ByteArrayOutputStream()
                var fragmenting = false
                repeat(100) {
                    val first = input.readUnsignedByte()
                    val second = input.readUnsignedByte()
                    check(first and 0x70 == 0 && second and 0x80 == 0) { "Unsupported WebSocket frame" }
                    val opcode = first and 15
                    val fin = first and 128 != 0
                    val size =
                        when (val length = second and 127) {
                            126 -> input.readUnsignedShort().toLong()
                            127 -> input.readLong()
                            else -> length.toLong()
                        }
                    require(size in 0..MAX_BODY.toLong()) { "WebSocket frame too large" }
                    if (opcode >= 8) require(fin && size <= 125)
                    val payload = ByteArray(size.toInt()).also { input.readFully(it) }
                    when (opcode) {
                        8 -> throw EOFException("DevTools closed the WebSocket")
                        9 -> frame(stream, 10, payload)
                        10 -> Unit
                        0, 1 -> {
                            check(if (opcode == 0) fragmenting else !fragmenting)
                            require(fragments.size() + payload.size <= MAX_BODY)
                            fragments.write(payload)
                            fragmenting = !fin
                            if (fin) {
                                val message = JSONObject(fragments.toString("UTF-8"))
                                fragments.reset()
                                if (message.optInt("id") == 1) {
                                    check(!message.has("error")) { "CDP returned an error" }
                                    val result = message.getJSONObject("result")
                                    check(!result.has("exceptionDetails")) { "Page evaluation failed" }
                                    val article = result.getJSONObject("result").getJSONObject("value")
                                    check(article.optString("title").isNotBlank()) { "Article title is not loaded" }
                                    frame(stream, 8, ByteArray(0))
                                    return@connected article
                                }
                            }
                        }
                        else -> throw IOException("Unsupported WebSocket opcode")
                    }
                }
                throw IOException("No CDP response within the message limit")
            }
        }

    private fun frame(
        stream: AdbServiceStream,
        opcode: Int,
        payload: ByteArray,
    ) {
        require(payload.size <= 65535)
        val bytes = ByteArrayOutputStream()
        bytes.write(0x80 or opcode)
        if (payload.size < 126) {
            bytes.write(0x80 or payload.size)
        } else {
            bytes.write(0x80 or 126)
            bytes.write(payload.size shr 8)
            bytes.write(payload.size and 255)
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        bytes.write(mask)
        payload.forEachIndexed { index, byte -> bytes.write(byte.toInt() xor mask[index % 4].toInt()) }
        stream.output.write(bytes.toByteArray())
    }

    private fun headers(input: InputStream): Pair<String, Map<String, String>> {
        val status = line(input)
        val headers = mutableMapOf<String, String>()
        var total = status.length
        while (true) {
            val line = line(input)
            total += line.length
            require(total <= 32768) { "HTTP headers too large" }
            if (line.isEmpty()) break
            require(':' in line)
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        return status to headers
    }

    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Incomplete HTTP response")
            if (byte == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(byte)
        }
        throw IOException("HTTP line too long")
    }

    companion object {
        internal const val OPEN_COMMENTS = """(() => {
            if (document.visibilityState !== 'visible') return false;
            const count = document.querySelector('#js_comment_bottom_num');
            if (!count || !count.getClientRects().length || getComputedStyle(count).visibility === 'hidden' || !/^[1-9][0-9]*(?:[.,][0-9]+)?[万亿]?[+]?$/.test(count.innerText.trim())) return false;
            const button = count.closest('button.sns_sc_comment_btn');
            if (!button) return false;
            button.click();
            return true;
        })()"""
        internal const val SCROLL_END = """(() => {
            if (document.visibilityState !== 'visible') return false;
            window.scrollTo(0, Math.max(document.body?.scrollHeight || 0, document.documentElement?.scrollHeight || 0));
            return true;
        })()"""

        private const val MAX_BODY = 2 * 1024 * 1024
        private val random = SecureRandom()
    }
}

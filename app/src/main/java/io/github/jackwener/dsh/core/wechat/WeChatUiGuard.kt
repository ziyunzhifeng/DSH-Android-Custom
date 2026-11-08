package io.github.jackwener.dsh.core.wechat

/** Android's XML pull parser avoids unsupported desktop SAX feature flags. */
object WeChatUiGuard {
    private val forbidden = Regex("^(发送|发表|赞|点赞|关注|取消关注|删除|举报|投诉|打赏|购买|付款|支付|分享|转发|Send|Like|Follow|Delete|Pay|Share)$", RegexOption.IGNORE_CASE)
    fun checkTap(xml: String, x: Int, y: Int): Boolean {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "Invalid UI hierarchy" }
        if (!xml.contains("<hierarchy")) return false // Dynamic WeChat pages can omit the accessibility hierarchy; use the current screenshot.
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                val bounds = Regex("[0-9]+").findAll(parser.getAttributeValue(null, "bounds").orEmpty()).map { it.value.toInt() }.toList()
                if (bounds.size == 4 && x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3]) {
                    check(!forbidden.matches(parser.getAttributeValue(null, "text").orEmpty().trim()) &&
                        !forbidden.matches(parser.getAttributeValue(null, "content-desc").orEmpty().trim())) { "This control is outside read-only scanning." }
                }
            }
            parser.next()
        }
        return true
    }
}

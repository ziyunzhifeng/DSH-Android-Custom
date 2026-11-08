package io.github.jackwener.dsh.core.wechat
import org.junit.Assert.*
import org.junit.Test
class WeChatMetricsTest {
    @Test fun `normalizes displayed lower bounds without inventing missing values`() {
        assertEquals(WeChatMetrics.Count(100000,true), WeChatMetrics.count("10万+"))
        assertEquals(WeChatMetrics.Count(12000,false), WeChatMetrics.count("1.2万"))
        assertEquals(WeChatMetrics.Count(1234,false), WeChatMetrics.count("1,234"))
        assertEquals(WeChatMetrics.Count(0,false), WeChatMetrics.count("0"))
        for (value in listOf(null,"","未知","阅读100","-1","0.3","99999999999999999999999")) assertNull(WeChatMetrics.count(value))
    }
    @Test fun `scan session can only invoke its own restricted tools`() {
        val session = WeChatScanPolicy.PREFIX + "test"
        assertTrue(WeChatScanPolicy.allows(session,"wechat_article_collect"))
        for (name in listOf("screen_tap","shell","browser_evaluate","wechat_article_read","send_message")) assertFalse(WeChatScanPolicy.allows(session,name))
        assertTrue(WeChatScanPolicy.allows("normal-session","screen_tap"))
        assertTrue(WeChatScanPolicy.mode.engineTools!!.isEmpty())
    }
    @Test fun `scan controls never create a second model question while normal chats may ask`() {
        assertFalse(WeChatScanPolicy.allowsUserQuestions(WeChatScanPolicy.PREFIX+"sample"))
        assertTrue(WeChatScanPolicy.allowsUserQuestions("normal-chat"))
    }

}

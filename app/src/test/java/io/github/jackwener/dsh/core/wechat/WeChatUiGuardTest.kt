package io.github.jackwener.dsh.core.wechat
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(application = Application::class, sdk = [33])
class WeChatUiGuardTest {
    @Test fun `Android parser accepts read navigation and blocks outbound controls`() {
        val xml="""<hierarchy><node text="文章标题" bounds="[0,0][200,100]"/><node text="发送" bounds="[0,100][200,200]"/></hierarchy>"""
        assertTrue(WeChatUiGuard.checkTap(xml,50,50))
        assertThrows(IllegalStateException::class.java) { WeChatUiGuard.checkTap(xml,50,150) }
    }
    @Test fun `dynamic pages explicitly fall back to screenshots while DTD is rejected`() {
        assertFalse(WeChatUiGuard.checkTap("cat: UI unavailable",50,50))
        assertThrows(IllegalArgumentException::class.java) { WeChatUiGuard.checkTap("<!DOCTYPE hierarchy><hierarchy/>",50,50) }
    }
}

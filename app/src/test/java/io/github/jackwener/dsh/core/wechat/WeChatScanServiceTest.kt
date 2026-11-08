package io.github.jackwener.dsh.core.wechat

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeChatScanServiceTest {
    private class Host(val service: WeChatScanService, val reply: Boolean = true) : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        var starts = 0
        var stops = 0
        var unbinds = 0
        override fun startForegroundService(intent: Intent): ComponentName {
            starts++
            return ComponentName(this, WeChatScanService::class.java)
        }
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (reply) connection.onServiceConnected(ComponentName(this, WeChatScanService::class.java), service.onBind(intent))
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
        override fun stopService(intent: Intent): Boolean { stops++; return true }
    }

    @Test fun `scan service is in the tool process and provides a quiet progress notification`() {
        val controller = Robolectric.buildService(WeChatScanService::class.java).create()
        val service = controller.get()
        val info = service.packageManager.getServiceInfo(ComponentName(service, WeChatScanService::class.java), 0)
        assertEquals(service.packageName, info.processName)
        assertFalse(info.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        val notification = service.notification()
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(WeChatScanService.CHANNEL, notification.channelId)
        assertEquals("io.github.jackwener.dsh.feature.settings.WeChatCollectionActivity",
            shadowOf(notification.contentIntent).savedIntent.component?.className)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
        controller.destroy()
        assertFalse(shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test fun `only the owning task releases its foreground service`() = runBlocking {
        val service = Robolectric.buildService(WeChatScanService::class.java).create()
        val host = Host(service.get())
        val runtime = WeChatScanRuntime(host)
        try {
            runtime.begin("task-a")
            assertEquals(1, host.starts)
            runtime.end("stale-task")
            assertEquals(0, host.stops)
            runtime.end("task-a")
            assertEquals(1, host.unbinds)
            assertEquals(1, host.stops)
            runtime.begin("task-b")
            runtime.end("task-b")
            assertEquals(2, host.stops)
        } finally { service.destroy() }
    }

    @Test fun `cancelled startup releases the binding and never starts navigation`() = runBlocking {
        val service = Robolectric.buildService(WeChatScanService::class.java).create()
        val host = Host(service.get(), reply = false)
        val runtime = WeChatScanRuntime(host)
        var ready = false
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { runtime.begin("cancelled"); ready = true }
            assertFalse(ready)
            job.cancelAndJoin()
            assertEquals(1, host.unbinds)
            assertEquals(1, host.stops)
        } finally { service.destroy() }
    }
}

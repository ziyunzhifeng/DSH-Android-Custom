package io.github.jackwener.dsh.core.adb

import android.app.Application
import android.app.Notification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AdbPairingNotificationTest {
    @Test fun `reply action uses explicit mutable service intent and private system notification`() {
        val service = Robolectric.buildService(AdbPairingService::class.java).get()
        val notification = service.notification(PairingPhase.Found)
        assertEquals(AdbPairingService.CHANNEL, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val action = notification.actions.single { it.remoteInputs?.isNotEmpty() == true }
        val input = action.remoteInputs.single()
        assertEquals("pairing_code", input.resultKey)
        assertTrue(input.allowFreeFormInput)
        assertFalse(action.allowGeneratedReplies)
        assertFalse(action.actionIntent.isImmutable)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(AdbPairingService::class.java.name, intent.component?.className)
        assertEquals("dsh.adb.pair.REPLY", intent.action)
        assertFalse(intent.getStringExtra("session").isNullOrBlank())
        assertFalse(intent.hasExtra("code"))
    }
    @Test fun `stale replies are ignored and short codes never reach pairing`() {
        val service = Robolectric.buildService(AdbPairingService::class.java).get()
        service.state = AdbPairingState()
        service.state.update(PairingPhase.Found)
        org.robolectric.util.ReflectionHelpers.setField(service, "started", true)
        AdbPairingService.ensureChannel(service)
        val token: String = org.robolectric.util.ReflectionHelpers.getField(service, "token")
        val input = android.app.RemoteInput.Builder("pairing_code").build()
        val intent = android.content.Intent().setAction("dsh.adb.pair.REPLY").putExtra("session", "expired-session")
        android.app.RemoteInput.addResultsToIntent(arrayOf(input), intent, android.os.Bundle().apply { putCharSequence("pairing_code", "123") })
        service.onStartCommand(intent, 0, 1)
        assertNull(service.state.status.value.message)
        intent.putExtra("session", token)
        service.onStartCommand(intent, 0, 2)
        assertEquals(io.github.jackwener.dsh.R.string.adb_code_error, service.state.status.value.message)
        assertEquals(PairingPhase.Found, service.state.status.value.phase)
    }

    @Test fun `completed notification removes reply and ongoing state`() {
        val service = Robolectric.buildService(AdbPairingService::class.java).get()
        for (phase in listOf(PairingPhase.Connected, PairingPhase.Failed)) {
            val notification = service.notification(phase)
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(notification.actions.isNullOrEmpty())
        }
    }
    @Test fun `discovery accepts only this phones interfaces`() {
        assertTrue(LocalAdbDiscovery.isLocal(java.net.InetAddress.getByName("127.0.0.1")))
        assertFalse(LocalAdbDiscovery.isLocal(java.net.InetAddress.getByName("192.0.2.125")))
        assertFalse(LocalAdbDiscovery.isLocal(null))
    }
}

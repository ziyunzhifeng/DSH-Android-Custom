package io.github.jackwener.dsh.core.adb

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.feature.settings.LocalAdbActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

/** User-started, time-bounded session. Framework RemoteInput renders in the phone's system UI. */
@AndroidEntryPoint
class AdbPairingService : Service() {
    @Inject lateinit var repository: LocalAdbRepository
    @Inject lateinit var state: AdbPairingState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discovery: LocalAdbDiscovery? = null
    private var operation: Job? = null
    private var deadline: Job? = null
    private var pairingPort: Int? = null
    private val connectionPort = MutableStateFlow<Int?>(null)
    private val token = UUID.randomUUID().toString()
    private var started = false
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!started) begin()
            REPLY -> if (started && intent.getStringExtra(SESSION) == token && state.status.value.phase == PairingPhase.Found) {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()?.trim().orEmpty()
                if (!code.matches(Regex("[0-9]{6}"))) show(PairingPhase.Found, R.string.adb_code_error)
                else pair(code)
            }
            STOP -> if (started && intent.getStringExtra(SESSION) == token) finish(PairingPhase.Stopped)
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        started = true
        ensureChannel(this)
        state.update(PairingPhase.Searching)
        val initial = notification(PairingPhase.Searching)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(ID, initial)
        } catch (_: Exception) { state.update(PairingPhase.Failed, R.string.adb_permission_error); stopSelf(); return }
        deadline = scope.launch { delay(150_000); finish(PairingPhase.Failed, R.string.adb_timeout) }
        discovery = LocalAdbDiscovery(this, found = { pairing, port ->
            if (pairing) {
                pairingPort = port
                if (state.status.value.phase in setOf(PairingPhase.Searching, PairingPhase.Found)) {
                    show(if (port == null) PairingPhase.Searching else PairingPhase.Found)
                }
            } else connectionPort.value = port
        }, failed = { finish(PairingPhase.Failed, R.string.adb_discovery_error) }).also { it.start() }
    }

    private fun pair(code: String) {
        val port = pairingPort ?: return
        show(PairingPhase.Pairing)
        operation = scope.launch {
            var paired = false
            try {
                repository.pair(port, code, connectionPort.value)
                paired = true
                show(PairingPhase.Connecting)
                val connect = withTimeout(20_000) { connectionPort.filterNotNull().first() }
                repository.configure(connect, true, true)
                withTimeout(20_000) { repository.status() }
                finish(PairingPhase.Connected)
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) finish(PairingPhase.Failed, if (paired) R.string.adb_paired_no_connection else R.string.adb_timeout)
                else throw cancelled
            } catch (_: Exception) {
                finish(PairingPhase.Failed, if (paired) R.string.adb_paired_no_connection else R.string.adb_pair_error)
            }
        }
    }

    private fun show(phase: PairingPhase, message: Int? = null) {
        state.update(phase, message)
        notifications.notify(ID, notification(phase, message))
    }
    private fun finish(phase: PairingPhase, message: Int? = null) {
        if (!started) return
        started = false
        state.update(phase, message)
        discovery?.stop(); discovery = null
        deadline?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (phase != PairingPhase.Stopped) notifications.notify(ID, notification(phase, message))
        stopSelf()
    }
    override fun onTimeout(startId: Int) { finish(PairingPhase.Failed, R.string.adb_timeout) }
    override fun onDestroy() {
        discovery?.stop()
        scope.cancel()
        if (state.status.value.active) state.update(PairingPhase.Stopped)
        super.onDestroy()
    }

    internal fun notification(phase: PairingPhase, message: Int? = null): Notification {
        val active = PairingStatus(phase).active
        val open = PendingIntent.getActivity(this, 0, Intent(this, LocalAdbActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (phase) {
            PairingPhase.Found -> R.string.adb_found
            PairingPhase.Pairing -> R.string.adb_pairing
            PairingPhase.Connecting -> R.string.adb_connecting
            PairingPhase.Connected -> R.string.adb_connected
            PairingPhase.Failed -> R.string.adb_failed
            else -> R.string.adb_searching
        }
        val body = message ?: when (phase) {
            PairingPhase.Found -> R.string.adb_found_hint
            PairingPhase.Pairing -> R.string.adb_pairing_hint
            PairingPhase.Connecting -> R.string.adb_connecting_hint
            PairingPhase.Connected -> R.string.adb_connected_hint
            PairingPhase.Failed -> R.string.adb_pair_error
            else -> R.string.adb_search_hint
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(getString(title)).setContentText(getString(body))
            .setStyle(Notification.BigTextStyle().bigText(getString(body)))
            .setContentIntent(open).setOngoing(active).setAutoCancel(!active)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (phase == PairingPhase.Found) {
            val reply = PendingIntent.getService(this, 1, Intent(this, AdbPairingService::class.java).setAction(REPLY).putExtra(SESSION, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(CODE).setLabel(getString(R.string.adb_code)).setAllowFreeFormInput(true)
            if (Build.VERSION.SDK_INT >= 29) input.setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED)
            val action = Notification.Action.Builder(null, getString(R.string.adb_reply), reply)
                .addRemoteInput(input.build()).setAllowGeneratedReplies(false)
            if (Build.VERSION.SDK_INT >= 31) action.setAuthenticationRequired(true)
            builder.addAction(action.build())
        }
        if (active) {
            val stop = PendingIntent.getService(this, 2, Intent(this, AdbPairingService::class.java).setAction(STOP).putExtra(SESSION, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.adb_cancel), stop).build())
        }
        return builder.build()
    }
    companion object {
        const val CHANNEL = "local_adb_pairing"
        const val ID = 7391
        const val START = "dsh.adb.pair.START"
        private const val REPLY = "dsh.adb.pair.REPLY"
        private const val STOP = "dsh.adb.pair.STOP"
        private const val SESSION = "session"
        private const val CODE = "pairing_code"
        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.adb_channel), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
        }
    }
}

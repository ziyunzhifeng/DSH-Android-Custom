package io.github.jackwener.dsh.core.wechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.feature.settings.WeChatCollectionActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** ADB and the tool bridge live in the main process, not in EngineService's :engine process. */
class WeChatScanService : Service() {
    private var startupError: Exception? = null
    internal inner class ReadyBinder : Binder() {
        fun requireReady() { startupError?.let { throw it } }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.wechat_scan_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null); enableVibration(false) })
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification())
        } catch (error: Exception) {
            startupError = error
            stopSelf()
        }
    }

    internal fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_dsh)
        .setContentTitle(getString(R.string.wechat_scan_running))
        .setContentText(getString(R.string.wechat_scan_running_hint))
        .setContentIntent(PendingIntent.getActivity(this, ID,
            Intent(this, WeChatCollectionActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    override fun onBind(intent: Intent?): IBinder = ReadyBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "wechat_scan"
        const val ID = 6102
    }
}

/** Await foreground promotion before opening WeChat; release only the owning scan's service. */
@Singleton
class WeChatScanRuntime @Inject constructor(@ApplicationContext private val context: Context) {
    private var owner: String? = null
    private var connection: ServiceConnection? = null

    suspend fun begin(session: String) = withContext(Dispatchers.Main.immediate) {
        check(owner == null) { "已有采集运行服务" }
        val ready = CompletableDeferred<Unit>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    (service as WeChatScanService.ReadyBinder).requireReady()
                    ready.complete(Unit)
                } catch (error: Exception) { ready.completeExceptionally(error) }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ready.completeExceptionally(IllegalStateException("采集运行服务已断开"))
            }
            override fun onNullBinding(name: ComponentName?) {
                ready.completeExceptionally(IllegalStateException("采集运行服务不可用"))
            }
        }
        owner = session
        val intent = Intent(context, WeChatScanService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
            check(context.bindService(intent, binding, Context.BIND_AUTO_CREATE)) { "无法连接采集运行服务" }
            connection = binding
            withTimeout(10_000) { ready.await() }
        } catch (error: Exception) {
            withContext(NonCancellable) { end(session) }
            throw error
        }
    }

    suspend fun end(session: String) = withContext(Dispatchers.Main.immediate) {
        if (owner == session) {
            connection?.let { context.unbindService(it) }
            connection = null
            owner = null
            context.stopService(Intent(context, WeChatScanService::class.java))
        }
    }
}

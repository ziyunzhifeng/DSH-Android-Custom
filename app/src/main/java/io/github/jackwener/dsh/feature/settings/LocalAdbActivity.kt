package io.github.jackwener.dsh.feature.settings

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.adb.*
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.designsystem.*
import javax.inject.Inject

@AndroidEntryPoint
class LocalAdbActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    private val vm: LocalAdbViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startPairing() else showPermissionSettings()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by settings.settings.collectAsStateWithLifecycle(initialValue = null)
            DshTheme(preferences?.theme ?: ThemeMode.System) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val pairing by vm.pairing.status.collectAsStateWithLifecycle()
                LocalAdbScreen(ui, pairing, onBack = { finish() }, onPair = { showPairingGuide() },
                    onStop = { stopService(Intent(this, AdbPairingService::class.java)) },
                    onCheck = { vm.check() }, onRead = vm::read, onCancel = vm::cancel,
                    onDisable = { stopService(Intent(this, AdbPairingService::class.java)); vm.disable() },
                    onManual = vm::check)
            }
        }
    }
    override fun onResume() { super.onResume(); vm.refresh() }
    private fun systemDialog(title: Int, message: Int, positive: Int, action: () -> Unit) {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        AlertDialog.Builder(ContextThemeWrapper(this, theme))
            .setTitle(title).setMessage(message)
            .setNegativeButton(R.string.adb_cancel, null)
            .setPositiveButton(positive) { _, _ -> action() }.show()
    }
    private fun showPairingGuide() {
        if (Build.VERSION.SDK_INT < 30) {
            systemDialog(R.string.adb_title, R.string.adb_old_android, android.R.string.ok) {}; return
        }
        systemDialog(R.string.adb_guide_title, R.string.adb_guide_body, R.string.adb_continue) {
            val missing = buildList {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= 37 && applicationInfo.targetSdkVersion >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) add("android.permission.ACCESS_LOCAL_NETWORK")
            }
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray()) else startPairing()
        }
    }
    private fun startPairing() {
        AdbPairingService.ensureChannel(this)
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(AdbPairingService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            showPermissionSettings(); return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, AdbPairingService::class.java).setAction(AdbPairingService.START))
            try { startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        } catch (_: Exception) { systemDialog(R.string.adb_title, R.string.adb_unavailable, android.R.string.ok) {} }
    }
    private fun showPermissionSettings() {
        systemDialog(R.string.adb_notifications, R.string.adb_notifications_hint, R.string.adb_settings) {
            runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }
}

@Composable
private fun LocalAdbScreen(
    ui: LocalAdbUiState, pairing: PairingStatus,
    onBack: () -> Unit, onPair: () -> Unit, onStop: () -> Unit,
    onCheck: () -> Unit, onRead: () -> Unit, onCancel: () -> Unit, onDisable: () -> Unit,
    onManual: (String, Boolean) -> Unit,
) {
    val c = Dsh.colors
    val context = LocalContext.current
    var advanced by rememberSaveable { mutableStateOf(false) }
    val active = pairing.active
    val busy = active || ui.busy
    val title = when (pairing.phase) {
        PairingPhase.Searching -> R.string.adb_searching
        PairingPhase.Found -> R.string.adb_found
        PairingPhase.Pairing -> R.string.adb_pairing
        PairingPhase.Connecting -> R.string.adb_connecting
        else -> if (ui.connected) R.string.adb_connected else if (ui.enabled) R.string.adb_saved else R.string.adb_disabled
    }
    SettingsPage(stringResource(R.string.adb_title), onBack) {
        Text(stringResource(R.string.adb_intro), Modifier.padding(start = 4.dp, top = 12.dp), style = DshType.xs13, color = c.labelTertiary)
        SettingsGroupTitle(stringResource(R.string.adb_connection))
        SettingsCard {
            SettingsRow(stringResource(title),
                if (ui.enabled && !active) "${if (ui.wireless) "TLS" else "TCP"} · 127.0.0.1:${ui.port}" else stringResource(R.string.adb_local_only), divider = false,
                leading = { if (busy) Spinner() else StateDot(if (ui.connected) DotKind.Done else DotKind.Idle) },
                trailing = { if (!active) DButton(stringResource(R.string.adb_check), onCheck, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = ui.enabled && !ui.busy) })
        }
        val feedback = if (active || pairing.phase == PairingPhase.Failed) pairing.message ?: when (pairing.phase) {
            PairingPhase.Searching -> R.string.adb_search_hint
            PairingPhase.Found -> R.string.adb_found_hint
            PairingPhase.Pairing -> R.string.adb_pairing_hint
            PairingPhase.Connecting -> R.string.adb_connecting_hint
            else -> R.string.adb_pair_error
        } else ui.message
        feedback?.let { Text(stringResource(it), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xs13, color = if (pairing.phase == PairingPhase.Failed) c.error else c.labelTertiary) }
        if (ui.busy) DButton(stringResource(R.string.adb_cancel), onCancel, Modifier.padding(top = 8.dp), variant = BtnVariant.Ghost, size = BtnSize.Sm)

        SettingsGroupTitle(stringResource(R.string.adb_pair_section))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (ui.paired && !active) R.string.adb_paired_success else R.string.adb_pair_help), style = DshType.s14, color = c.labelPrimary)
                if (!ui.paired || active) Text(stringResource(R.string.adb_steps), style = DshType.xs13, color = c.labelTertiary)
                DButton(stringResource(if (active) R.string.adb_stop else if (ui.paired) R.string.adb_repair else R.string.adb_pair_start), if (active) onStop else onPair, Modifier.fillMaxWidth(),
                    variant = if (active) BtnVariant.Outline else BtnVariant.Primary, enabled = !ui.busy)
            }
        }
        SettingsGroupTitle(stringResource(R.string.adb_wechat))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.adb_wechat_hint), style = DshType.xs13, color = c.labelTertiary)
                DButton("公众号采集与热榜", { context.startActivity(Intent(context, WeChatCollectionActivity::class.java)) }, Modifier.fillMaxWidth(), enabled = ui.enabled && !busy)
                DButton(stringResource(R.string.adb_read), onRead, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, enabled = ui.enabled && !busy)
            }
            ui.article?.let { article ->
                SettingsRow(article.title, article.account, divider = true)
                SettingsRow(stringResource(R.string.adb_read_count), article.reads ?: stringResource(R.string.adb_missing))
            }
        }
        SettingsGroupTitle(stringResource(R.string.adb_advanced))
        SettingsCard {
            SettingsRow(stringResource(R.string.adb_manual), stringResource(R.string.adb_manual_hint), onClick = { if (!busy) advanced = true }, divider = false,
                trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
            if (ui.enabled) SettingsRow(stringResource(R.string.adb_disable), stringResource(R.string.adb_disable_hint), onClick = onDisable, titleColor = c.error)
        }
    }
    if (advanced) {
        var port by rememberSaveable { mutableStateOf(ui.port.toString()) }
        var wireless by rememberSaveable { mutableStateOf(ui.wireless) }
        var invalid by remember { mutableStateOf(false) }
        DDialog({ advanced = false }, stringResource(R.string.adb_manual), buttons = {
            DButton(stringResource(R.string.adb_cancel), { advanced = false }, variant = BtnVariant.Outline)
            DButton(stringResource(R.string.adb_save), {
                val number = port.toIntOrNull()
                if (number == null || number !in 1..65535 || number == 15555) invalid = true
                else { advanced = false; onManual(port, wireless) }
            })
        }) {
            SettingsRow(stringResource(R.string.adb_wireless), divider = false, trailing = { DSwitch(wireless, { wireless = it }) })
            if (!wireless) Text(stringResource(R.string.adb_tcp), style = DshType.xs13, color = c.labelTertiary)
            Text(stringResource(R.string.adb_port), style = DshType.s14, color = c.labelSecondary)
            DInput(port, { port = it.filter(Char::isDigit).take(5); invalid = false }, keyboardType = KeyboardType.Number)
            if (invalid) Text(stringResource(R.string.adb_port_error), style = DshType.xs13, color = c.error)
        }
    }
}

package dev.dquest.aichanger

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.dquest.aichanger.daemon.DaemonProtocol
import dev.dquest.aichanger.ui.AIChangerTheme
import dev.dquest.aichanger.ui.AssistantItem
import dev.dquest.aichanger.ui.HomeScreen
import dev.dquest.aichanger.ui.SetupWizard
import dev.dquest.aichanger.ui.UiActions
import dev.dquest.aichanger.ui.UiState

class MainActivity : ComponentActivity(), UiActions {

    // Контроллер не наблюдаемый: экран перечитывает его, когда меняется этот счётчик.
    private var tick by mutableIntStateOf(0)
    private var assistants by mutableStateOf(emptyList<AssistantItem>())
    private val onChange: () -> Unit = { runOnUiThread { tick++ } }
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            PowerKeyController.refreshAsync()
            tick++
            handler.postDelayed(this, POLL_MS)
        }
    }
    // Код сопряжения вводится в уведомлении, без разрешения на них ничего не выйдет.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startPairing() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIChangerTheme {
                val state = remember(tick) { UiState.capture(this) }
                if (!state.paired || !state.setupDone) SetupWizard(state, this) else HomeScreen(state, assistants, this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PowerKeyController.addListener(onChange)
        // Ассистентов могли установить или удалить, пока экран был закрыт.
        assistants = loadAssistants()
        PowerKeyController.startIfNeeded(applicationContext)
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        PowerKeyController.removeListener(onChange)
        super.onStop()
    }

    private fun loadAssistants(): List<AssistantItem> = Assistants.find(this).map { assistant ->
        // У Gemini своё приложение, а вызывается он через Google: показываем знакомую иконку Gemini.
        val iconPackage = if (assistant == Assistants.GEMINI && isInstalled(GEMINI_APP)) GEMINI_APP
        else DaemonProtocol.assistantPackage(assistant.target)
        val icon = runCatching { packageManager.getApplicationIcon(iconPackage).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
        AssistantItem(assistant, icon.getOrNull())
    }

    private fun isInstalled(packageName: String) =
        runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    override fun openDeviceInfo() {
        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }

    override fun startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // После сопряжения PairingService сам запустит перехват, если он включён.
        PowerKeyController.setEnabled(true)
        startForegroundService(Intent(this, PairingService::class.java))
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        )
    }

    /** Без разрешения на автозапуск vivo отбрасывает загрузочную рассылку, и демон не стартует после перезагрузки. */
    override fun openAutoStart() {
        val vivo = Intent().setComponent(ComponentName(VIVO_PERMISSION_MANAGER, VIVO_AUTOSTART_ACTIVITY))
        try {
            startActivity(vivo)
        } catch (_: ActivityNotFoundException) {
            openAppDetails()
        } catch (_: SecurityException) {
            openAppDetails()
        }
    }

    private fun openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    override fun finishSetup() {
        PowerKeyController.setSetupDone(true)
        enable()
    }

    /** Заново с сопряжения: оно же нужно, если сменился ключ или сеть. Работающий перехват не останавливаем. */
    override fun restartSetup() {
        PowerKeyController.setSetupDone(false)
        PowerKeyController.setPaired(false)
    }

    override fun enable() {
        PowerKeyController.setEnabled(true)
        AutoStartService.start(this)
    }

    override fun disable() = PowerKeyController.disable(this)

    override fun selectAssistant(target: String) = PowerKeyController.setAssistant(target)

    override fun setVoice(voice: Boolean) = PowerKeyController.setVoiceMode(voice)

    private companion object {
        const val POLL_MS = 2000L
        const val ICON_PX = 96
        const val GEMINI_APP = "com.google.android.apps.bard"
        const val VIVO_PERMISSION_MANAGER = "com.vivo.permissionmanager"
        const val VIVO_AUTOSTART_ACTIVITY = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
    }
}

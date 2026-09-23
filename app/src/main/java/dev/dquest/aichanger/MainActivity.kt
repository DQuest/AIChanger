package dev.dquest.aichanger

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
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
    // Код сопряжения вводится в уведомлении, без разрешения на них ничего не выйдет. После второго отказа система
    // больше не спрашивает и сразу отвечает «нет»: тогда разрешить можно только в настройках.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPairing() else openNotificationSettings()
        }

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

    override fun openWifi() = openFirst(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    override fun openDeviceInfo() = openFirst(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    override fun startPairing() {
        // Разрешение появилось в Android 13; на 11–12 запрос сразу вернул бы «нет».
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Разрешение есть, но уведомления приложения выключены в настройках: уведомление с полем кода не появится.
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            openNotificationSettings()
            return
        }
        // После сопряжения PairingService сам запустит перехват, если он включён.
        PowerKeyController.setEnabled(true)
        startForegroundService(Intent(this, PairingService::class.java))
        openFirst(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    private fun openNotificationSettings() = openFirst(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        appDetailsIntent(),
    )

    /** Без разрешения на автозапуск vivo отбрасывает загрузочную рассылку, и демон не стартует после перезагрузки. */
    override fun openAutoStart() = openVivoSettings(VIVO_PERMISSION_MANAGER, VIVO_AUTOSTART_ACTIVITY)

    /**
     * vivo замораживает фоновые приложения через секунды после ухода с экрана, даже с foreground-службой, и тогда
     * AIChanger не узнаёт, что появился Wi-Fi. Не замораживает тех, кому разрешено высокое энергопотребление в фоне.
     */
    override fun openBackgroundPower() = openVivoSettings(VIVO_POWER_SAVING, VIVO_BACKGROUND_POWER_ACTIVITY)

    private fun openVivoSettings(packageName: String, activity: String) =
        openFirst(Intent().setComponent(ComponentName(packageName, activity)), appDetailsIntent())

    private fun appDetailsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /** Набор экранов настроек у прошивок разный: если нужного нет или он закрыт, открываем следующий, а не падаем. */
    private fun openFirst(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
    }

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
        const val VIVO_POWER_SAVING = "com.iqoo.powersaving"
        const val VIVO_BACKGROUND_POWER_ACTIVITY = "com.iqoo.powersaving.BackgroundHighUsageActivity"
    }
}

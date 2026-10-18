package dev.dquest.aichanger.ui

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.dquest.aichanger.Assistant
import dev.dquest.aichanger.BuildConfig
import dev.dquest.aichanger.PowerKeyController
import dev.dquest.aichanger.SetupStep
import dev.dquest.aichanger.Wifi

/** Снимок состояния для экрана: [PowerKeyController] не наблюдаемый, поэтому экран перечитывает его по сигналу. */
@Immutable
data class UiState(
    val enabled: Boolean,
    val paired: Boolean,
    /** Прежнее сопряжение перестало действовать, мастер просит его снова. */
    val pairingLost: Boolean,
    val setupDone: Boolean,
    val setupSteps: Set<SetupStep>,
    val developerOptions: Boolean,
    val running: Boolean,
    val launching: Boolean,
    val waitingForWifi: Boolean,
    /** Ждём другую сеть Wi-Fi: в этой беспроводная отладка не разрешена. */
    val untrustedWifi: Boolean,
    val wifiEnabled: Boolean,
    /** Код сопряжения вводится в уведомлении. */
    val notificationsEnabled: Boolean,
    val error: String?,
    /** Перехват выключен, но прежнего системного ассистента вернуть не удалось. */
    val stopError: String?,
    /** Демон работает, но кнопку не перехватывает. */
    val problem: String?,
    val assistant: String,
    val changedSystemAssistant: Boolean,
    val voice: Boolean,
    val details: String?,
) {
    companion object {
        fun capture(context: Context): UiState {
            val version = PowerKeyController.daemonVersion
            return UiState(
                enabled = PowerKeyController.isEnabled,
                // Пока настраивают заново, мастер начинается с сопряжения, хотя прежнее ещё действует.
                paired = PowerKeyController.isPaired && !PowerKeyController.isRepairing,
                pairingLost = PowerKeyController.isPairingLost,
                setupDone = PowerKeyController.isSetupDone,
                setupSteps = PowerKeyController.completedSetupSteps,
                developerOptions = Settings.Global.getInt(
                    context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
                ) == 1,
                running = version == BuildConfig.VERSION_CODE,
                // Демон прежней версии сейчас заменят новым: для пользователя это тот же запуск. Но если замена уже
                // не удалась, показываем ошибку: иначе «Запускается…» висело бы вечно, пока отвечает старый демон.
                launching = PowerKeyController.launching ||
                    (version != null && version != BuildConfig.VERSION_CODE && PowerKeyController.lastLaunchError == null),
                waitingForWifi = PowerKeyController.waitingForWifi,
                untrustedWifi = PowerKeyController.untrustedWifi,
                wifiEnabled = Wifi.isEnabled(context),
                notificationsEnabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                error = PowerKeyController.lastLaunchError,
                stopError = PowerKeyController.lastStopError.takeIf { !PowerKeyController.isEnabled },
                problem = PowerKeyController.daemonProblem,
                assistant = PowerKeyController.assistant,
                changedSystemAssistant = PowerKeyController.changedSystemAssistant,
                voice = PowerKeyController.isVoiceMode,
                details = PowerKeyController.daemonStatus,
            )
        }
    }
}

@Immutable
data class AssistantItem(val assistant: Assistant, val icon: ImageBitmap?)

/** Что экран просит сделать; реализует MainActivity. */
interface UiActions {
    fun openDeviceInfo()
    fun startPairing()
    fun openAutoStart()
    fun openBackgroundPower()
    fun openWifi()
    fun completeSetupStep(step: SetupStep)
    fun finishSetup()
    fun restartSetup()
    fun enable()
    fun disable()
    fun selectAssistant(target: String)
    fun setVoice(voice: Boolean)
}

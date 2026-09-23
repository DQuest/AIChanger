package dev.dquest.aichanger.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.dquest.aichanger.Assistant
import dev.dquest.aichanger.BuildConfig
import dev.dquest.aichanger.PowerKeyController
import dev.dquest.aichanger.Wifi

/** Снимок состояния для экрана: [PowerKeyController] не наблюдаемый, поэтому экран перечитывает его по сигналу. */
@Immutable
data class UiState(
    val enabled: Boolean,
    val paired: Boolean,
    val setupDone: Boolean,
    val developerOptions: Boolean,
    val running: Boolean,
    val launching: Boolean,
    val waitingForWifi: Boolean,
    val wifiEnabled: Boolean,
    val error: String?,
    /** Демон работает, но кнопку не перехватывает. */
    val problem: String?,
    val assistant: String,
    val voice: Boolean,
    val details: String?,
) {
    companion object {
        fun capture(context: Context): UiState {
            val version = PowerKeyController.daemonVersion
            return UiState(
                enabled = PowerKeyController.isEnabled,
                paired = PowerKeyController.isPaired,
                setupDone = PowerKeyController.isSetupDone,
                developerOptions = Settings.Global.getInt(
                    context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
                ) == 1,
                running = version == BuildConfig.VERSION_CODE,
                // Демон прежней версии сейчас заменят новым: для пользователя это тот же запуск.
                launching = PowerKeyController.launching || (version != null && version != BuildConfig.VERSION_CODE),
                waitingForWifi = PowerKeyController.waitingForWifi,
                wifiEnabled = Wifi.isEnabled(context),
                error = PowerKeyController.lastLaunchError,
                problem = PowerKeyController.daemonProblem,
                assistant = PowerKeyController.assistant,
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
    fun openSettings()
    fun openDeviceInfo()
    fun startPairing()
    fun openAutoStart()
    fun openBackgroundPower()
    fun openWifi()
    fun finishSetup()
    fun restartSetup()
    fun enable()
    fun disable()
    fun selectAssistant(target: String)
    fun setVoice(voice: Boolean)
}

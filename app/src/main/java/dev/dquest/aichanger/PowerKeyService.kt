package dev.dquest.aichanger

import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService: работает в отдельном процессе с uid shell.
 *
 * Читает сырые события кнопки питания (`pmic_pwrkey`) из /dev/input. При удержании
 * дольше порога закрывает меню питания vivo и отправляет KEYCODE_VOICE_ASSIST или KEYCODE_ASSIST,
 * которые PhoneWindowManager передаёт системному ассистенту (Gemini): сразу с голосовым вводом или без.
 */
class PowerKeyService : IPowerKeyService.Stub() {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingFire: ScheduledFuture<*>? = null

    @Volatile private var holdMs = Config.DEFAULT_HOLD_MS
    @Volatile private var voiceMode = true
    @Volatile private var active = false
    @Volatile private var reader: Thread? = null
    @Volatile private var device: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var fired = 0

    override fun start(holdMs: Int) {
        synchronized(this) {
            this.holdMs = holdMs
            active = true
            if (reader?.isAlive == true) return
            reader = thread(name = "power-key-reader") { readLoop() }
        }
    }

    override fun stop() {
        synchronized(this) {
            // Поток чтения заметит флаг на следующем событии; при удалении службы процесс завершается целиком.
            active = false
            pendingFire?.cancel(false)
        }
    }

    override fun isRunning() = active && reader?.isAlive == true

    override fun status(): String = buildString {
        append("Устройство: ${device ?: "—"}\n")
        append("Порог удержания: $holdMs мс\n")
        append("Режим: ${if (voiceMode) "голосовой ввод" else "окно без прослушивания"}\n")
        append("Вызовов Gemini: $fired\n")
        append("BlueLM на кнопке: ${if (sh(GET_BLUELM_SWITCH).trim() == "1") "включён" else "выключен"}")
        lastError?.let { append("\nОшибка: $it") }
    }

    override fun setBlueLmOnPowerKey(enabled: Boolean) {
        // vivo молча игнорирует `settings put` для этих ключей, а запись через провайдер работает.
        sh(
            "content insert --uri content://settings/system " +
                "--bind name:s:$BLUELM_SWITCH --bind value:s:${if (enabled) 1 else 0}"
        )
    }

    override fun setVoiceMode(voice: Boolean) {
        voiceMode = voice
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    private fun readLoop() {
        setBlueLmOnPowerKey(false)
        while (active) {
            try {
                val dev = findPowerDevice() ?: error("устройство pmic_pwrkey не найдено")
                device = dev
                lastError = null
                DataInputStream(FileInputStream(dev)).use { input ->
                    val event = ByteArray(INPUT_EVENT_SIZE)
                    val fields = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
                    while (active) {
                        input.readFully(event)
                        // struct input_event на arm64: timeval (16 байт), type u16, code u16, value s32.
                        if (fields.getShort(16).toInt() == EV_KEY && fields.getShort(18).toInt() == KEY_POWER) {
                            onPowerKey(fields.getInt(20))
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
            }
            if (active) Thread.sleep(RESTART_DELAY_MS)
        }
    }

    private fun onPowerKey(value: Int) = synchronized(this) {
        when (value) {
            KEY_DOWN -> {
                pendingFire?.cancel(false)
                pendingFire = scheduler.schedule(::invokeAssistant, holdMs.toLong(), TimeUnit.MILLISECONDS)
            }
            KEY_UP -> pendingFire?.cancel(false)
        }
    }

    private fun invokeAssistant() {
        // Сначала закрываем меню питания: CLOSE_SYSTEM_DIALOGS, отправленный после, спрятал бы и Gemini.
        sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null")
        // Экран блокировки vivo не пропускает панель Gemini (остаётся только рамка),
        // поэтому сначала просим разблокировку и открываем Gemini уже после неё.
        if (isKeyguardShowing()) {
            // Любая другая клавиша, нажатая пока питание зажато, помечает нажатие питания обработанным.
            // Без этого отпускание кнопки сработает как короткое нажатие и погасит экран.
            // На разблокированном экране эту роль играет сама клавиша вызова ассистента.
            sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
            if (!waitForUnlock()) return
            Thread.sleep(UNLOCK_SETTLE_MS)
        }
        // Сразу после первой разблокировки Google-приложение ещё поднимается, и панель Gemini
        // может закрыться сама (мелькнёт только рамка). Тогда вызываем ещё раз.
        repeat(ASSIST_ATTEMPTS) {
            sh("input keyevent ${if (voiceMode) "KEYCODE_VOICE_ASSIST" else "KEYCODE_ASSIST"}")
            if (waitForGemini()) {
                fired++
                return
            }
        }
    }

    private fun waitForGemini(): Boolean {
        val deadline = System.currentTimeMillis() + GEMINI_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(UNLOCK_POLL_MS)
            if (sh("dumpsys activity activities | grep -m1 topResumedActivity").contains(GEMINI_ACTIVITY)) return true
        }
        return false
    }

    private fun isKeyguardShowing() = sh("dumpsys window | grep -m1 isKeyguardShowing").contains("=true")

    private fun waitForUnlock(): Boolean {
        val deadline = System.currentTimeMillis() + UNLOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(UNLOCK_POLL_MS)
            if (!isKeyguardShowing()) return true
        }
        return false
    }

    /** Номер /dev/input/eventN меняется между загрузками, поэтому ищем по имени. */
    private fun findPowerDevice(): String? {
        var current: String? = null
        for (line in sh("getevent -pl").lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("add device")) {
                current = trimmed.substringAfter(": ").trim()
            } else if (trimmed.startsWith("name:") && trimmed.contains("\"$POWER_DEVICE_NAME\"")) {
                return current
            }
        }
        return null
    }

    private fun sh(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private companion object {
        const val POWER_DEVICE_NAME = "pmic_pwrkey"
        const val BLUELM_SWITCH = "vivo_jovi_power_wakeup_switch"
        const val GET_BLUELM_SWITCH = "settings get system $BLUELM_SWITCH"
        const val INPUT_EVENT_SIZE = 24
        const val EV_KEY = 1
        const val KEY_POWER = 116
        const val KEY_UP = 0
        const val KEY_DOWN = 1
        const val RESTART_DELAY_MS = 2000L
        const val UNLOCK_TIMEOUT_MS = 15_000L
        const val UNLOCK_POLL_MS = 150L
        const val UNLOCK_SETTLE_MS = 300L
        const val ASSIST_ATTEMPTS = 3
        const val GEMINI_WAIT_MS = 1500L
        const val GEMINI_ACTIVITY = "googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
    }
}

package dev.dquest.aichanger.daemon

import android.util.Log
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Логика кнопки питания. Работает в процессе [PowerKeyDaemon] с uid shell.
 *
 * Читает сырые события кнопки питания из /dev/input. При удержании дольше порога
 * вызывает выбранного ассистента ([assistant]): для системного ассистента (Gemini, ChatGPT) отправляет
 * KEYCODE_VOICE_ASSIST или KEYCODE_ASSIST, которые PhoneWindowManager передаёт ему, остальных (Claude,
 * Perplexity, Алиса) запускает напрямую через их activity режима ассистента.
 * Если держать дольше [powerMenuMs], закрывает ассистента и открывает штатное меню питания vivo:
 * своё меню vivo уже не покажет, потому что нажатие питания помечено обработанным.
 * Кнопки громкости тоже читает: питание вместе с громкостью — это сочетание (скриншот и т. п.), его обрабатывает система.
 */
class PowerKeyEngine(private val holdMs: Int, private val powerMenuMs: Int, @Volatile var voiceMode: Boolean) {

    // Вызов ассистента может ждать разблокировки до 15 с, а меню ждать его не должно, даже если вызовы
    // от прошлых нажатий ещё не закончились: у них разные потоки.
    private val assistantScheduler = Executors.newSingleThreadScheduledExecutor()
    private val menuScheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingFire: ScheduledFuture<*>? = null
    private var pendingMenu: ScheduledFuture<*>? = null
    // Номер текущего нажатия и нажатий, для которых ассистент уже не нужен: открыто меню или это сочетание клавиш.
    @Volatile private var press = 0
    @Volatile private var menuPress = -1
    @Volatile private var chordPress = -1
    // Под локом this: зажата ли кнопка питания и когда нажаты ещё не отпущенные кнопки громкости (System.nanoTime).
    private var powerDown = false
    private val volumeDown = mutableMapOf<Int, Long>()

    @Volatile private var active = false
    @Volatile private var devices: List<String> = emptyList()
    // Почему кнопка питания не перехватывается; null — всё в порядке.
    @Volatile private var lastError: String? = null
    // Последняя ошибка прямого запуска ассистента (`am start`); только для сведений, кнопку она не ломает.
    @Volatile private var launchError: String? = null
    @Volatile private var fired = 0

    /** Кого вызывать, в формате [DaemonProtocol.ASSISTANT]. */
    @Volatile var assistant: String = DaemonProtocol.GOOGLE_APP
        private set
    // Кого вернуть системным ассистентом при выключении, если ради выбранного мы его поменяли.
    @Volatile private var restoreRole: String? = null

    fun start() {
        synchronized(this) {
            if (active) return
            active = true
            thread(name = "input-discovery") { discoverDevices() }
        }
    }

    fun stop() {
        synchronized(this) {
            // Потоки чтения заметят флаг на следующем событии; демон после остановки завершается целиком.
            active = false
            cancelPending()
        }
    }

    /** Почему кнопка питания не перехватывается (для экрана приложения); null — всё в порядке. */
    fun problem(): String? = lastError

    fun status(): String = buildString {
        append("Устройства: ${devices.joinToString().ifEmpty { "—" }}\n")
        append("Порог удержания: $holdMs мс, меню питания: $powerMenuMs мс\n")
        append("Ассистент: $assistant\n")
        if (isGemini(assistant)) append("Режим: ${if (voiceMode) "голосовой ввод" else "окно без прослушивания"}\n")
        append("Вызовов ассистента: $fired")
        lastError?.let { append("\nОшибка: $it") }
        launchError?.let { append("\nОшибка запуска ассистента: $it") }
    }

    /**
     * Прямому запуску роль системного ассистента не нужна, и если мы её меняли, возвращаем прежнего.
     * Остальных делаем системным ассистентом и запоминаем, кто им был. Возвращает, кого вернуть при выключении.
     */
    @Synchronized
    fun setAssistant(target: String, restore: String?): String? {
        // Приложение хранит [restore] между запусками демона, но его значение может отстать от нашего: ответ
        // на прошлую команду потерялся по таймауту или два вызова разминулись. Своё, если оно есть, важнее.
        if (restoreRole == null) restoreRole = restore
        if (DaemonProtocol.isDirect(target)) {
            restoreAssistantRole()
        } else {
            val holder = sh(DaemonProtocol.GET_ASSISTANT_ROLE).lineSequence().firstOrNull()?.trim().orEmpty()
            if (holder != target) {
                if (restoreRole == null) {
                    restoreRole = holder.ifEmpty { DaemonProtocol.NO_ASSISTANT }.takeIf(DaemonProtocol::isValidRestore)
                }
                exec(DaemonProtocol.assistantRoleArgs(target))
            }
            if (restoreRole == target) restoreRole = null
        }
        assistant = target
        return restoreRole
    }

    @Synchronized
    fun restoreAssistantRole() {
        val previous = restoreRole ?: return
        exec(DaemonProtocol.assistantRoleArgs(previous))
        restoreRole = null
    }

    /**
     * Номер /dev/input/eventN меняется между загрузками, а имя устройства кнопки питания — между платформами
     * (Qualcomm, MediaTek), поэтому сначала ищем по известным именам, а если их нет — любое настоящее
     * (не uinput) устройство с KEY_POWER. Сразу после загрузки устройств может ещё не быть: ищем, пока не найдём.
     */
    private fun discoverDevices() {
        while (active) {
            val all = try {
                listInputDevices()
            } catch (e: Exception) {
                Log.w(TAG, "daemon: getevent failed: $e")
                emptyList()
            }
            val withPower = all.filter { KEY_POWER_LABEL in it.keys }
            val power = withPower.filter { it.name in POWER_DEVICE_NAMES }.ifEmpty { withPower.filter { !it.virtual } }
            if (power.isEmpty()) {
                lastError = "кнопка питания не найдена среди устройств ввода"
                Thread.sleep(RESTART_DELAY_MS)
                continue
            }
            val volume = all.filter { !it.virtual && it !in power && it.keys.any { key -> key in VOLUME_LABELS } }
            devices = (power + volume).map { "${it.path} (${it.name})" }
            lastError = null
            for (device in power) thread(name = "power-key-reader") { readDevice(device.path, power = true) }
            for (device in volume) thread(name = "volume-key-reader") { readDevice(device.path, power = false) }
            return
        }
    }

    private fun readDevice(path: String, power: Boolean) {
        while (active) {
            try {
                DataInputStream(FileInputStream(path)).use { input ->
                    val event = ByteArray(INPUT_EVENT_SIZE)
                    val fields = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
                    if (power) lastError = null
                    while (active) {
                        input.readFully(event)
                        // struct input_event на arm64: timeval (16 байт), type u16, code u16, value s32.
                        if (fields.getShort(16).toInt() != EV_KEY) continue
                        val code = fields.getShort(18).toInt() and 0xffff
                        // KEY_POWER берём только с устройства кнопки питания, чтобы не получить одно нажатие дважды.
                        if (code == KEY_POWER && !power) continue
                        onKey(code, fields.getInt(20))
                    }
                }
            } catch (e: Exception) {
                if (power) lastError = "не читается кнопка питания ($path): ${e.message}"
                Log.w(TAG, "daemon: reading $path failed: $e")
            }
            // Пропущенное отпускание громкости не должно навсегда считаться зажатой клавишей.
            if (!power) synchronized(this) { volumeDown.clear() }
            if (active) Thread.sleep(RESTART_DELAY_MS)
        }
    }

    private fun onKey(code: Int, value: Int) {
        synchronized(this) {
            when (code) {
                KEY_POWER -> when (value) {
                    KEY_DOWN -> onPowerDown()
                    KEY_UP -> {
                        powerDown = false
                        cancelPending()
                    }
                }
                KEY_VOLUMEDOWN, KEY_VOLUMEUP -> when (value) {
                    KEY_DOWN -> {
                        volumeDown[code] = System.nanoTime()
                        // Громкость нажали, пока держат питание: это сочетание, его обработает система.
                        if (powerDown) {
                            chordPress = press
                            cancelPending()
                        }
                    }
                    KEY_UP -> volumeDown.remove(code)
                }
            }
        }
    }

    /** Вызывается под локом this. */
    private fun onPowerDown() {
        cancelPending()
        powerDown = true
        val id = ++press
        // Громкость зажали чуть раньше питания: тоже сочетание. Давно зажатую не считаем, её отпускание могли пропустить.
        val now = System.nanoTime()
        if (volumeDown.values.any { now - it < TimeUnit.MILLISECONDS.toNanos(CHORD_WINDOW_MS) }) {
            chordPress = id
            return
        }
        pendingFire = assistantScheduler.schedule({ invokeAssistant(id) }, holdMs.toLong(), TimeUnit.MILLISECONDS)
        pendingMenu = menuScheduler.schedule({ showPowerMenu(id) }, powerMenuMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Вызывается под локом this. */
    private fun cancelPending() {
        pendingFire?.cancel(false)
        pendingMenu?.cancel(false)
    }

    /**
     * Ассистент для нажатия [id] уже не нужен: открыто меню питания, это сочетание клавиш или кнопку нажали снова.
     * Последнее важно на экране блокировки: вызов ждёт разблокировки до 15 с, и если пользователь передумал и погасил
     * экран, ассистент не должен открыться, когда телефон потом разблокируют.
     */
    private fun cancelled(id: Int) = press != id || menuPress == id || chordPress == id

    private fun showPowerMenu(id: Int) {
        menuPress = id
        // Gemini закрывается вместе с системными диалогами, остальные ассистенты — обычные activity,
        // их закрываем кнопкой «Назад», но только если открыты они, а не приложение пользователя.
        val target = assistant
        if (!isGemini(target) && topActivity().contains(DaemonProtocol.assistantPackage(target) + "/")) {
            sh("input keyevent KEYCODE_BACK")
        }
        // Вызываем меню так же, как его вызвало бы долгое нажатие.
        // Демон этого нажатия не увидит: внедрённые события не проходят через /dev/input.
        sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null; input keyevent --longpress KEYCODE_POWER")
    }

    private fun invokeAssistant(id: Int) {
        val target = assistant
        // Сначала закрываем меню питания: CLOSE_SYSTEM_DIALOGS, отправленный после, спрятал бы и Gemini.
        sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null")
        // Экран блокировки vivo не пропускает панель Gemini (остаётся только рамка), а activity
        // других ассистентов без разблокировки не откроются, поэтому сначала просим разблокировку.
        if (isKeyguardShowing()) {
            // Любая другая клавиша, нажатая пока питание зажато, помечает нажатие питания обработанным.
            // Без этого отпускание кнопки сработает как короткое нажатие и погасит экран.
            // На разблокированном экране эту роль играет клавиша вызова ассистента (см. [launchAssistant]).
            sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
            if (!waitForUnlock(id) || cancelled(id)) return
            Thread.sleep(UNLOCK_SETTLE_MS)
        }
        // Повтор нужен только Gemini (см. ниже). Остальные открываются с первого раза, а повторная клавиша ASSIST
        // или прямой запуск при медленном холодном старте закрыли бы уже открытую панель или открыли её заново.
        if (!isGemini(target)) {
            launchAssistant(target)
            fired++
            return
        }
        // Сразу после первой разблокировки Google-приложение ещё поднимается, и панель Gemini
        // может закрыться сама (мелькнёт только рамка). Тогда вызываем ещё раз.
        repeat(ASSIST_ATTEMPTS) {
            if (cancelled(id)) return
            val before = topActivity()
            launchAssistant(target)
            if (waitForGemini(before, id)) {
                fired++
                return
            }
        }
    }

    private fun launchAssistant(target: String) {
        when {
            // Прямой запуск клавиш не нажимает, поэтому нажатие питания помечаем обработанным через
            // KEYCODE_WAKEUP (на включённом экране ничего не делает), иначе отпускание погасит экран.
            DaemonProtocol.isDirect(target) -> {
                sh("input keyevent KEYCODE_WAKEUP")
                val output = exec(listOf("am", "start", "-a", "android.intent.action.ASSIST", "-n", target))
                // Например, обновление ассистента переименовало activity: видно в сведениях, а приложение
                // подберёт новую при следующем открытии.
                output.lineSequence().firstOrNull { it.startsWith("Error") }?.let { launchError = "$target: $it" }
            }
            // Голосовая клавиша всегда открывает Google, поэтому для других системных ассистентов только ASSIST.
            isGemini(target) && voiceMode -> sh("input keyevent KEYCODE_VOICE_ASSIST")
            else -> sh("input keyevent KEYCODE_ASSIST")
        }
    }

    /** [before] — верхняя activity до вызова. */
    private fun waitForGemini(before: String, id: Int): Boolean {
        val deadline = System.currentTimeMillis() + GEMINI_WAIT_MS
        while (System.currentTimeMillis() < deadline && !cancelled(id)) {
            Thread.sleep(UNLOCK_POLL_MS)
            if (isGeminiOpened(topActivity(), before)) return true
        }
        return false
    }

    /**
     * Панель Gemini узнаём по её activity, а если Google её переименует — по любой activity Google, появившейся
     * после вызова. Иначе каждое удержание слало бы ASSIST трижды, открывая и закрывая панель.
     */
    private fun isGeminiOpened(top: String, before: String) =
        GEMINI_ACTIVITY in top || (GOOGLE_ACTIVITY in top && top != before)

    private fun isGemini(target: String) = target == DaemonProtocol.GOOGLE_APP

    private fun topActivity() = sh("dumpsys activity activities | grep -m1 topResumedActivity")

    private fun isKeyguardShowing() = sh("dumpsys window | grep -m1 isKeyguardShowing").contains("=true")

    private fun waitForUnlock(id: Int): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + UNLOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // Лицо и отпечаток срабатывают за секунду-две: тогда проверяем часто, чтобы ассистент открылся сразу.
            // Дальше (вводят PIN) — реже: каждый полный dumpsys window заметно нагружает system_server.
            val fast = System.currentTimeMillis() - start < FAST_UNLOCK_MS
            Thread.sleep(if (fast) UNLOCK_POLL_MS else SLOW_UNLOCK_POLL_MS)
            if (cancelled(id)) return false
            if (!isKeyguardShowing()) return true
        }
        return false
    }

    /** Устройство из `getevent -pl`: путь, имя, uinput ли это и названия клавиш, которые оно умеет. */
    private class InputDevice(val path: String) {
        var name = ""
        var virtual = false
        val keys = mutableSetOf<String>()
    }

    private fun listInputDevices(): List<InputDevice> {
        val devices = mutableListOf<InputDevice>()
        for (line in sh("getevent -pl").lineSequence()) {
            val trimmed = line.trim()
            val device = devices.lastOrNull()
            when {
                trimmed.startsWith("add device") -> devices += InputDevice(trimmed.substringAfter(": ").trim())
                device == null -> continue
                trimmed.startsWith("name:") -> device.name = trimmed.substringAfter("name:").trim().removeSurrounding("\"")
                trimmed.startsWith("bus:") -> device.virtual = trimmed.substringAfter("bus:").trim() == BUS_VIRTUAL
                // Список клавиш в разделе KEY, может занимать несколько строк.
                else -> KEY_LABEL.findAll(trimmed).mapTo(device.keys) { it.value }
            }
        }
        return devices
    }

    /** Только для постоянных команд: строку разбирает оболочка. */
    private fun sh(command: String) = exec(listOf("sh", "-c", command))

    /** Без оболочки: имена пакетов и классов от приложения передаются отдельными аргументами. */
    private fun exec(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private companion object {
        const val TAG = "AIChanger"
        /** Кнопка питания на Qualcomm (новые и старые ядра) и MediaTek. */
        val POWER_DEVICE_NAMES = setOf("pmic_pwrkey", "qpnp_pon", "mtk-pmic-keys", "mtk-kpd")
        const val KEY_POWER_LABEL = "KEY_POWER"
        val VOLUME_LABELS = setOf("KEY_VOLUMEDOWN", "KEY_VOLUMEUP")
        val KEY_LABEL = Regex("\\bKEY_[A-Z0-9_]+")
        const val BUS_VIRTUAL = "0006"
        const val INPUT_EVENT_SIZE = 24
        const val EV_KEY = 1
        const val KEY_VOLUMEDOWN = 114
        const val KEY_VOLUMEUP = 115
        const val KEY_POWER = 116
        const val KEY_UP = 0
        const val KEY_DOWN = 1
        // Громкость, нажатая раньше питания не более чем на столько, считается частью сочетания.
        const val CHORD_WINDOW_MS = 1000L
        const val RESTART_DELAY_MS = 2000L
        const val UNLOCK_TIMEOUT_MS = 15_000L
        const val UNLOCK_POLL_MS = 150L
        const val FAST_UNLOCK_MS = 2000L
        const val SLOW_UNLOCK_POLL_MS = 500L
        const val UNLOCK_SETTLE_MS = 300L
        const val ASSIST_ATTEMPTS = 3
        const val GEMINI_WAIT_MS = 1500L
        const val GEMINI_ACTIVITY = "googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
        const val GOOGLE_ACTIVITY = DaemonProtocol.GOOGLE_APP + "/"
    }
}

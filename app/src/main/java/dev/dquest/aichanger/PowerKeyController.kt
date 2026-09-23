package dev.dquest.aichanger

import android.content.Context
import dev.dquest.aichanger.daemon.DaemonProtocol
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Сторона приложения: настройки и связь с демоном ([DaemonClient], [DaemonLauncher]). */
object PowerKeyController {

    private const val PREFS = "aichanger"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PAIRED = "paired"
    private const val KEY_VOICE = "voice"
    private const val KEY_TOKEN = "token"
    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_RESTORE_ROLE = "restore_role"
    private const val KEY_SETUP_DONE = "setup_done"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val background = Executors.newSingleThreadExecutor()
    // Хранилище, доступное до первой разблокировки после загрузки (Direct Boot).
    private lateinit var storage: Context

    /** versionCode работающего демона, null — не запущен. Обновляется [refreshAsync]. */
    @Volatile var daemonVersion: Int? = null
        private set
    @Volatile var daemonStatus: String? = null
        private set
    /** Причина последнего неудачного запуска демона, null — запуск не падал или ещё не пробовали. */
    @Volatile var lastLaunchError: String? = null
        private set
    @Volatile var launching = false
        private set
    /** Запуск отложен до подключения к Wi-Fi: его ждёт [AutoStartService] с уведомлением. */
    @Volatile var waitingForWifi = false
        private set

    fun setWaitingForWifi(value: Boolean) {
        waitingForWifi = value
        notifyListeners()
    }

    fun init(context: Context) {
        storage = context.applicationContext.createDeviceProtectedStorageContext()
        // Перенос настроек из версий до 0.5; до первой разблокировки недоступно, тогда пропускаем.
        runCatching { storage.moveSharedPreferencesFrom(context.applicationContext, PREFS) }
    }

    private val prefs get() = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Включён ли перехват. Если да, демон запускается и после перезагрузки. */
    val isEnabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    /** По умолчанию Gemini сразу слушает, как при удержании кнопки на Samsung. */
    val isVoiceMode: Boolean get() = prefs.getBoolean(KEY_VOICE, true)

    val isPaired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    /** Пройден ли мастер первой настройки (последние шаги — настройки vivo — проверить нельзя). */
    val isSetupDone: Boolean get() = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(value: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()
        notifyListeners()
    }

    /** Кого вызывать, в формате [DaemonProtocol.ASSISTANT]. */
    val assistant: String get() = prefs.getString(KEY_ASSISTANT, null) ?: Assistants.GEMINI.target

    /** Кого вернуть системным ассистентом при выключении; хранится, потому что демон после перезагрузки его не знает. */
    private val restoreRole: String?
        get() = prefs.getString(KEY_RESTORE_ROLE, null)?.takeIf(DaemonProtocol::isValidRestore)

    val isDaemonRunning: Boolean get() = daemonVersion != null

    /** Секрет для команд демону, который запущен последним. */
    private val token: String get() = prefs.getString(KEY_TOKEN, null) ?: newToken()

    /**
     * Токен уходит открытым текстом тому, кто слушает порт демона, а пока демона нет, порт может занять любое
     * приложение. Поэтому каждый демон получает новый токен: перехваченный подходит только к уже неработающему.
     */
    private fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
        .also { prefs.edit().putString(KEY_TOKEN, it).commit() }

    fun setPaired(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED, value).apply()
        notifyListeners()
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        notifyListeners()
    }

    fun setVoiceMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, value).apply()
        background.execute {
            DaemonClient.setVoiceMode(token, value)
            refresh()
        }
    }

    fun setAssistant(target: String) {
        prefs.edit().putString(KEY_ASSISTANT, target).apply()
        notifyListeners()
        background.execute {
            applyAssistant()
            refresh()
        }
    }

    /** Передаёт выбор демону и запоминает, кого он велит вернуть системным ассистентом при выключении. */
    private fun applyAssistant() {
        when (val reply = DaemonClient.setAssistant(token, assistant, restoreRole) ?: return) {
            DaemonProtocol.NONE -> prefs.edit().remove(KEY_RESTORE_ROLE).apply()
            // DaemonProtocol.INVALID и прочее не сохраняем: демон выбор не принял, прежнее значение в силе.
            else -> if (DaemonProtocol.isValidRestore(reply)) prefs.edit().putString(KEY_RESTORE_ROLE, reply).apply()
        }
    }

    /**
     * Блокирующий вызов: убеждается, что работает демон этой версии, иначе запускает его заново.
     * Бросает исключение с понятным текстом, если запустить не удалось. Вызовы не пересекаются
     * друг с другом и с остановкой ([stopDaemon]): второй дождётся первого и увидит уже работающий демон.
     */
    @Synchronized
    fun ensureRunning(context: Context) {
        // Пока ждали лок, перехват могли выключить: тогда запускать нечего.
        if (!isEnabled) return
        launching = true
        notifyListeners()
        try {
            if (DaemonClient.ping(token) == BuildConfig.VERSION_CODE) {
                DaemonClient.setVoiceMode(token, isVoiceMode)
            } else {
                check(isPaired) { "сначала выполните сопряжение с беспроводной отладкой" }
                DaemonLauncher.launch(context, isVoiceMode, ::newToken)
            }
            // Демон после запуска вызывает Gemini, а выбранного ассистента узнаёт только отсюда.
            applyAssistant()
            lastLaunchError = null
        } catch (e: Exception) {
            lastLaunchError = e.message
            throw e
        } finally {
            launching = false
            refresh()
        }
    }

    /** Останавливает демон; он сам возвращает прежнего системного ассистента. BlueLM пользователь включает сам. */
    fun disable(context: Context) {
        setEnabled(false)
        val appContext = context.applicationContext
        // Если запуск ждал Wi-Fi, ждать больше нечего: убираем службу вместе с её уведомлением.
        AutoStartService.stop(appContext)
        // Свой поток, а не background: ожидание идущего запуска может занять минуты, а экран должен обновляться.
        thread(name = "daemon-stop") {
            stopDaemon(appContext)
            refresh()
        }
    }

    /**
     * Под тем же локом, что и [ensureRunning]. Остановка службы не прерывает её поток: без лока идущий запуск
     * поднял бы демон уже после выключения, а два adb-сеанса с общим [AdbManager] мешали бы друг другу.
     */
    @Synchronized
    private fun stopDaemon(context: Context) {
        // Пока ждали, перехват снова включили: останавливать нечего.
        if (isEnabled) return
        // Пустой ответ бывает, когда демон закрыл соединение, ничего не сделав (например, все его потоки заняты).
        val restored = DaemonClient.stop(token) == DaemonProtocol.OK ||
            runCatching { DaemonLauncher.restoreSystem(context, restoreRole) }.isSuccess
        if (restored) prefs.edit().remove(KEY_RESTORE_ROLE).apply()
    }

    fun refreshAsync() = background.execute { refresh() }

    /**
     * Вызывается при открытии приложения: если перехват включён, а демон не отвечает или прежней версии,
     * запускает его. Так чинятся неудачный автозапуск (не было Wi-Fi, vivo не разбудила приложение),
     * убитый демон и обновление, после которого vivo отбросила MY_PACKAGE_REPLACED.
     */
    fun startIfNeeded(context: Context) = background.execute {
        refresh()
        if (isEnabled && isPaired && daemonVersion != BuildConfig.VERSION_CODE) AutoStartService.start(context)
    }

    private fun refresh() {
        daemonVersion = DaemonClient.ping(token)
        daemonStatus = if (daemonVersion != null) DaemonClient.status(token) else null
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyListeners() = listeners.forEach { it() }
}

package dev.dquest.aichanger

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Сторона приложения: настройки и связь с демоном ([DaemonClient], [DaemonLauncher]). */
object PowerKeyController {

    private const val PREFS = "aichanger"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PAIRED = "paired"
    private const val KEY_VOICE = "voice"
    private const val KEY_TOKEN = "token"

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

    val isDaemonRunning: Boolean get() = daemonVersion != null

    /** Секрет для команд демону; передаётся ему при запуске. */
    private val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString(KEY_TOKEN, it).apply() }

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

    /**
     * Блокирующий вызов: убеждается, что работает демон этой версии, иначе запускает его заново.
     * Бросает исключение с понятным текстом, если запустить не удалось. Вызовы не пересекаются:
     * второй дождётся первого и увидит уже работающий демон.
     */
    @Synchronized
    fun ensureRunning(context: Context) {
        launching = true
        notifyListeners()
        try {
            if (DaemonClient.ping(token) == BuildConfig.VERSION_CODE) {
                DaemonClient.setVoiceMode(token, isVoiceMode)
            } else {
                check(isPaired) { "сначала выполните сопряжение с беспроводной отладкой" }
                DaemonLauncher.launch(context, token, isVoiceMode)
            }
            lastLaunchError = null
        } catch (e: Exception) {
            lastLaunchError = e.message
            throw e
        } finally {
            launching = false
            refresh()
        }
    }

    /** Останавливает демон; он сам возвращает BlueLM на кнопку питания. */
    fun disable(context: Context) {
        setEnabled(false)
        val appContext = context.applicationContext
        background.execute {
            if (DaemonClient.stop(token) == null) {
                runCatching { DaemonLauncher.restoreBlueLm(appContext) }
            }
            refresh()
        }
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

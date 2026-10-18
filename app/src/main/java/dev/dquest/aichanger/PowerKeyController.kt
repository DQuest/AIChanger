package dev.dquest.aichanger

import android.content.Context
import android.util.Log
import dev.dquest.aichanger.daemon.DaemonProtocol
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Шаги мастера, которые приложение проверить не может и подтверждает пользователь. Хранятся в настройках, а не
 * в состоянии экрана: из настроек телефона возвращаются и по уведомлению, и vivo может выгрузить приложение,
 * а мастер должен продолжиться с того же шага.
 */
enum class SetupStep { BLUELM_OFF, DEVELOPER_OPTIONS, AUTOSTART }

/** Сторона приложения: настройки и связь с демоном ([DaemonClient], [DaemonLauncher]). */
object PowerKeyController {

    private const val TAG = "AIChanger"
    private const val PREFS = "aichanger"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PAIRED = "paired"
    private const val KEY_VOICE = "voice"
    private const val KEY_TOKEN = "token"
    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_RESTORE_ROLE = "restore_role"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_SETUP_STEPS = "setup_steps"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    // Отдельный лок, а не @Synchronized: тот держит ensureRunning на всё время запуска, а выбор ассистента ждать его не должен.
    private val assistantLock = Any()
    private val background = Executors.newSingleThreadExecutor()
    // Хранилище, доступное до первой разблокировки после загрузки (Direct Boot).
    private lateinit var storage: Context

    /** versionCode работающего демона, null — не запущен. Обновляется [refreshAsync]. */
    @Volatile var daemonVersion: Int? = null
        private set
    @Volatile var daemonStatus: String? = null
        private set
    /** Демон этой версии работает, но кнопку не перехватывает (например, не нашёл её); null — всё в порядке. */
    @Volatile var daemonProblem: String? = null
        private set
    /** Причина последнего неудачного запуска демона, null — запуск не падал или ещё не пробовали. */
    @Volatile var lastLaunchError: String? = null
        private set
    @Volatile var launching = false
        private set
    /**
     * Почему при выключении не удалось вернуть прежнего системного ассистента (демона уже не было, а adb без Wi-Fi
     * не подключился); null — вернули или возвращать нечего. Повторяем при открытии приложения ([startIfNeeded]).
     */
    @Volatile var lastStopError: String? = null
        private set
    private val stopping = AtomicBoolean(false)
    /** Запуск отложен до подключения к Wi-Fi: его ждёт [AutoStartService] с уведомлением. */
    @Volatile var waitingForWifi = false
        private set
    /** Wi-Fi подключён, но в этой сети беспроводная отладка не разрешена: [AutoStartService] ждёт другую сеть. */
    @Volatile var untrustedWifi = false
        private set

    fun setWaitingForWifi(value: Boolean, untrusted: Boolean = false) {
        waitingForWifi = value
        untrustedWifi = value && untrusted
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

    /**
     * Пользователь начал настройку заново: мастер показывает сопряжение, пока оно не пройдёт. Сохранённое сопряжение
     * при этом не сбрасываем: ключ продолжает действовать, и если мастер бросить, автозапуск после перезагрузки
     * ([BootReceiver] смотрит на [isPaired]) должен работать.
     */
    @Volatile var isRepairing = false
        private set

    /** Пройден ли мастер первой настройки (последние шаги — настройки vivo — проверить нельзя). */
    val isSetupDone: Boolean get() = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(value: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()
        notifyListeners()
    }

    val completedSetupSteps: Set<SetupStep>
        get() = prefs.getStringSet(KEY_SETUP_STEPS, null).orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> SetupStep.entries.firstOrNull { it.name == name } }

    fun completeSetupStep(step: SetupStep) {
        // Множество из getStringSet менять нельзя, поэтому сохраняем новое.
        val names = completedSetupSteps.mapTo(mutableSetOf()) { it.name } + step.name
        prefs.edit().putStringSet(KEY_SETUP_STEPS, names).apply()
        notifyListeners()
    }

    /** Мастер с первого шага: сбрасываются отметки пользователя, сопряжение — только в мастере ([isRepairing]). Работающий перехват не трогаем. */
    fun restartSetup() {
        isRepairing = true
        prefs.edit().putBoolean(KEY_SETUP_DONE, false).remove(KEY_SETUP_STEPS).apply()
        notifyListeners()
    }

    /** Кого вызывать, в формате [DaemonProtocol.ASSISTANT]. */
    val assistant: String get() = prefs.getString(KEY_ASSISTANT, null) ?: Assistants.GEMINI.target

    /** Кого вернуть системным ассистентом при выключении; хранится, потому что демон после перезагрузки его не знает. */
    private val restoreRole: String?
        get() = prefs.getString(KEY_RESTORE_ROLE, null)?.takeIf(DaemonProtocol::isValidRestore)

    /** Демон поменял системного ассистента ради выбранного и вернёт прежнего при выключении. */
    val changedSystemAssistant: Boolean get() = restoreRole != null

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
        if (value) isRepairing = false
        notifyListeners()
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        // Включённый демон сам вернёт ассистента при следующем выключении: прежняя неудача больше не важна.
        if (value) lastStopError = null
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

    /**
     * Сверяет выбор со списком установленных ассистентов ([Assistants.find]). Для прямого запуска хранится и activity,
     * которую обновление ассистента может переименовать, а сам ассистент могут удалить: тогда кнопка молча перестала бы
     * работать. Берём тот же пакет с новой activity, иначе Gemini или ассистента с прямым запуском, который не меняет
     * системного. Вызывать после разблокировки: до неё список неполный (службы большинства ассистентов не directBootAware).
     */
    fun reconcileAssistant(available: List<Assistant>) {
        val current = assistant
        if (available.any { it.target == current }) return
        val replacement = available.firstOrNull { DaemonProtocol.assistantPackage(it.target) == DaemonProtocol.assistantPackage(current) }
            ?: available.firstOrNull { it == Assistants.GEMINI }
            ?: available.firstOrNull { DaemonProtocol.isDirect(it.target) }
            ?: return
        setAssistant(replacement.target)
    }

    /**
     * Передаёт выбор демону и запоминает, кого он велит вернуть системным ассистентом при выключении.
     * Вызывается и с экрана, и из [ensureRunning]: без лока ответ одного вызова мог затереть ответ другого.
     */
    private fun applyAssistant() {
        synchronized(assistantLock) {
            when (val reply = DaemonClient.setAssistant(token, assistant, restoreRole) ?: return) {
                DaemonProtocol.NONE -> prefs.edit().remove(KEY_RESTORE_ROLE).commit()
                // DaemonProtocol.INVALID и прочее не сохраняем: демон выбор не принял, прежнее значение в силе.
                else -> if (DaemonProtocol.isValidRestore(reply)) prefs.edit().putString(KEY_RESTORE_ROLE, reply).commit()
            }
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
        stopInBackground(appContext)
    }

    /** Свой поток, а не background: ожидание идущего запуска может занять минуты, а экран должен обновляться. */
    private fun stopInBackground(context: Context) {
        // Остановка уже идёт (нажали ещё раз или открыли приложение): вторая ничего не добавит.
        if (!stopping.compareAndSet(false, true)) return
        thread(name = "daemon-stop") {
            try {
                stopDaemon(context)
            } finally {
                stopping.set(false)
            }
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
        val result = if (DaemonClient.stop(token) == DaemonProtocol.OK) Result.success(Unit)
        else runCatching { DaemonLauncher.restoreSystem(context, restoreRole) }
        if (result.isSuccess) {
            prefs.edit().remove(KEY_RESTORE_ROLE).apply()
            lastStopError = null
        } else if (restoreRole != null) {
            // Системным остался ассистент, выбранный в AIChanger: говорим об этом, а не молчим.
            lastStopError = result.exceptionOrNull()?.message ?: "неизвестная ошибка"
        }
    }

    private val refreshQueued = AtomicBoolean(false)

    /** Опрос экрана раз в 2 с не должен копить очередь, пока поток занят долгим запросом к демону. */
    fun refreshAsync() {
        if (!refreshQueued.compareAndSet(false, true)) return
        background.execute {
            refreshQueued.set(false)
            refresh()
        }
    }

    /**
     * Вызывается при открытии приложения: если перехват включён, а демон не отвечает или прежней версии,
     * запускает его. Так чинятся неудачный автозапуск (не было Wi-Fi, vivo не разбудила приложение),
     * убитый демон и обновление, после которого vivo отбросила MY_PACKAGE_REPLACED.
     * Если перехват выключен, но прежнего системного ассистента вернуть не удалось, повторяет возврат.
     */
    fun startIfNeeded(context: Context) = background.execute {
        refresh()
        // Перехват выключен, а прежнего системного ассистента вернуть не удалось: пробуем снова.
        if (!isEnabled && isPaired && restoreRole != null) stopInBackground(context.applicationContext)
        if (isEnabled && isPaired && daemonVersion != BuildConfig.VERSION_CODE) {
            try {
                AutoStartService.start(context)
            } catch (e: IllegalStateException) {
                // Пока опрашивали демон, приложение ушло в фон, и Android не даёт запустить foreground-службу
                // (ForegroundServiceStartNotAllowedException). Без перехвата упало бы приложение; запустим при следующем открытии.
                Log.w(TAG, "autostart from app not allowed: $e")
            }
        }
    }

    private fun refresh() {
        daemonVersion = DaemonClient.ping(token)
        daemonStatus = if (daemonVersion != null) DaemonClient.status(token) else null
        daemonProblem = if (daemonVersion == BuildConfig.VERSION_CODE) DaemonClient.problem(token)?.ifEmpty { null } else null
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyListeners() = listeners.forEach { it() }
}

package dev.dquest.aichanger

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

object Config {
    const val DEFAULT_HOLD_MS = 600
}

/** Сторона приложения: хранит флаг «включено» и держит связь с [PowerKeyService] через Shizuku. */
object PowerKeyController {

    private const val TAG = "AIChanger"
    private const val PREFS = "aichanger"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_PAIRED = "paired"
    private const val KEY_VOICE = "voice"

    @Volatile var service: IPowerKeyService? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    // Хранилище, доступное до первой разблокировки после загрузки (Direct Boot).
    private lateinit var storage: Context

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, PowerKeyService::class.java.name))
            .daemon(true) // служба переживает закрытие приложения
            .processNameSuffix("powerkey")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IPowerKeyService.Stub.asInterface(binder)
            service = connected
            if (isEnabled) runCatching { configure(connected) }.onFailure { Log.w(TAG, it) }
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            notifyListeners()
        }
    }

    fun init(context: Context) {
        storage = context.applicationContext.createDeviceProtectedStorageContext()
        // Перенос настроек из версий до 0.5; до первой разблокировки недоступно, тогда пропускаем.
        runCatching { storage.moveSharedPreferencesFrom(context.applicationContext, PREFS) }
        // Приходит и при запуске приложения, и когда позже запускается сам Shizuku.
        Shizuku.addBinderReceivedListenerSticky {
            if (isEnabled && hasPermission()) bind()
            notifyListeners()
        }
        Shizuku.addBinderDeadListener {
            service = null
            notifyListeners()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED && isEnabled) bind()
            notifyListeners()
        }
    }

    private val prefs get() = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isEnabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    private fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    val isAutoStartEnabled: Boolean get() = prefs.getBoolean(KEY_AUTOSTART, false)

    fun setAutoStartEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()
        notifyListeners()
    }

    /** По умолчанию Gemini сразу слушает, как при удержании кнопки на Samsung. */
    val isVoiceMode: Boolean get() = prefs.getBoolean(KEY_VOICE, true)

    fun setVoiceMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, value).apply()
        service?.let { runCatching { it.setVoiceMode(value) }.onFailure { e -> Log.w(TAG, e) } }
        notifyListeners()
    }

    val isPaired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    fun setPaired(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED, value).apply()
        notifyListeners()
    }

    fun isShizukuRunning() = Shizuku.pingBinder()

    fun hasPermission() = isShizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission() = Shizuku.requestPermission(0)

    fun enable() {
        setEnabled(true)
        val current = service
        if (current != null) configure(current) else bind()
        notifyListeners()
    }

    private fun configure(service: IPowerKeyService) {
        service.setVoiceMode(isVoiceMode)
        service.start(Config.DEFAULT_HOLD_MS)
    }

    /** Останавливает службу и возвращает BlueLM на кнопку питания. */
    fun disable() {
        setEnabled(false)
        service?.let {
            runCatching {
                it.stop()
                it.setBlueLmOnPowerKey(true)
            }.onFailure { e -> Log.w(TAG, e) }
        }
        if (isShizukuRunning()) Shizuku.unbindUserService(serviceArgs, connection, true)
        service = null
        notifyListeners()
    }

    private fun bind() {
        if (!hasPermission()) return
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }.onFailure { Log.w(TAG, it) }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyListeners() = listeners.forEach { it() }
}

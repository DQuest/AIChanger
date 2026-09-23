package dev.dquest.aichanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import dev.dquest.aichanger.daemon.DaemonProtocol
import dev.dquest.aichanger.daemon.PowerKeyDaemon
import java.io.IOException

/**
 * Запускает [PowerKeyDaemon] с uid shell через собственный adb приложения: включает беспроводную
 * отладку, подключается к своему adbd ключом, сопряжённым заранее, при первом запуске выдаёт
 * приложению WRITE_SECURE_SETTINGS (чтобы дальше включать отладку самому) и стартует демон.
 */
object DaemonLauncher {

    private const val TAG = "AIChanger"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
    private const val WIFI_WAIT_MS = 90_000L
    private const val CONNECT_ATTEMPTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val DAEMON_WAIT_MS = 10_000L
    private const val POLL_MS = 500L

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Выполняется в фоновом потоке, бросает исключение с понятным текстом при ошибке. */
    fun launch(context: Context, token: String, voice: Boolean) {
        withWirelessAdb(context) { adb ->
            if (!canWriteSecureSettings(context)) {
                shell(adb, "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
            }
            Log.i(TAG, "daemon launch: ${shell(adb, launchCommand(context, token, voice))}")
        }
        check(waitForDaemon(token)) { "демон не ответил после запуска" }
    }

    /** Возвращает BlueLM на кнопку, когда демона, который сделал бы это сам, уже нет. */
    fun restoreBlueLm(context: Context) {
        withWirelessAdb(context) { adb -> shell(adb, DaemonProtocol.blueLmCommand(true)) }
    }

    private fun withWirelessAdb(context: Context, block: (AdbManager) -> Unit) {
        waitForWifi(context)
        val resolver = context.contentResolver
        val wasEnabled = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1
        // Беспроводная отладка живёт внутри настроек для разработчиков: если их выключили, включаем на время.
        val devWasEnabled = Settings.Global.getInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        if (!wasEnabled) {
            check(canWriteSecureSettings(context)) {
                "включите «Беспроводная отладка» в настройках для разработчиков и повторите"
            }
            if (!devWasEnabled) Settings.Global.putInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, 1)
            // Не включится в сети, для которой не разрешено «Всегда разрешать в этой сети».
            Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
        }
        try {
            withAdb(context, block)
        } finally {
            // Демон переживает выключение отладки, а держать её включённой незачем.
            if (!wasEnabled) {
                Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 0)
                if (!devWasEnabled) Settings.Global.putInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, 0)
            }
        }
    }

    /**
     * Старые экземпляры убиваем по имени процесса: демон прежней версии и служба из версий с Shizuku.
     * `pkill -f` не годится: он нашёл бы и сам этот `sh -c`, в строке которого есть те же имена.
     * `shell:` в libadb идёт через терминал: если сразу завершить команду, adbd закроет сессию
     * и фоновый процесс погибнет от hangup раньше, чем `setsid` его отвяжет. Поэтому в конце ждём.
     */
    private fun launchCommand(context: Context, token: String, voice: Boolean): String {
        val mode = if (voice) DaemonProtocol.MODE_VOICE else DaemonProtocol.MODE_WINDOW
        return "for n in ${DaemonProtocol.PROCESS_NAME} ${context.packageName}:powerkey; do " +
            "p=\$(pidof \$n) && kill \$p; done; sleep 0.3; " +
            "CLASSPATH=${context.applicationInfo.sourceDir} setsid app_process /system/bin " +
            "--nice-name=${DaemonProtocol.PROCESS_NAME} ${PowerKeyDaemon::class.java.name} $token $mode " +
            "</dev/null >/dev/null 2>&1 & sleep 1"
    }

    private fun withAdb(context: Context, block: (AdbManager) -> Unit) {
        val adb = AdbManager.get(context)
        var lastError: Exception? = null
        repeat(CONNECT_ATTEMPTS) {
            try {
                if (adb.isConnected || adb.connectTls(context, CONNECT_TIMEOUT_MS)) {
                    try {
                        block(adb)
                    } finally {
                        adb.disconnect()
                    }
                    return
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ADB connect attempt failed", e)
            }
            Thread.sleep(POLL_MS * 4)
        }
        throw IllegalStateException("не удалось подключиться к беспроводной отладке: ${lastError?.message}", lastError)
    }

    private fun shell(adb: AdbManager, command: String): String =
        adb.openStream("shell:$command").use { stream ->
            val output = StringBuilder()
            val reader = stream.openInputStream().bufferedReader()
            val buffer = CharArray(1024)
            try {
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.appendRange(buffer, 0, count)
                }
            } catch (e: IOException) {
                // Когда adbd закрывает поток, libadb бросает «Stream closed» вместо конца потока.
                if (!stream.isClosed) throw e
            }
            output.toString()
        }

    private fun waitForWifi(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val deadline = System.currentTimeMillis() + WIFI_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION") // для беспроводной отладки Wi-Fi не обязан быть основной сетью
            val wifi = connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi) return
            Thread.sleep(POLL_MS)
        }
        error("нет подключения к Wi-Fi")
    }

    private fun waitForDaemon(token: String): Boolean {
        val deadline = System.currentTimeMillis() + DAEMON_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (DaemonClient.ping(token) == BuildConfig.VERSION_CODE) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }
}

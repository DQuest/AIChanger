package dev.dquest.aichanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Запускает Shizuku без компьютера: включает беспроводную отладку, подключается к своему adbd
 * ключом, сопряжённым заранее, и выполняет стартер Shizuku (`libshizuku.so`).
 */
object ShizukuStarter {

    private const val TAG = "AIChanger"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WIFI_WAIT_MS = 90_000L
    private const val CONNECT_ATTEMPTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SHIZUKU_WAIT_MS = 10_000L
    private const val POLL_MS = 500L

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Выполняется в фоновом потоке. Возвращает текст результата для уведомления или экрана. */
    fun start(context: Context): String {
        if (Shizuku.pingBinder()) return "Shizuku уже запущен"
        check(canWriteSecureSettings(context)) { "нет разрешения WRITE_SECURE_SETTINGS" }
        waitForWifi(context)

        val resolver = context.contentResolver
        val wasEnabled = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1
        // Беспроводная отладка не включится в сети, которой не разрешили «Всегда разрешать в этой сети».
        Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
        try {
            return startOverAdb(context)
        } finally {
            // Shizuku переживает выключение отладки, а держать её включённой незачем.
            if (!wasEnabled) Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 0)
        }
    }

    private fun startOverAdb(context: Context): String {
        val adb = AdbManager.get(context)
        var lastError: Exception? = null
        repeat(CONNECT_ATTEMPTS) {
            try {
                if (adb.isConnected || adb.connectTls(context, CONNECT_TIMEOUT_MS)) {
                    val output = runShell(adb, starterPath(context))
                    Log.i(TAG, "Shizuku starter: $output")
                    adb.disconnect()
                    return if (waitForShizuku()) "Shizuku запущен" else "стартер отработал, но Shizuku не ответил:\n$output"
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ADB connect attempt failed", e)
            }
            Thread.sleep(POLL_MS * 4)
        }
        throw IllegalStateException("не удалось подключиться к беспроводной отладке: ${lastError?.message}", lastError)
    }

    private fun starterPath(context: Context): String =
        context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0).nativeLibraryDir + "/libshizuku.so"

    private fun runShell(adb: AdbManager, command: String): String =
        adb.openStream("shell:$command").use { stream ->
            stream.openInputStream().bufferedReader().readText()
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

    private fun waitForShizuku(): Boolean {
        val deadline = System.currentTimeMillis() + SHIZUKU_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (Shizuku.pingBinder()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }
}

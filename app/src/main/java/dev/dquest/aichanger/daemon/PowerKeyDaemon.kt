package dev.dquest.aichanger.daemon

import android.util.Log
import dev.dquest.aichanger.BuildConfig
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Процесс с uid shell, который приложение запускает через свой adb:
 * `CLASSPATH=<apk> app_process /system/bin --nice-name=aichanger-daemon dev.dquest.aichanger.daemon.PowerKeyDaemon <токен> <режим>`.
 * Держит [PowerKeyEngine] и принимает команды приложения по [DaemonProtocol].
 */
object PowerKeyDaemon {

    private const val TAG = "AIChanger"
    private const val UNINSTALL_CHECK_MS = 10_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0) ?: fail("не передан токен")
        val voice = args.getOrNull(1) != DaemonProtocol.MODE_WINDOW
        // Если порт занят, значит, демон уже работает; второй экземпляр не нужен.
        val server = runCatching {
            ServerSocket(DaemonProtocol.PORT, 4, InetAddress.getByName(DaemonProtocol.HOST))
        }.getOrElse { fail("порт ${DaemonProtocol.PORT} занят: ${it.message}") }

        val engine = PowerKeyEngine(DaemonProtocol.HOLD_MS, DaemonProtocol.POWER_MENU_MS, voice)
        engine.start()
        Log.i(TAG, "daemon v${BuildConfig.VERSION_CODE} started, voice=$voice")
        thread(name = "uninstall-watch", isDaemon = true) { watchForUninstall(engine) }

        while (true) {
            val client = server.accept()
            thread(name = "daemon-client") { handle(client, token, engine) }
        }
    }

    private fun handle(client: Socket, token: String, engine: PowerKeyEngine) = client.use { socket ->
        socket.soTimeout = 3000
        val parts = socket.getInputStream().bufferedReader().readLine().orEmpty().split(' ')
        val out = socket.getOutputStream().bufferedWriter()
        if (!MessageDigest.isEqual(parts[0].toByteArray(), token.toByteArray())) {
            out.write(DaemonProtocol.DENIED)
            out.flush()
            return@use
        }
        val reply = when (parts.getOrNull(1)) {
            DaemonProtocol.PING -> BuildConfig.VERSION_CODE.toString()
            DaemonProtocol.STATUS -> engine.status()
            DaemonProtocol.MODE -> {
                engine.voiceMode = parts.getOrNull(2) != DaemonProtocol.MODE_WINDOW
                DaemonProtocol.OK
            }
            DaemonProtocol.STOP -> {
                engine.stop()
                engine.setBlueLmOnPowerKey(true)
                out.write(DaemonProtocol.OK)
                out.flush()
                Log.i(TAG, "daemon stopped by app")
                exitProcess(0)
            }
            else -> "unknown command"
        }
        out.write(reply)
        out.flush()
    }

    /**
     * Демон работает с uid shell, и удаление приложения его не убивает. Без этой проверки кнопка
     * продолжала бы вызывать Gemini, а BlueLM так и остался бы выключенным даже после перезагрузки.
     * При обновлении APK тоже пропадает, но пакет остаётся: тогда приложение само перезапустит демон.
     */
    private fun watchForUninstall(engine: PowerKeyEngine) {
        val apk = File(System.getProperty("java.class.path").orEmpty().substringBefore(':'))
        while (true) {
            Thread.sleep(UNINSTALL_CHECK_MS)
            if (apk.exists() || isPackageInstalled()) continue
            engine.stop()
            engine.setBlueLmOnPowerKey(true)
            Log.i(TAG, "app uninstalled, daemon exits")
            exitProcess(0)
        }
    }

    private fun isPackageInstalled(): Boolean {
        val process = ProcessBuilder("pm", "path", BuildConfig.APPLICATION_ID).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.contains("package:")
    }

    private fun fail(message: String): Nothing {
        Log.e(TAG, "daemon: $message")
        exitProcess(1)
    }
}

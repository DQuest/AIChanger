package dev.dquest.aichanger.daemon

import android.util.Log
import dev.dquest.aichanger.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    // Столько проверок подряд пакета нет: одна неудачная (pm занят, например, пока перезапускается system_server) не в счёт.
    private const val UNINSTALL_CONFIRMATIONS = 3
    private const val CLIENT_TIMEOUT_MS = 3000
    private const val MAX_CLIENTS = 4
    private const val MAX_QUEUED = 8
    // Команда — это токен (32 символа), имя команды и два имени пакета или класса.
    private const val MAX_LINE = 1024
    private const val ACCEPT_RETRY_MS = 1000L

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0) ?: fail("no token passed")
        val voice = args.getOrNull(1) != DaemonProtocol.MODE_WINDOW
        // Если порт занят, значит, демон уже работает; второй экземпляр не нужен.
        val server = runCatching {
            ServerSocket(DaemonProtocol.PORT, 4, InetAddress.getByName(DaemonProtocol.HOST))
        }.getOrElse { fail("port ${DaemonProtocol.PORT} is busy: ${it.message}") }

        val engine = PowerKeyEngine(DaemonProtocol.HOLD_MS, DaemonProtocol.POWER_MENU_MS, voice)
        engine.start()
        Log.i(TAG, "daemon v${BuildConfig.VERSION_CODE} started, voice=$voice")
        thread(name = "uninstall-watch", isDaemon = true) { watchForUninstall(engine) }

        // Подключиться к порту может любое приложение. Ограниченный пул не даёт открыть поток на каждое
        // соединение, пока не кончится память; лишние соединения просто закрываем.
        val clients = ThreadPoolExecutor(MAX_CLIENTS, MAX_CLIENTS, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_QUEUED))
        while (true) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                // Например, кончились дескрипторы: падать из-за этого нельзя, кнопка осталась бы без ассистента.
                Log.w(TAG, "daemon: accept failed: $e")
                Thread.sleep(ACCEPT_RETRY_MS)
                continue
            }
            try {
                clients.execute { serve(client, token, engine) }
            } catch (_: RejectedExecutionException) {
                client.close()
            }
        }
    }

    /**
     * Под app_process необработанное исключение в любом потоке завершает весь процесс (RuntimeInit ставит
     * KillApplicationHandler), и кнопка осталась бы без ассистента, пока не откроют приложение. Поэтому ошибки
     * клиента (молчит до таймаута, оборвал соединение) только пишем в лог.
     */
    private fun serve(client: Socket, token: String, engine: PowerKeyEngine) {
        try {
            client.use { handle(it, token, engine) }
        } catch (e: Throwable) {
            Log.w(TAG, "daemon: client failed: $e")
        }
    }

    private fun handle(socket: Socket, token: String, engine: PowerKeyEngine) {
        socket.soTimeout = CLIENT_TIMEOUT_MS
        val parts = readCommand(socket.getInputStream().buffered()) ?: return
        val out = socket.getOutputStream().bufferedWriter()
        if (!MessageDigest.isEqual(parts[0].toByteArray(), token.toByteArray())) {
            out.write(DaemonProtocol.DENIED)
            out.flush()
            return
        }
        val reply = when (parts.getOrNull(1)) {
            DaemonProtocol.PING -> BuildConfig.VERSION_CODE.toString()
            DaemonProtocol.STATUS -> engine.status()
            DaemonProtocol.PROBLEM -> engine.problem().orEmpty()
            DaemonProtocol.MODE -> {
                engine.voiceMode = parts.getOrNull(2) != DaemonProtocol.MODE_WINDOW
                DaemonProtocol.OK
            }
            DaemonProtocol.ASSISTANT -> {
                val target = parts.getOrNull(2) ?: return
                val restore = parts.getOrNull(3)?.takeIf { it != DaemonProtocol.NONE }
                // Аргументы уходят в команды cmd и am: принимаем только имена пакетов и классов.
                if (DaemonProtocol.isValidTarget(target) && (restore == null || DaemonProtocol.isValidRestore(restore))) {
                    engine.setAssistant(target, restore) ?: DaemonProtocol.NONE
                } else {
                    Log.w(TAG, "daemon: rejected assistant $target $restore")
                    DaemonProtocol.INVALID
                }
            }
            DaemonProtocol.STOP -> stop(engine, out)
            else -> "unknown command"
        }
        out.write(reply)
        out.flush()
    }

    /** Завершается, даже если вернуть не вышло: приложение, не получив [DaemonProtocol.OK], вернёт всё через adb. */
    private fun stop(engine: PowerKeyEngine, out: Writer): Nothing {
        try {
            restoreSystem(engine)
            out.write(DaemonProtocol.OK)
            out.flush()
        } finally {
            Log.i(TAG, "daemon stopped by app")
            exitProcess(0)
        }
    }

    /** Строка команды, разбитая по пробелам; null, если она длиннее [MAX_LINE]: такое шлёт не наше приложение. */
    internal fun readCommand(input: InputStream): List<String>? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1 || byte == '\n'.code) break
            if (line.size() >= MAX_LINE) return null
            line.write(byte)
        }
        return String(line.toByteArray(), Charsets.UTF_8).split(' ')
    }

    /**
     * Демон работает с uid shell, и удаление приложения его не убивает. Без этой проверки кнопка
     * продолжала бы вызывать ассистента до перезагрузки, а системным остался бы выбранный в приложении ассистент.
     * При обновлении APK тоже пропадает, но пакет остаётся: тогда приложение само перезапустит демон.
     */
    private fun watchForUninstall(engine: PowerKeyEngine) {
        val apk = File(System.getProperty("java.class.path").orEmpty().substringBefore(':'))
        var missing = 0
        while (true) {
            Thread.sleep(UNINSTALL_CHECK_MS)
            // Ложное «удалено» выключило бы перехват, поэтому сбой самого pm удалением не считаем.
            val installed = apk.exists() || runCatching { isPackageInstalled() }.getOrNull() != false
            missing = if (installed) 0 else missing + 1
            if (missing < UNINSTALL_CONFIRMATIONS) continue
            restoreSystem(engine)
            Log.i(TAG, "app uninstalled, daemon exits")
            exitProcess(0)
        }
    }

    /** Останавливает перехват и возвращает то, что демон менял: системного ассистента. BlueLM включает сам пользователь. */
    private fun restoreSystem(engine: PowerKeyEngine) {
        engine.stop()
        engine.restoreAssistantRole()
    }

    /**
     * true — установлен, false — нет, null — pm не ответил по делу. Для удалённого пакета `pm path` ничего
     * не выводит; любой другой вывод без `package:` (например, «Can't find service: package», пока
     * перезапускается system_server) — это сбой, а не ответ.
     */
    private fun isPackageInstalled(): Boolean? {
        val process = ProcessBuilder("pm", "path", BuildConfig.APPLICATION_ID).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return when {
            output.contains("package:") -> true
            output.isBlank() -> false
            else -> null
        }
    }

    private fun fail(message: String): Nothing {
        Log.e(TAG, "daemon: $message")
        exitProcess(1)
    }
}

package dev.dquest.aichanger

import dev.dquest.aichanger.daemon.DaemonProtocol
import java.net.InetSocketAddress
import java.net.Socket

/** Команды демону по [DaemonProtocol]. Блокирующие, вызывать не из главного потока. */
object DaemonClient {

    private const val CONNECT_TIMEOUT_MS = 500
    private const val READ_TIMEOUT_MS = 3000
    // Смена системного ассистента (cmd role) бывает медленной, если PermissionController ещё не запущен.
    // Ответ, потерянный по таймауту, оставил бы приложение без знания, кого вернуть.
    private const val ROLE_TIMEOUT_MS = 15_000

    /** versionCode работающего демона или null, если он не запущен (или не наш: другой токен). */
    fun ping(token: String): Int? = request(token, DaemonProtocol.PING)?.trim()?.toIntOrNull()

    fun status(token: String): String? = request(token, DaemonProtocol.STATUS)

    /** Почему кнопка не перехватывается; пустая строка — всё в порядке, null — демона нет. */
    fun problem(token: String): String? = request(token, DaemonProtocol.PROBLEM)?.trim()

    fun setVoiceMode(token: String, voice: Boolean) =
        request(token, "${DaemonProtocol.MODE} ${if (voice) DaemonProtocol.MODE_VOICE else DaemonProtocol.MODE_WINDOW}")

    /** Ответ: кого вернуть системным ассистентом при выключении ([DaemonProtocol.NONE] — никого), null — демона нет. */
    fun setAssistant(token: String, target: String, restore: String?) =
        request(token, "${DaemonProtocol.ASSISTANT} $target ${restore ?: DaemonProtocol.NONE}", ROLE_TIMEOUT_MS)?.trim()

    fun stop(token: String) = request(token, DaemonProtocol.STOP, ROLE_TIMEOUT_MS)

    private fun request(token: String, command: String, timeoutMs: Int = READ_TIMEOUT_MS): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(DaemonProtocol.HOST, DaemonProtocol.PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write("$token $command\n".toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readText()
        }
    }.getOrNull()?.takeIf { it != DaemonProtocol.DENIED }
}

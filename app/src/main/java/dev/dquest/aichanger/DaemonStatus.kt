package dev.dquest.aichanger

import android.content.Context
import dev.dquest.aichanger.daemon.DaemonProtocol

/** Разбор ответа демона на [DaemonProtocol.STATUS]: строки `ключ=значение`. */
internal fun parseStatus(raw: String): Map<String, String> = raw.lineSequence()
    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key to value } }
    .toMap()

/** Сведения о демоне для экрана на языке телефона: демон отдаёт только данные ([DaemonProtocol.STATUS]). */
fun describeStatus(context: Context, raw: String): String {
    val status = parseStatus(raw)
    val lines = mutableListOf<String>()
    status[DaemonProtocol.STATUS_DEVICES]?.let { lines += context.getString(R.string.status_devices, it.ifEmpty { "—" }) }
    val hold = status[DaemonProtocol.STATUS_HOLD_MS]
    val menu = status[DaemonProtocol.STATUS_MENU_MS]
    if (hold != null && menu != null) lines += context.getString(R.string.status_thresholds, hold, menu)
    status[DaemonProtocol.STATUS_ASSISTANT]?.let { lines += context.getString(R.string.status_assistant, it) }
    status[DaemonProtocol.STATUS_VOICE]?.let {
        lines += context.getString(if (it == "1") R.string.status_mode_voice else R.string.status_mode_window)
    }
    status[DaemonProtocol.STATUS_FIRED]?.let { lines += context.getString(R.string.status_fired, it) }
    status[DaemonProtocol.STATUS_PROBLEM]?.let { lines += context.getString(R.string.status_error, describeProblem(context, it)) }
    status[DaemonProtocol.STATUS_LAUNCH_ERROR]?.let { lines += context.getString(R.string.status_launch_error, it) }
    // Демон прежней версии отвечал готовым текстом: показываем его как есть, пока его не заменят.
    return if (lines.isEmpty()) raw else lines.joinToString("\n")
}

/** Текст проблемы по коду демона ([DaemonProtocol.PROBLEM]); незнакомый код (демон другой версии) — как есть. */
fun describeProblem(context: Context, code: String): String = when {
    code == DaemonProtocol.PROBLEM_NO_POWER_KEY -> context.getString(R.string.problem_no_power_key)
    code.startsWith(DaemonProtocol.PROBLEM_UNREADABLE + " ") -> {
        val details = code.removePrefix(DaemonProtocol.PROBLEM_UNREADABLE + " ")
        context.getString(R.string.problem_power_key_unreadable, details.substringBefore(' '), details.substringAfter(' ', ""))
    }
    else -> code
}

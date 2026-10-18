package dev.dquest.aichanger

import android.content.Context

/** Сопряжения с беспроводной отладкой ещё нет: без него запустить демон нельзя. */
class NotPairedException : IllegalStateException("not paired with wireless debugging")

/**
 * Текст ошибки для пользователя на языке телефона: что случилось и что сделать. Сообщения самих исключений
 * английские и только для лога; неизвестную ошибку показываем её сообщением, как есть.
 */
fun describeError(context: Context, error: Throwable): String {
    val text = when (error) {
        is PairingLostException -> R.string.error_pairing_lost
        is NotPairedException -> R.string.error_not_paired
        is UntrustedWifiException -> R.string.error_untrusted_wifi
        is NoWifiException -> R.string.error_no_wifi
        is WirelessDebuggingOffException -> R.string.error_enable_wireless_debugging
        is AdbConnectException -> R.string.error_adb_connect
        is DaemonNoAnswerException -> R.string.error_daemon_no_answer
        else -> return error.message ?: context.getString(R.string.error_unknown)
    }
    return context.getString(text)
}

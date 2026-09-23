package dev.dquest.aichanger.daemon

/**
 * Протокол между приложением и демоном: TCP на 127.0.0.1, одна строка `<токен> <команда> [аргумент]`,
 * ответ текстом до закрытия соединения. Токен приложение передаёт демону при запуске.
 */
object DaemonProtocol {
    const val HOST = "127.0.0.1"
    const val PORT = 47831
    const val PROCESS_NAME = "aichanger-daemon"
    const val HOLD_MS = 600
    const val POWER_MENU_MS = 2000

    const val PING = "ping" // ответ: versionCode демона
    const val STATUS = "status"
    const val MODE = "mode" // аргумент: MODE_VOICE или MODE_WINDOW
    const val STOP = "stop" // вернуть BlueLM на кнопку и завершиться

    const val MODE_VOICE = "voice"
    const val MODE_WINDOW = "window"

    const val OK = "ok"
    const val DENIED = "denied"

    /**
     * Shell-команда, возвращающая или убирающая BlueLM с кнопки питания.
     * vivo молча игнорирует `settings put` для этого ключа, а запись через провайдер работает.
     */
    fun blueLmCommand(enabled: Boolean) =
        "content insert --uri content://settings/system " +
            "--bind name:s:vivo_jovi_power_wakeup_switch --bind value:s:${if (enabled) 1 else 0}"
}

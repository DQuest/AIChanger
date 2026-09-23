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
    const val STOP = "stop" // вернуть BlueLM на кнопку и прежнего системного ассистента, завершиться
    /**
     * Аргументы: кого вызывать ([GOOGLE_APP] для Gemini, пакет, который нужно сделать системным ассистентом,
     * или activity режима ассистента `пакет/класс`) и кого вернуть системным ассистентом при выключении
     * (или [NONE]). Ответ: кого вернуть теперь; приложение хранит это между запусками демона.
     */
    const val ASSISTANT = "assistant"

    const val MODE_VOICE = "voice"
    const val MODE_WINDOW = "window"

    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val NONE = "-"
    /** Прежде системного ассистента не было: при выключении снимаем роль со всех. */
    const val NO_ASSISTANT = "none"
    private const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"
    const val GET_ASSISTANT_ROLE = "cmd role get-role-holders $ASSISTANT_ROLE"

    /** Прямой запуск режима ассистента, `пакет/класс`; иначе это пакет системного ассистента. */
    fun isDirect(assistant: String) = assistant.contains('/')

    fun assistantPackage(assistant: String) = assistant.substringBefore('/')

    fun assistantRoleCommand(packageName: String) =
        if (packageName == NO_ASSISTANT) "cmd role clear-role-holders $ASSISTANT_ROLE 0"
        else "cmd role add-role-holder $ASSISTANT_ROLE $packageName 0"

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

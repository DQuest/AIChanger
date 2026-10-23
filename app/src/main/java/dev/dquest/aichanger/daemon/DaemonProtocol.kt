package dev.dquest.aichanger.daemon

/**
 * Протокол между приложением и демоном: TCP на 127.0.0.1, одна строка `<токен> <команда> [аргумент]`,
 * ответ текстом до закрытия соединения. Токен приложение передаёт демону при запуске, каждый раз новый:
 * порт занять может кто угодно, и токен, отправленный ему, пока демона нет, к следующему демону не подойдёт.
 */
object DaemonProtocol {
    const val HOST = "127.0.0.1"
    const val PORT = 47831
    const val PROCESS_NAME = "aichanger-daemon"
    const val HOLD_MS = 600
    const val POWER_MENU_MS = 2000

    const val PING = "ping" // ответ: versionCode демона
    const val STATUS = "status" // ответ: строки `ключ=значение` (STATUS_*), текст на языке телефона собирает приложение
    const val PROBLEM = "problem" // ответ: код PROBLEM_*, почему кнопка питания не перехватывается; пусто — всё в порядке

    // У демона нет ресурсов приложения, поэтому он отдаёт данные и коды, а не готовый текст.
    const val STATUS_DEVICES = "devices"
    const val STATUS_HOLD_MS = "hold_ms"
    const val STATUS_MENU_MS = "menu_ms"
    const val STATUS_ASSISTANT = "assistant"
    const val STATUS_VOICE = "voice" // только для Gemini: 1 — голосовой ввод, 0 — окно
    const val STATUS_FIRED = "fired"
    const val STATUS_PROBLEM = "problem" // код PROBLEM_*
    const val STATUS_LAUNCH_ERROR = "launch_error" // вывод am start, как есть
    const val PROBLEM_NO_POWER_KEY = "no_power_key"
    const val PROBLEM_UNREADABLE = "unreadable" // `unreadable <путь> <сообщение>`
    const val MODE = "mode" // аргумент: MODE_VOICE или MODE_WINDOW
    const val STOP = "stop" // вернуть прежнего системного ассистента, завершиться
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

    // Демон работает с uid shell и подставляет эти имена в команды cmd и am, поэтому принимает только
    // настоящие имена пакетов и классов: никаких пробелов, кавычек и символов оболочки.
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

    /** Годится ли [assistant] как кого вызывать: пакет или `пакет/класс`. */
    fun isValidTarget(assistant: String) =
        PACKAGE_NAME.matches(assistantPackage(assistant)) &&
            (!isDirect(assistant) || CLASS_NAME.matches(assistant.substringAfter('/')))

    /** Годится ли [packageName] как кого вернуть системным ассистентом: пакет или [NO_ASSISTANT]. */
    fun isValidRestore(packageName: String) = packageName == NO_ASSISTANT || PACKAGE_NAME.matches(packageName)

    /** Команда отдельными аргументами; [packageName] должен пройти [isValidRestore]. */
    fun assistantRoleArgs(packageName: String) =
        if (packageName == NO_ASSISTANT) listOf("cmd", "role", "clear-role-holders", ASSISTANT_ROLE, "0")
        else listOf("cmd", "role", "add-role-holder", ASSISTANT_ROLE, packageName, "0")

    /** Снять роль системного ассистента с [packageName]; это имя пакета из [isValidTarget]. */
    fun removeAssistantRoleArgs(packageName: String) =
        listOf("cmd", "role", "remove-role-holder", ASSISTANT_ROLE, packageName, "0")

    const val OK = "ok"
    const val DENIED = "denied"
    /** Ответ на [ASSISTANT] с недопустимыми аргументами: выбор не принят. */
    const val INVALID = "invalid"
}

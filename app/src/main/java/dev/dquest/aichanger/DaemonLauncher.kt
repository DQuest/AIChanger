package dev.dquest.aichanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import dev.dquest.aichanger.daemon.DaemonProtocol
import dev.dquest.aichanger.daemon.PowerKeyDaemon
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.IOException
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * adbd не узнаёт ключ AIChanger: сопряжение больше не действует (например, истекло, см.
 * [DaemonLauncher.disableAuthorizationTimeout]). Повторять бессмысленно, нужно новое сопряжение.
 */
class PairingLostException(cause: Throwable) :
    IllegalStateException("сопряжение с беспроводной отладкой больше не действует, выполните его заново", cause)

/**
 * Запускает [PowerKeyDaemon] с uid shell через собственный adb приложения: включает беспроводную
 * отладку, подключается к своему adbd ключом, сопряжённым заранее, при первом запуске выдаёт
 * приложению WRITE_SECURE_SETTINGS (чтобы дальше включать отладку самому) и стартует демон.
 */
object DaemonLauncher {

    private const val TAG = "AIChanger"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
    private const val MARKS = "launcher_marks"
    private const val WIFI_WAIT_MS = 30_000L
    private const val CONNECT_ATTEMPTS = 5
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
    // Столько раз система выключила отладку сразу после включения: значит, сеть не доверенная.
    private const val UNTRUSTED_AFTER = 2
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val DAEMON_WAIT_MS = 10_000L
    private const val POLL_MS = 500L
    private const val PORT_WAIT_MS = 6_000L
    private const val LOOPBACK = "127.0.0.1"
    // Диапазон, из которого ядро выдаёт порт adbd (/proc/sys/net/ipv4/ip_local_port_range).
    private val EPHEMERAL_PORTS = 32768..60999
    private const val SCAN_BATCH = 512
    private const val SCAN_THREADS = 8
    private const val PROBE_TIMEOUT_MS = 500
    private const val ADB_HEADER_SIZE = 24
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_STLS = 0x534c5453
    private val ADB_REPLIES = setOf(A_CNXN, A_AUTH, A_STLS)
    private const val ADB_VERSION = 0x01000001
    private const val ADB_MAX_DATA = 256 * 1024
    private const val SCAN_CONNECT_TIMEOUT_MS = 200

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Android забывает ключи отладки, которыми не пользовались 7 дней (пока выключена настройка «Отключить тайм-аут
     * авторизации adb»), и сопряжение по Wi-Fi тоже. AIChanger подключается к adb только при запуске демона, а телефон
     * может работать без перезагрузки дольше недели: тогда следующий запуск (например, после обновления) упрётся
     * в «нужно сопряжение». 0 — та же настройка в параметрах разработчика: ключи не истекают никогда.
     */
    fun disableAuthorizationTimeout(context: Context) {
        val resolver = context.contentResolver
        if (Settings.Global.getLong(resolver, ADB_ALLOWED_CONNECTION_TIME, -1) == 0L || !canWriteSecureSettings(context)) return
        Settings.Global.putLong(resolver, ADB_ALLOWED_CONNECTION_TIME, 0)
        Log.i(TAG, "adb authorization timeout disabled")
    }

    fun isWirelessAdbOn(context: Context) = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    /** Система выключила беспроводную отладку, пока мы искали порт: ждать порт дальше бессмысленно. */
    private class WirelessAdbOffException : IOException("система выключила беспроводную отладку")

    /**
     * Выполняется в фоновом потоке, бросает исключение с понятным текстом при ошибке.
     * [newToken] вызывается перед самой командой запуска: если до неё не дошло (нет Wi-Fi, не подключились),
     * прежний демон продолжает работать со старым токеном, и приложение по-прежнему может его остановить.
     */
    fun launch(context: Context, voice: Boolean, newToken: () -> String) {
        lateinit var token: String
        withWirelessAdb(context) { adb ->
            if (!canWriteSecureSettings(context)) {
                shell(adb, "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
            }
            disableAuthorizationTimeout(context)
            token = newToken()
            Log.i(TAG, "daemon launch: ${shell(adb, launchCommand(context, token, voice))}")
        }
        check(waitForDaemon(token)) { "демон не ответил после запуска" }
    }

    /**
     * Возвращает прежнего системного ассистента, когда демон не ответил на `stop`.
     * Демон при этом мог и работать, но не принять токен, поэтому сначала его завершаем.
     */
    fun restoreSystem(context: Context, restoreRole: String?) {
        withWirelessAdb(context) { adb ->
            shell(adb, killCommand(context))
            // Строку собирает оболочка adb, поэтому пропускаем только имя пакета.
            restoreRole?.takeIf(DaemonProtocol::isValidRestore)?.let {
                shell(adb, DaemonProtocol.assistantRoleArgs(it).joinToString(" "))
            }
        }
    }

    private fun withWirelessAdb(context: Context, block: (AdbManager) -> Unit) {
        waitForWifi(context)
        val resolver = context.contentResolver
        // Если процесс убили посреди запуска (например, обновлением), настройки остались включёнными нами.
        // Отметки об этом не дают принять их за включённые пользователем и оставить так навсегда.
        val marks = context.createDeviceProtectedStorageContext().getSharedPreferences(MARKS, Context.MODE_PRIVATE)
        val wasEnabled = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1 && !marks.getBoolean(ADB_WIFI_ENABLED, false)
        // Беспроводная отладка живёт внутри настроек для разработчиков: если их выключили, включаем на время.
        val devWasEnabled = Settings.Global.getInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 &&
            !marks.getBoolean(DEVELOPMENT_SETTINGS_ENABLED, false)
        if (!wasEnabled) {
            check(canWriteSecureSettings(context)) {
                "включите «Беспроводная отладка» в настройках для разработчиков и повторите"
            }
            marks.edit().putBoolean(ADB_WIFI_ENABLED, true).putBoolean(DEVELOPMENT_SETTINGS_ENABLED, !devWasEnabled).commit()
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
                marks.edit().clear().commit()
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
        return "${killCommand(context)}; sleep 0.3; " +
            "CLASSPATH=${context.applicationInfo.sourceDir} setsid app_process /system/bin " +
            "--nice-name=${DaemonProtocol.PROCESS_NAME} ${PowerKeyDaemon::class.java.name} $token $mode " +
            "</dev/null >/dev/null 2>&1 & sleep 1"
    }

    private fun killCommand(context: Context) =
        "for n in ${DaemonProtocol.PROCESS_NAME} ${context.packageName}:powerkey; do p=\$(pidof \$n) && kill \$p; done"

    private fun withAdb(context: Context, block: (AdbManager) -> Unit) {
        val adb = AdbManager.get(context)
        var lastError: Exception? = null
        var turnedOff = 0
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) restartWirelessAdb(context)
            try {
                if (adb.isConnected || connect(context, adb)) {
                    try {
                        block(adb)
                    } finally {
                        adb.disconnect()
                    }
                    return
                }
            } catch (e: WirelessAdbOffException) {
                lastError = e
                Log.w(TAG, "ADB connect attempt ${attempt + 1}: wireless debugging turned off by the system")
                // Сразу после загрузки система выключает отладку, если Wi-Fi ещё не готов, и перезапуск это лечит.
                // Выключила снова — значит, сеть не доверенная: в ней каждое включение лишь показывает диалог
                // о разрешении отладки, а перебор всех попыток занял бы около двух минут.
                if (++turnedOff >= UNTRUSTED_AFTER) throw UntrustedWifiException()
            } catch (e: PairingLostException) {
                // Перезапуск отладки и новые попытки тут не помогут, а заняли бы около минуты.
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ADB connect attempt ${attempt + 1} failed, adb_wifi_enabled=${isWirelessAdbOn(context)}", e)
            }
            Thread.sleep(POLL_MS * 4)
        }
        throw IllegalStateException("не удалось подключиться к беспроводной отладке: ${lastError?.message}", lastError)
    }

    /**
     * Порт беспроводной отладки ищем сами: свойство service.adb.tls.port приложениям закрыто, а mDNS
     * на телефоне ненадёжен: после перезапуска отладки остаётся устаревшая запись, а системный
     * клиент mDNS не может разобрать часть пакетов, и libadb не находит порт. mDNS — запасной путь.
     */
    private fun connect(context: Context, adb: AdbManager): Boolean {
        val rejected = mutableSetOf<Int>()
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        do {
            val started = System.currentTimeMillis()
            val ports = parallel(findLocalPorts().filter { it !in rejected }) { port -> port.takeIf(::isAdbPort) }
            Log.i(TAG, "adb ports ${ports.joinToString()} found in ${System.currentTimeMillis() - started} ms")
            for (port in ports) {
                val connected = try {
                    adb.connect(LOOPBACK, port)
                } catch (e: AdbPairingRequiredException) {
                    // adbd ответил, но ключ не узнал (SSLV3_ALERT_CERTIFICATE_UNKNOWN): это наш порт, дело в сопряжении.
                    throw PairingLostException(e)
                } catch (e: Exception) {
                    Log.w(TAG, "ADB connect to port $port failed: $e")
                    false
                }
                if (connected) {
                    Log.i(TAG, "ADB connected on port $port")
                    return true
                }
                rejected += port
            }
            if (!isWirelessAdbOn(context)) throw WirelessAdbOffException()
            // Сразу после включения отладки adbd открывает порт не мгновенно.
            Thread.sleep(POLL_MS)
        } while (System.currentTimeMillis() < deadline)
        return try {
            adb.connectTls(context, CONNECT_TIMEOUT_MS)
        } catch (e: AdbPairingRequiredException) {
            throw PairingLostException(e)
        }
    }

    /**
     * Порты, которые слушают на localhost в диапазоне порта adbd, кроме порта нашего демона.
     * Неблокирующие подключения пачками в нескольких потоках: по одному перебор ~28 тысяч портов занимал секунды.
     */
    private fun findLocalPorts(): List<Int> {
        val size = (EPHEMERAL_PORTS.last - EPHEMERAL_PORTS.first) / SCAN_THREADS + 1
        val parts = (0 until SCAN_THREADS).map { i ->
            EPHEMERAL_PORTS.first + i * size..minOf(EPHEMERAL_PORTS.first + (i + 1) * size - 1, EPHEMERAL_PORTS.last)
        }
        return parallel(parts, ::findOpenPorts).flatten().filter { it != DaemonProtocol.PORT }.sorted()
    }

    private fun <T, R : Any> parallel(items: List<T>, work: (T) -> R?): List<R> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(items.size, SCAN_THREADS))
        try {
            return items.map { pool.submit(Callable { work(it) }) }.mapNotNull { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun findOpenPorts(ports: IntRange): List<Int> {
        val open = mutableListOf<Int>()
        Selector.open().use { selector ->
            try {
                for (batch in ports.chunked(SCAN_BATCH)) {
                    // Считаем незавершённые сами: отменённые ключи остаются в selector.keys() до следующего select.
                    var pending = 0
                    for (port in batch) {
                        val channel = SocketChannel.open()
                        try {
                            channel.configureBlocking(false)
                            if (channel.connect(InetSocketAddress(LOOPBACK, port))) {
                                open += port
                                channel.close()
                            } else {
                                channel.register(selector, SelectionKey.OP_CONNECT, port)
                                pending++
                            }
                        } catch (_: IOException) {
                            channel.close()
                        }
                    }
                    val deadline = System.currentTimeMillis() + SCAN_CONNECT_TIMEOUT_MS
                    while (pending > 0) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) break
                        selector.select(left)
                        for (key in selector.selectedKeys()) {
                            val channel = key.channel() as SocketChannel
                            if (runCatching { channel.finishConnect() }.getOrDefault(false)) open += key.attachment() as Int
                            key.cancel()
                            channel.close()
                            pending--
                        }
                        selector.selectedKeys().clear()
                    }
                    // Не ответившие за отведённое время закрываем; selectNow снимает отменённые ключи.
                    for (key in selector.keys()) {
                        key.cancel()
                        key.channel().close()
                    }
                    selector.selectNow()
                }
            } finally {
                // Если SocketChannel.open() упал (например, кончились дескрипторы), закрываем уже открытые:
                // закрытие селектора сами каналы не закрывает, и они висели бы до сборки мусора.
                for (key in selector.keys()) key.channel().close()
            }
        }
        return open
    }

    /**
     * adbd отвечает на CNXN своим сообщением (на порту беспроводной отладки — STLS), чужие сервисы — нет.
     * Без этой пробы libadb ждала бы ответа от каждого чужого порта по несколько секунд.
     */
    private fun isAdbPort(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            val payload = "host::\u0000".toByteArray()
            val message = ByteBuffer.allocate(ADB_HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(A_CNXN).putInt(ADB_VERSION).putInt(ADB_MAX_DATA).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 0xff }).putInt(A_CNXN.inv())
                .put(payload)
            socket.getOutputStream().write(message.array())
            val reply = ByteArray(4)
            DataInputStream(socket.getInputStream()).readFully(reply)
            ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).int in ADB_REPLIES
        }
    } catch (_: IOException) {
        false
    }

    /**
     * Система сама выключает беспроводную отладку, если в момент включения Wi-Fi ещё не готов, а объявление
     * порта по mDNS после загрузки иногда теряется. Перезапуск отладки лечит оба случая: adbd заново
     * открывает порт и объявляет его.
     */
    private fun restartWirelessAdb(context: Context) {
        if (!canWriteSecureSettings(context)) return
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 0)
        Thread.sleep(POLL_MS * 2)
        Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
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

    /** После загрузки Wi-Fi подключается не сразу; дольше не ждём — ожиданием с уведомлением занимается AutoStartService. */
    private fun waitForWifi(context: Context) {
        if (!Wifi.awaitConnected(context, WIFI_WAIT_MS)) throw NoWifiException()
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

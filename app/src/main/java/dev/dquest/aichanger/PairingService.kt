package dev.dquest.aichanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.muntashirakon.adb.android.AdbMdns
import kotlin.concurrent.thread

/**
 * Сопряжение с беспроводной отладкой. Пользователь открывает «Сопряжение по коду» в настройках
 * и вводит шестизначный код прямо в уведомлении. Порт окна сопряжения служба ищет через mDNS;
 * на vivo поиск иногда ничего не находит, тогда пользователь вводит порт вместе с кодом.
 */
class PairingService : Service() {

    private var mdns: AdbMdns? = null
    @Volatile private var pairingPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> {
                val input = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                onInput(input)
            }
            else -> startDiscovery()
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        ensureChannel(this)
        startForeground(
            NOTIFICATION_ID,
            notification("Откройте «Беспроводная отладка» → «Сопряжение по коду» и введите код из окна", withCodeInput = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        mdns?.stop()
        // Порт окна сопряжения с прошлого раза уже закрыт: новое окно откроется на другом.
        pairingPort = -1
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) pairingPort = port
        }.also { it.start() }
    }

    /**
     * Код или «порт код» через пробел. Без порта ждём, пока его найдёт mDNS. Порт — число перед кодом:
     * так подходит и строка «IP-адрес и порт», вставленная целиком («192.168.1.5:37123 123456»).
     */
    private fun onInput(input: String) {
        val numbers = Regex("\\d+").findAll(input).map { it.value }.toList()
        val code = numbers.lastOrNull()
        if (code == null || code.length != PAIRING_CODE_LENGTH) {
            notify("Введите шестизначный код из окна сопряжения (или порт и код через пробел)", withCodeInput = true)
            return
        }
        val typedPort = numbers.getOrNull(numbers.size - 2)?.toIntOrNull()?.takeIf { it in 1..65535 }
        notify("Сопряжение…")
        thread(name = "adb-pairing") {
            val port = typedPort ?: waitForPort()
            if (port == null) {
                notify(
                    "Не нашёл окно сопряжения. Введите порт и код через пробел, например 37123 123456. " +
                        "Порт — число после двоеточия в строке «IP-адрес и порт».",
                    withCodeInput = true,
                )
            } else {
                pair(port, code)
            }
        }
    }

    private fun waitForPort(): Int? {
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        while (pairingPort == -1 && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return pairingPort.takeIf { it != -1 }
    }

    private fun pair(port: Int, code: String) {
        val paired = runCatching { AdbManager.get(this).pair(LOCALHOST, port, code) }
        if (paired.isFailure) {
            // Например, неверный код: оставляем поле ввода, чтобы можно было сразу повторить.
            notify("Сопряжение не удалось: ${paired.exceptionOrNull()?.message}. Проверьте код и порт", withCodeInput = true)
            return
        }
        PowerKeyController.setPaired(true)
        // Отладка сейчас включена пользователем, так что сразу и запускаем.
        val message = if (!PowerKeyController.isEnabled) {
            "Сопряжение выполнено"
        } else {
            runCatching { PowerKeyController.ensureRunning(this) }
                .fold({ "Сопряжение выполнено, перехват кнопки запущен" }, { "Сопряжение выполнено, но запуск не удался: ${it.message}" })
        }
        mdns?.stop()
        stopForeground(STOP_FOREGROUND_DETACH)
        notify(message)
        stopSelf()
    }

    private fun notify(text: String, withCodeInput: Boolean = false) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, withCodeInput))

    private fun notification(text: String, withCodeInput: Boolean = false): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AIChanger: сопряжение")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(!withCodeInput)
            .setContentIntent(openAppIntent(this))
        if (withCodeInput) {
            val reply = PendingIntent.getService(
                this, 0, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Код (или порт и код)").build()
            builder.addAction(Notification.Action.Builder(null, "Ввести код", reply).addRemoteInput(input).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        mdns?.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "aichanger"
        private const val PORT_WAIT_MS = 5000L
        private const val PAIRING_CODE_LENGTH = 6
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CODE = "dev.dquest.aichanger.PAIRING_CODE"
        private const val KEY_CODE = "code"
        private const val LOCALHOST = "127.0.0.1"

        /** Без явного contentIntent прошивка vivo до первой разблокировки пытается подставить свой и пишет ошибку. */
        fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        fun ensureChannel(context: Context) =
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AIChanger", NotificationManager.IMPORTANCE_HIGH)
            )
    }
}

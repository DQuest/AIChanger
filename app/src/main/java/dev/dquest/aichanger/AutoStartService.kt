package dev.dquest.aichanger

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Запускает демон кнопки после загрузки (или по кнопке «Включить») и завершается. Если Wi-Fi нет,
 * не сдаётся, а ждёт его с уведомлением: без Wi-Fi беспроводная отладка, через которую идёт запуск, не работает.
 */
class AutoStartService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PairingService.ensureChannel(this)
        // Уже ждём Wi-Fi или запускаем: второй поток ничего не добавит, первый сам запустит демон.
        // Уведомление оставляем текущее, иначе «Wi-Fi выключен» сменилось бы на «Запуск…».
        val alreadyBusy = !busy.compareAndSet(false, true)
        val shown = current?.takeIf { alreadyBusy } ?: notification("Запуск перехвата кнопки…").also { current = it }
        startForeground(NOTIFICATION_ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (alreadyBusy) return START_NOT_STICKY
        thread(name = "daemon-autostart") {
            try {
                val result = launchWhenWifi()
                Log.i(TAG, "autostart: $result")
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Об успехе не сообщаем, чтобы не мешать; об ошибке оставляем уведомление,
                // если только перехват не выключили, пока шёл запуск.
                result.exceptionOrNull()?.takeIf { PowerKeyController.isEnabled }
                    ?.let { show(notification("Не удалось запустить перехват кнопки: ${it.message}")) }
            } finally {
                current = null
                busy.set(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun launchWhenWifi(): Result<Unit> {
        while (true) {
            val result = runCatching { PowerKeyController.ensureRunning(this) }
            if (result.exceptionOrNull() !is NoWifiException) return result
            waitForWifi()
            // Пока ждали, перехват выключили: запускать уже не нужно, и это не ошибка.
            if (!PowerKeyController.isEnabled) return Result.success(Unit)
        }
    }

    private fun waitForWifi() {
        PowerKeyController.setWaitingForWifi(true)
        try {
            var shownEnabled: Boolean? = null
            while (PowerKeyController.isEnabled && !Wifi.isConnected(this)) {
                // Текст меняется, когда Wi-Fi включили, но сеть ещё не подключилась.
                val enabled = Wifi.isEnabled(this)
                if (enabled != shownEnabled) {
                    show(wifiNotification(enabled))
                    shownEnabled = enabled
                }
                Wifi.awaitConnected(this, WIFI_RECHECK_MS)
            }
        } finally {
            PowerKeyController.setWaitingForWifi(false)
        }
        show(notification("Запуск перехвата кнопки…"))
    }

    private fun show(notification: Notification) {
        current = notification
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun wifiNotification(wifiEnabled: Boolean): Notification {
        val openWifi = PendingIntent.getActivity(
            this, 0, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Перехват кнопки запустится сам, как только телефон подключится к Wi-Fi, " +
            "в котором настраивали AIChanger."
        return Notification.Builder(this, PairingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (wifiEnabled) "Wi-Fi не подключён" else "Wi-Fi выключен")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PairingService.openAppIntent(this))
            .addAction(Notification.Action.Builder(null, "Открыть Wi-Fi", openWifi).build())
            .setOngoing(true)
            .build()
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, PairingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AIChanger")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PairingService.openAppIntent(this))
            .build()

    companion object {
        private const val TAG = "AIChanger"
        private const val NOTIFICATION_ID = 2
        // Раз в столько перепроверяем, не включили ли Wi-Fi (подключение к сети приходит сразу, по колбэку).
        private const val WIFI_RECHECK_MS = 10_000L
        private val busy = AtomicBoolean(false)
        @Volatile private var current: Notification? = null

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AutoStartService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, AutoStartService::class.java))
    }
}

package dev.dquest.aichanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlin.concurrent.thread

/**
 * Запускает демон кнопки после загрузки (или по кнопке «Включить») и завершается. Если Wi-Fi нет,
 * не сдаётся, а ждёт его с уведомлением: без Wi-Fi беспроводная отладка, через которую идёт запуск, не работает.
 *
 * Запуск идёт в своём потоке, который может пережить экземпляр службы: «Выключить» останавливает службу,
 * а поток доходит до конца сам. Поэтому поток работает с контекстом приложения, а службу и уведомление
 * по его окончании убирает главный поток, в котором идёт и onStartCommand: так конец запуска не разминётся
 * с новым запросом.
 */
class AutoStartService : Service() {

    // Последний запрос этого экземпляра: остановка по нему не отменит запрос, который ещё не дошёл до onStartCommand.
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannel(this)
        // Уже ждём Wi-Fi или запускаем: уведомление оставляем текущее, иначе «Wi-Fi выключен» сменилось бы на «Запуск…».
        val shown = current ?: notification(this, getString(R.string.notif_starting)).also { current = it }
        startForeground(NOTIFICATION_ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // Поток уже идёт. Он мог решить, что запускать не нужно (перехват выключали), поэтому после него запустим ещё раз.
        if (working) {
            rerun = true
            retryNow = true
        } else {
            startWork(applicationContext)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "AIChanger"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "status"
        // Раз в столько перепроверяем, не включили ли Wi-Fi (подключение к сети приходит сразу, по колбэку).
        private const val WIFI_RECHECK_MS = 10_000L

        private val main = Handler(Looper.getMainLooper())
        // Меняются только в главном потоке.
        private var instance: AutoStartService? = null
        private var working = false
        private var rerun = false
        @Volatile private var current: Notification? = null
        // Запрос пришёл, пока ждём другую сеть Wi-Fi (например, открыли приложение): пробуем снова в этой.
        @Volatile private var retryNow = false

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AutoStartService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, AutoStartService::class.java))

        /**
         * Свой канал без звука и всплывающих уведомлений: запуск идёт при каждой загрузке и ответа не ждёт.
         * Канал сопряжения громкий, потому что там ждут ввода кода.
         */
        private fun ensureChannel(context: Context) =
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
            )

        private fun startWork(context: Context) {
            working = true
            rerun = false
            thread(name = "daemon-autostart") {
                val result = runCatching { launchWhenWifi(context) }
                Log.i(TAG, "autostart: $result")
                main.post { onWorkDone(context, result) }
            }
        }

        private fun onWorkDone(context: Context, result: Result<Unit>) {
            if (rerun && PowerKeyController.isEnabled) {
                startWork(context)
                return
            }
            working = false
            rerun = false
            current = null
            val service = instance
            service?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            // Об успехе не сообщаем, чтобы не мешать; об ошибке оставляем уведомление, если перехват не выключили.
            // Иначе убираем и то, что поток успел показать уже после остановки службы.
            val error = result.exceptionOrNull()?.takeIf { PowerKeyController.isEnabled }
            val manager = context.getSystemService(NotificationManager::class.java)
            if (error != null) {
                manager.notify(NOTIFICATION_ID, notification(context, context.getString(R.string.notif_start_failed, describeError(context, error))))
            } else {
                manager.cancel(NOTIFICATION_ID)
            }
            service?.let { it.stopSelfResult(it.lastStartId) }
        }

        private fun launchWhenWifi(context: Context) {
            while (true) {
                val networks = Wifi.networks(context)
                try {
                    // Если перехват выключили, пока ждали Wi-Fi, ensureRunning ничего не запустит.
                    PowerKeyController.ensureRunning(context)
                    return
                } catch (_: UntrustedWifiException) {
                    waitForOtherWifi(context, networks)
                } catch (_: NoWifiException) {
                    waitForWifi(context)
                }
            }
        }

        private fun waitForWifi(context: Context) {
            PowerKeyController.setWaitingForWifi(true)
            try {
                var shownEnabled: Boolean? = null
                while (PowerKeyController.isEnabled && !Wifi.isConnected(context)) {
                    // Текст меняется, когда Wi-Fi включили, но сеть ещё не подключилась.
                    val enabled = Wifi.isEnabled(context)
                    if (enabled != shownEnabled) {
                        show(context, wifiNotification(
                            context,
                            context.getString(if (enabled) R.string.notif_wifi_not_connected else R.string.status_wifi_off_title),
                        ))
                        shownEnabled = enabled
                    }
                    Wifi.awaitConnected(context, WIFI_RECHECK_MS)
                }
            } finally {
                PowerKeyController.setWaitingForWifi(false)
            }
            if (PowerKeyController.isEnabled) show(context, notification(context, context.getString(R.string.notif_starting)))
        }

        /**
         * В сети, где отладка не разрешена, каждая попытка лишь показывает системный диалог о разрешении, поэтому ждём
         * подключения к другой сети (или переподключения), нового запроса из приложения или того, что пользователь
         * разрешил отладку в этом диалоге: тогда система включает её сама.
         */
        private fun waitForOtherWifi(context: Context, known: Set<Network>) {
            PowerKeyController.setWaitingForWifi(true, untrusted = true)
            retryNow = false
            try {
                show(
                    context,
                    wifiNotification(
                        context,
                        context.getString(R.string.status_untrusted_wifi_title),
                        context.getString(R.string.untrusted_reason),
                    ),
                )
                while (PowerKeyController.isEnabled && !retryNow && !DaemonLauncher.isWirelessAdbOn(context)) {
                    if (Wifi.awaitNewNetwork(context, known, WIFI_RECHECK_MS)) break
                }
            } finally {
                PowerKeyController.setWaitingForWifi(false)
            }
            if (PowerKeyController.isEnabled) show(context, notification(context, context.getString(R.string.notif_starting)))
        }

        private fun show(context: Context, notification: Notification) {
            current = notification
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }

        private fun wifiNotification(context: Context, title: String, reason: String? = null): Notification {
            val openWifi = PendingIntent.getActivity(
                context, 0, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val text = listOfNotNull(
                reason,
                context.getString(R.string.wifi_wait_text),
            ).joinToString(" ")
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(PairingService.openAppIntent(context))
                .addAction(Notification.Action.Builder(null, context.getString(R.string.action_open_wifi), openWifi).build())
                .setOngoing(true)
                .build()
        }

        private fun notification(context: Context, text: String): Notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AIChanger")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(PairingService.openAppIntent(context))
                .build()
    }
}

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
 * Сопряжение с беспроводной отладкой. Пользователь открывает «Сопряжение по коду» в настройках,
 * служба находит порт через mDNS и принимает шестизначный код прямо из уведомления.
 */
class PairingService : Service() {

    private var mdns: AdbMdns? = null
    @Volatile private var pairingPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                if (code.isNullOrEmpty() || pairingPort == -1) notify("Код не введён, откройте окно сопряжения ещё раз")
                else pair(pairingPort, code)
            }
            else -> startDiscovery()
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        ensureChannel(this)
        startForeground(
            NOTIFICATION_ID,
            notification("Откройте «Беспроводная отладка» → «Сопряжение по коду»"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        mdns?.stop()
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host == null || port <= 0) return@AdbMdns
            pairingPort = port
            notify("Найдено окно сопряжения. Введите код из него.", withCodeInput = true)
        }.also { it.start() }
    }

    private fun pair(port: Int, code: String) {
        notify("Сопряжение…")
        thread(name = "adb-pairing") {
            val message = runCatching {
                AdbManager.get(this).pair(LOCALHOST, port, code)
                PowerKeyController.setPaired(true)
                "Сопряжение выполнено"
            }.getOrElse { "Сопряжение не удалось: ${it.message}" }
            mdns?.stop()
            stopForeground(STOP_FOREGROUND_DETACH)
            notify(message)
            stopSelf()
        }
    }

    private fun notify(text: String, withCodeInput: Boolean = false) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, withCodeInput))

    private fun notification(text: String, withCodeInput: Boolean = false): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AIChanger: сопряжение")
            .setContentText(text)
            .setOnlyAlertOnce(!withCodeInput)
            .setContentIntent(openAppIntent(this))
        if (withCodeInput) {
            val reply = PendingIntent.getService(
                this, 0, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Код сопряжения").build()
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

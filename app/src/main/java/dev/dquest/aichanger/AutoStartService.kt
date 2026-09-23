package dev.dquest.aichanger

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/** Запускает Shizuku после загрузки (или по кнопке) и завершается. */
class AutoStartService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PairingService.ensureChannel(this)
        startForeground(NOTIFICATION_ID, notification("Запуск Shizuku…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        thread(name = "shizuku-autostart") {
            val result = runCatching { ShizukuStarter.start(this) }
            Log.i(TAG, "autostart: $result")
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Об успехе не сообщаем, чтобы не мешать; об ошибке оставляем уведомление.
            result.exceptionOrNull()?.let {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Не удалось запустить Shizuku: ${it.message}"))
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, PairingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AIChanger")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PairingService.openAppIntent(this))
            .build()

    companion object {
        private const val TAG = "AIChanger"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AutoStartService::class.java))
    }
}

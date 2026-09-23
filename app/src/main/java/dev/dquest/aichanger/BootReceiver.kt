package dev.dquest.aichanger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import rikka.shizuku.Shizuku

/**
 * После загрузки запускает Shizuku, если включён автозапуск. Если Shizuku уже работает, достаточно
 * самого старта процесса: сервер Shizuku передаёт binder, и [PowerKeyController] поднимает службу.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PowerKeyController.isEnabled && PowerKeyController.isAutoStartEnabled && !Shizuku.pingBinder()) {
            AutoStartService.start(context)
        }
    }
}

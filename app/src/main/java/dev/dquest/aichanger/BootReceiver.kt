package dev.dquest.aichanger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * После загрузки (ещё до первой разблокировки) и после обновления приложения запускает демон кнопки,
 * если перехват включён. После обновления работает демон старой версии, его нужно заменить.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PowerKeyController.isEnabled && PowerKeyController.isPaired) AutoStartService.start(context)
    }
}

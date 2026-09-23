package dev.dquest.aichanger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PowerKeyController.init(this)
    }
}

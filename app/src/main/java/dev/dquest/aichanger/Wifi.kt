package dev.dquest.aichanger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Запуск демона возможен только через беспроводную отладку, а она работает только по Wi-Fi. */
class NoWifiException : IllegalStateException("нет подключения к Wi-Fi")

object Wifi {

    /** Для беспроводной отладки Wi-Fi не обязан быть основной сетью: достаточно, что он подключён. */
    fun isConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** Выключен ли Wi-Fi совсем (например, ради точки доступа), а не просто не подключён к сети. */
    fun isEnabled(context: Context) = context.getSystemService(WifiManager::class.java).isWifiEnabled

    /** Ждёт подключения к Wi-Fi не дольше [timeoutMs]; возвращает, подключён ли он. */
    fun awaitConnected(context: Context, timeoutMs: Long): Boolean {
        if (isConnected(context)) return true
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = latch.countDown()
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback
        )
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
        return isConnected(context)
    }
}

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
open class NoWifiException(message: String = "нет подключения к Wi-Fi") : IllegalStateException(message)

/**
 * Wi-Fi есть, но беспроводная отладка в этой сети не включается: для неё не разрешено «Всегда разрешать в этой сети».
 * Повторять в той же сети бесполезно, ждать нужно другой сети.
 */
class UntrustedWifiException : NoWifiException("беспроводная отладка не включается в этой сети Wi-Fi")

object Wifi {

    /** Для беспроводной отладки Wi-Fi не обязан быть основной сетью: достаточно, что он подключён. */
    fun isConnected(context: Context) = networks(context).isNotEmpty()

    /** Подключённые сети Wi-Fi: по ним [awaitNewNetwork] замечает, что телефон подключился к другой сети. */
    fun networks(context: Context): Set<Network> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.filter {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.toSet()
    }

    /**
     * Ждёт не дольше [timeoutMs] подключения к сети Wi-Fi не из [known]; возвращает, дождался ли.
     * Переподключение к той же сети тоже даёт новую [Network].
     */
    fun awaitNewNetwork(context: Context, known: Set<Network>, timeoutMs: Long): Boolean =
        awaitWifi(context, timeoutMs) { it !in known }

    /** Выключен ли Wi-Fi совсем (например, ради точки доступа), а не просто не подключён к сети. */
    fun isEnabled(context: Context) = context.getSystemService(WifiManager::class.java).isWifiEnabled

    /** Ждёт подключения к Wi-Fi не дольше [timeoutMs]; возвращает, подключён ли он. */
    fun awaitConnected(context: Context, timeoutMs: Long): Boolean {
        if (isConnected(context)) return true
        awaitWifi(context, timeoutMs) { true }
        return isConnected(context)
    }

    /** Ждёт не дольше [timeoutMs] сети Wi-Fi, подходящей под [accept]; onAvailable приходит и для уже подключённых. */
    private fun awaitWifi(context: Context, timeoutMs: Long, accept: (Network) -> Boolean): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (accept(network)) latch.countDown()
            }
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback
        )
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
    }
}

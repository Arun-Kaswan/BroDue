package com.abk.brodue

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object ConnectivityMonitor {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var appContext: Context? = null

    @Volatile
    var online: Boolean = true
        private set

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        online = NetworkUtils.isOnline(ctx)
        runCatching {
            val cm = appContext!!.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    post { setOnline(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    post { setOnline(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) }
                }

                override fun onLost(network: Network) {
                    post { setOnline(false) }
                }
            })
        }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(online) }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun setOnline(value: Boolean) {
        if (online == value) return
        online = value
        listeners.forEach { runCatching { it(value) } }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
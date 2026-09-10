package com.abk.brodue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast

object NetworkUtils {

    @Volatile
    var offlineActive = false

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun requireOnline(context: Context): Boolean {
        if (isOnline(context)) return true
        Toast.makeText(context, R.string.no_internet_save, Toast.LENGTH_SHORT).show()
        return false
    }

    // Re-checks connectivity; on success runs onOnline (e.g. exit offline mode),
    // otherwise shows a "No internet" alert
    fun retryConnection(context: Context, onOnline: (() -> Unit)? = null): Boolean {
        return if (isOnline(context)) {
            onOnline?.invoke()
            true
        } else {
            Toast.makeText(context, R.string.no_internet, Toast.LENGTH_SHORT).show()
            false
        }
    }
}
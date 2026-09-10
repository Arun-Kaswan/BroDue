package com.abk.brodue

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class BroDueApp : Application() {
    private var resumedCount = 0
    private val bgHandler = Handler(Looper.getMainLooper())
    private var bgCheck: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        FirebaseConfig.init(this)
        ConnectivityMonitor.init(this)
        // Init currency symbol from local prefs (Firestore offline cache enabled by default)
        Formatters.setCurrencySymbol(CurrencyManager.getSymbol(this))
        // Delete the active QR invite when the app goes to the background
        // (minimize) - QrSheet.onDismiss only covers in-app closes.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedCount++
                bgCheck?.let { bgHandler.removeCallbacks(it) }
                bgCheck = null
            }

            override fun onActivityPaused(activity: Activity) {
                resumedCount = (resumedCount - 1).coerceAtLeast(0)
                bgCheck?.let { bgHandler.removeCallbacks(it) }
                bgCheck = Runnable {
                    if (resumedCount == 0) QrSession.deleteActive()
                }
                // Delayed so rotations/quick switches don't count as background
                bgHandler.postDelayed(bgCheck!!, 700)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

package com.abk.brodue

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * Firebase bootstrap. Full builds configure via app/google-services.json
 * (generated from Firebase Console for package com.abk.brodue).
 * Offline builds (cloned repos without that file) skip this entirely and
 * run fully local - every cloud call site checks BuildConfig.OFFLINE_MODE.
 */
object FirebaseConfig {
    fun init(context: Context) {
        if (BuildConfig.OFFLINE_MODE) return
        // FirebaseApp auto-initializes from google-services.json — no manual options needed.
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
    }
}
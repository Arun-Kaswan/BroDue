package com.abk.brodue

import com.google.firebase.auth.FirebaseAuth

object UserDb {
    // Offline builds have no FirebaseApp - getInstance() would throw, so
    // every access is guarded and degrades to signed-out (local-only mode).
    fun uid(): String? = try {
        if (BuildConfig.OFFLINE_MODE) null
        else FirebaseAuth.getInstance().currentUser?.uid
    } catch (_: Exception) {
        null
    }

    fun requireUid(): String = uid() ?: throw IllegalStateException("Not signed in")
}

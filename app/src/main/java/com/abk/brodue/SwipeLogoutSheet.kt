package com.abk.brodue

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

class SwipeLogoutSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_swipe_logout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val swipeView = view.findViewById<SwipeLogoutView>(R.id.swipeLogout)
        swipeView.onSwipeComplete = {
            val ctx = requireContext()
            // Stop all Firebase listeners first so no callback can fire during teardown
            try {
                (activity as? MainActivity)?.prepareLogout()
            } catch (_: Exception) {}
            // Perform logout (drop this device's push token first, while authed)
            try {
                ShareSync.unregisterPushToken(ctx)
            } catch (_: Exception) {}
            try {
                FirebaseAuth.getInstance().signOut()
            } catch (_: Exception) {}
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                GoogleSignIn.getClient(ctx, gso).signOut()
            } catch (_: Exception) {}
            // Dismiss safely and navigate after the sheet is fully gone
            dismissAllowingStateLoss()
            Handler(Looper.getMainLooper()).post {
                try {
                    val intent = Intent(ctx, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val TAG = "SwipeLogoutSheet"
        fun newInstance(): SwipeLogoutSheet = SwipeLogoutSheet()
    }
}

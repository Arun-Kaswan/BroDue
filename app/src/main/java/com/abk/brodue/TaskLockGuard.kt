package com.abk.brodue

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

// Gates in-app tasks behind fingerprint when App Lock + the matching toggle are enabled.
// Keys: "add", "edit_entry", "archive", "edit_person"
object TaskLockGuard {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun enabledFor(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("app_lock_enabled", false) &&
            prefs(ctx).getBoolean("task_lock_$key", key == "app")

    fun gate(activity: FragmentActivity, key: String, title: String, onAllowed: () -> Unit) {
        if (!enabledFor(activity, key)) {
            onAllowed()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAllowed()
                }
                // On failure/cancel simply do nothing - user can retry the action
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Confirm to continue")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        try {
            prompt.authenticate(info)
        } catch (_: Exception) {
            // No biometric hardware available - fall through so tasks stay usable
            onAllowed()
        }
    }
}

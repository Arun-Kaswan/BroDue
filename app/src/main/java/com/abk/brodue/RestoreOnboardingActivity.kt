package com.abk.brodue

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// Fresh-install onboarding: restore from a backup file or skip.
// Shown only when there is no local data at all.
class RestoreOnboardingActivity : AppCompatActivity() {

    private val pickBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) readBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeBlackIcons()
        setContentView(R.layout.activity_restore_onboarding)

        // System-bars insets: content clears the status bar, the skip
        // button clears the navigation bar (no overlaps, no dead space)
        val density = resources.displayMetrics.density
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.rootOnboarding)
        ) { v, insets ->
            val sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                (12 * density).toInt() + sb.top,
                v.paddingRight,
                (12 * density).toInt()
            )
            insets
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.btnOnboardingSkip)
        ) { v, insets ->
            val sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.bottomMargin = sb.bottom
                v.layoutParams = it
            }
            insets
        }

        findViewById<View>(R.id.btnOnboardingSkip).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            goNext()
        }
        findViewById<View>(R.id.btnSelectBackup).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            try {
                pickBackupLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readBackup(uri: Uri) {
        Toast.makeText(this, R.string.reading_backup, Toast.LENGTH_SHORT).show()
        BackupManager.loadBackup(this, uri) { data, needsPin, err ->
            runOnUiThread {
                if (needsPin) {
                    PinSheet.showVerify(
                        this,
                        "Enter PIN",
                        "Enter the 6-digit PIN used for this backup",
                        onConfirmed = { pin ->
                            BackupManager.loadWithPin(this, uri, pin) { d, e2 ->
                                runOnUiThread {
                                    if (d == null) {
                                        Toast.makeText(
                                            this, e2 ?: getString(R.string.save_error),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@runOnUiThread
                                    }
                                    applyBackup(d)
                                }
                            }
                        }
                    )
                    return@runOnUiThread
                }
                if (data == null) {
                    Toast.makeText(this, err ?: getString(R.string.save_error), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                applyBackup(data)
            }
        }
    }

    // Fresh install: nothing local to protect, replace directly
    private fun applyBackup(data: Map<String, Any?>) {
        Toast.makeText(this, R.string.restoring, Toast.LENGTH_SHORT).show()
        BackupManager.applyWithRules(this, data, "replace") { ok ->
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, R.string.data_restored, Toast.LENGTH_SHORT).show()
                    // A restored backup may carry the saved currency: adopt
                    // it so the currency page is skipped for restore users
                    val saved = LocalStore.getSetting(this, "currency") as? String
                    if (!saved.isNullOrBlank() && !CurrencyManager.hasLocalCurrency(this)) {
                        CurrencyManager.currencies.find { it.symbol == saved }?.let {
                            CurrencyManager.saveLocal(this, it)
                            Formatters.setCurrencySymbol(it.symbol)
                        }
                    }
                    goNext()
                } else {
                    Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Backup page -> currency page (first run) -> app
    private fun goNext() {
        val next = if (!CurrencyManager.hasLocalCurrency(this)) {
            Intent(this, CurrencyOnboardingActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        startActivity(next)
        finish()
    }
}

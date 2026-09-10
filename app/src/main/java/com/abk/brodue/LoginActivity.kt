package com.abk.brodue

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var credentialManager: CredentialManager
    private var loginSheet: BottomSheetDialog? = null
    private var sheetProgress: View? = null
    private var sheetStatus: TextView? = null
    private var sheetGoogleBtn: View? = null

    // Fallback for old devices / no Credential Manager
    private val signInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account)
        } catch (e: Exception) {
            setLoading(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeBlackIcons()
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.btnGetStarted)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
            // Keep bottom margin for gesture navigation and leave extra space under button
            val lp = v.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp?.bottomMargin = 48 + sb.bottom + 16
            v.layoutParams = lp
            insets
        }

        // Offline builds (cloned repos): no account system. Get Started goes
        // straight to currency onboarding, then the app - fully local.
        if (BuildConfig.OFFLINE_MODE) {
            findViewById<View>(R.id.btnGetStarted).setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                goToOnboarding()
            }
            startLoginAnimations()
            return
        }

        auth = FirebaseAuth.getInstance()

        // If already signed in, go to Main
        if (auth.currentUser != null) {
            goToMain()
            return
        }

        credentialManager = CredentialManager.create(this)

        // Build GoogleSignInClient (fallback for old devices)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        googleWebClientId()?.let { gsoBuilder.requestIdToken(it) }
        googleClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        findViewById<View>(R.id.btnGetStarted).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showLoginSheet()
        }

        startLoginAnimations()
    }

    private fun startLoginAnimations() {
        // Logo pop
        val logo = findViewById<View>(R.id.loginLogo)
        logo.scaleX = 0.7f
        logo.scaleY = 0.7f
        logo.alpha = 0f
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).setStartDelay(100).start()

        // Title
        val title = findViewById<View>(R.id.loginTitle)
        title.alpha = 0f
        title.translationY = 16f
        title.animate().alpha(1f).translationY(0f).setDuration(600).setStartDelay(250).setInterpolator(UiUtils.EASE_OUT).start()

        // Tagline
        findViewById<View>(R.id.loginTagline)?.let {
            it.animate().alpha(0.7f).setDuration(600).setStartDelay(450).start()
        }

        // Branding container subtle fade
        findViewById<View>(R.id.loginBranding)?.let {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(400).setStartDelay(80).start()
        }

        // Floating symbols - subtle float
        fun floatView(id: Int, dy: Float, dur: Long, delay: Long) {
            val v = findViewById<View>(id) ?: return
            android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, dy, 0f).apply {
                duration = dur
                startDelay = delay
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
            android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, v.rotation, v.rotation + 6f, v.rotation - 4f, v.rotation).apply {
                duration = dur + 400
                startDelay = delay
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
        floatView(R.id.floatDollar, 12f, 3600L, 400L)
        floatView(R.id.floatEuro, -10f, 3400L, 700L)
        floatView(R.id.floatYen, 13f, 3800L, 200L)

        // Big animated circles - top corners overlapping edges & each other, bottom-right cut off
        fun pulseCircle(id: Int, fromAlpha: Float, toAlpha: Float, scale: Float, dur: Long, delay: Long) {
            findViewById<View>(id)?.let { v ->
                android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, fromAlpha, toAlpha, fromAlpha).apply {
                    duration = dur
                    startDelay = delay
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, scale, 1f).apply {
                    duration = dur + 200
                    startDelay = delay
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, scale, 1f).apply {
                    duration = dur + 200
                    startDelay = delay
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
                // slow drift - even more position change for top circles
                android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -18f, 0f).apply {
                    duration = dur + 900
                    startDelay = delay
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
                android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, 12f, 0f).apply {
                    duration = dur + 1100
                    startDelay = delay + 300
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
            }
        }
        // Two large circles at top overlapping each other & screen edges, one larger at bottom-right
        pulseCircle(R.id.bigCircleTopLeft, 0.30f, 0.38f, 1.03f, 5200L, 0L)
        pulseCircle(R.id.bigCircleTopRight, 0.16f, 0.22f, 1.03f, 5400L, 500L)
        pulseCircle(R.id.bigCircleBottomRight, 0.35f, 0.44f, 1.04f, 5600L, 900L)

        // Get Started button slide up
        val btn = findViewById<View>(R.id.btnGetStarted)
        btn.alpha = 0f
        btn.translationY = 24f
        btn.animate().alpha(1f).translationY(0f).setDuration(600).setStartDelay(800).setInterpolator(UiUtils.EASE_OUT).start()

        // Footer fade
        findViewById<View>(R.id.loginFooter)?.let {
            it.animate().alpha(0.65f).setDuration(600).setStartDelay(1100).start()
        }
    }

    private fun showLoginSheet() {
        if (loginSheet?.isShowing == true) return
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_login, null)
        sheet.setContentView(view)
        // Slide animation for drawer
        sheet.window?.setWindowAnimations(R.style.SheetSlideAnimation)
        sheetProgress = view.findViewById(R.id.progressLogin)
        sheetStatus = null
        sheetGoogleBtn = view.findViewById(R.id.btnGoogleSignIn)

        view.findViewById<View>(R.id.btnGoogleSignIn).setOnClickListener {
            if (!NetworkUtils.isOnline(this)) {
                return@setOnClickListener
            }
            if (googleWebClientId() == null) return@setOnClickListener
            setLoading(true)
            launchGoogleSignInWithCredentialManager()
        }

        sheet.setOnDismissListener {
            // Reset loading state when dismissed
            setLoading(false)
        }

        loginSheet = sheet
        sheet.show()
    }

    private fun launchGoogleSignInWithCredentialManager() {
        val webClientId = googleWebClientId()

        if (webClientId == null) {
            // Fallback to old flow if no web client id
            signInLauncher.launch(googleClient.signInIntent)
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    context = this@LoginActivity,
                    request = request
                )
                val credential = result.credential
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val googleIdToken = googleIdTokenCredential.idToken
                    firebaseAuthWithGoogleIdToken(googleIdToken)
                } else {
                    // Unexpected credential type - fallback to old
                    signInLauncher.launch(googleClient.signInIntent)
                }
            } catch (e: GetCredentialCancellationException) {
                // User canceled the drawer - just reset loading
                setLoading(false)
            } catch (e: NoCredentialException) {
                // No Google accounts found - fallback to old flow which will show account picker
                try {
                    signInLauncher.launch(googleClient.signInIntent)
                } catch (_: Exception) {
                    setLoading(false)
                }
            } catch (e: GetCredentialException) {
                // Other error - fallback
                try {
                    signInLauncher.launch(googleClient.signInIntent)
                } catch (_: Exception) {
                    setLoading(false)
                }
            } catch (e: Exception) {
                setLoading(false)
            }
        }
    }

    private fun firebaseAuthWithGoogleIdToken(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                Toast.makeText(this, "Welcome ${auth.currentUser?.displayName}", Toast.LENGTH_SHORT).show()
                loginSheet?.dismiss()
                goToMain()
            }
            .addOnFailureListener {
                setLoading(false)
            }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount?) {
        if (account == null || account.idToken == null) {
            setLoading(false)
            return
        }
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                Toast.makeText(this, "Welcome ${auth.currentUser?.displayName}", Toast.LENGTH_SHORT).show()
                loginSheet?.dismiss()
                goToMain()
            }
            .addOnFailureListener {
                setLoading(false)
            }
    }

    // Runtime-safe probe for google-services values (compile-time R refs to
    // them break offline builds, where the plugin never generates them).
    private fun googleWebClientId(): String? {
        return try {
            val id = resources.getIdentifier("default_web_client_id", "string", packageName)
            if (id == 0) return null
            getString(id).takeIf { it.isNotBlank() && !it.startsWith("TODO") }
        } catch (_: Exception) {
            null
        }
    }

    private fun goToOnboarding() {
        // Offline: currency first (if unset), else straight into the app.
        // No login, no restore - this build is local-only by design.
        if (!CurrencyManager.hasLocalCurrency(this)) {
            startActivity(Intent(this, CurrencyOnboardingActivity::class.java))
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun goToMain() {
        // Local-first: currency comes from local storage; missing selection
        // is handled downstream (restore page -> currency onboarding page)
        Formatters.setCurrencySymbol(CurrencyManager.getSymbol(this))
        // Fresh install (no local data at all): offer backup restore first
        val next = if (!LocalStore.hasLocalData(this)) {
            Intent(this, RestoreOnboardingActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        startActivity(next)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        sheetProgress?.visibility = if (loading) View.VISIBLE else View.GONE
        sheetGoogleBtn?.isEnabled = !loading
        sheetGoogleBtn?.alpha = if (loading) 0.6f else 1f
    }

    private fun showError(msg: String) {
        // No-op: errors are not shown per design
    }
}

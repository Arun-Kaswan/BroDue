package com.abk.brodue

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import android.view.animation.PathInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private enum class SortMode(val iconRes: Int, val labelRes: Int) {
        AZ(R.drawable.ic_sort_az, R.string.sort_by_name),
        VALUE(R.drawable.ic_sort_value, R.string.sort_by_price),
        RECENT(R.drawable.ic_sort_recent, R.string.sort_by_time)
    }

    private lateinit var rvPeople: RecyclerView
    private lateinit var rvRecords: RecyclerView
    private lateinit var rvLogs: RecyclerView
    private lateinit var homeContent: View  
    private lateinit var logsContent: View
    private lateinit var detailPanel: View
    private lateinit var bottomNav: View
    private lateinit var scrimView: View
    private lateinit var btnNavHome: MaterialButton
    private lateinit var skeletonLogs: View
    private lateinit var skeletonLogCard: View
    private lateinit var btnNavLogs: MaterialButton
    private lateinit var progress: View
    private lateinit var skeletonList: View
    private lateinit var emptyState: View
    private lateinit var recordsEmpty: View
    private lateinit var logsEmpty: View
    private lateinit var tvNetAmount: TextView
    private lateinit var tvLogNetAmount: TextView
    private val adapter = PersonAdapter(
        onItemClick = { person -> openPersonDetail(person) },
        onItemLongClick = { person ->
            openNetBalanceFor(person)
            true
        }
    )
    private val transactionAdapter = TransactionAdapter { tx ->  openRecordDetail(tx) }
    private val transactionHeaderAdapter = SectionHeaderAdapter(R.string.money_records)

    private val logsAdapter = LogsAdapter { entry ->
        // latestTx previews carry no note/history: open the person instead
        // of a half-empty record (gap fetch fills it on open).
        if (previewTxIds.contains(entry.tx.id)) {
            peopleList.find { it.id == entry.personId }?.let { openPersonDetail(it) }
            return@LogsAdapter
        }
        showSheet(
            RecordDetailSheet.newInstance(entry.tx, entry.personId, entry.personName),
            RecordDetailSheet.TAG
        )
    }

    // txIds currently rendered from latestTx previews (tap -> person)
    private var previewTxIds = mutableSetOf<String>()

    private var logsEntriesCache = mutableListOf<LogEntry>()
    private var logsListLoaded = false
    // Set when a refresh skips the cache rebuild (logs hidden). The next
    // navigation to logs rebuilds first - the list is never stale on screen.
    private var logsCacheDirty = false

    private fun rebuildLogsCache() {
        val names = mutableMapOf<String, String>()
        peopleList.forEach { names[it.id] = it.name }
        val txs = LocalStore.transactions(this)
        val entries = mutableListOf<LogEntry>()
        val tKeys = txs.keys()
        while (tKeys.hasNext()) {
            val txId = tKeys.next()
            val t = txs.optJSONObject(txId) ?: continue
            val pid = t.optString("personId", "")
            val name = names[pid] ?: continue
            entries.add(
                LogEntry(
                    pid, name,
                    Transaction(
                        txId,
                        t.optString("category", "General"),
                        t.optLong("amount", 0L),
                        t.optString("type", "gave"),
                        t.optString("note", ""),
                        t.optLong("createdAt", 0L),
                        t.optLong("savedAt", 0L),
                        t.optBoolean("unseen", false)
                    )
                )
            )
        }
        // Zero-read previews: each synced person's newest entry rides its
        // person doc (worker-maintained latestTx). Merged only where the
        // full tx isn't cached; full entries always win. Preview taps open
        // the person (gap fetch fills history on open).
        previewTxIds = mutableSetOf()
        try {
            val haveTx = entries.map { it.tx.id }.toHashSet()
            val peopleObj = LocalStore.people(this)
            val pk = peopleObj.keys()
            while (pk.hasNext()) {
                val pid = pk.next()
                val po = peopleObj.optJSONObject(pid) ?: continue
                if (po.optBoolean("archived", false)) continue
                val lt = po.optJSONObject("latestTx") ?: continue
                val tid = lt.optString("txId", "")
                if (tid.isBlank() || tid in haveTx) continue
                val personName = names[pid] ?: continue
                previewTxIds.add(tid)
                val at = lt.optLong("savedAt", 0L)
                entries.add(
                    LogEntry(
                        pid, personName,
                        Transaction(
                            tid,
                            lt.optString("category", "General"),
                            lt.optLong("amount", 0L),
                            lt.optString("type", "gave"),
                            "",
                            at,
                            at,
                            false
                        )
                    )
                )
            }
        } catch (_: Exception) {}
        logsEntriesCache = entries.toMutableList()
        logsCacheDirty = false
    }

    private fun ensureLogsCache() {
        if (logsCacheDirty || logsEntriesCache.isEmpty()) rebuildLogsCache()
    }
    private var lastWrittenCount: Int? = null
    private var listLoaded = false
    private var sortMode = SortMode.AZ
    private var sortDescending = false
    private var peopleList = emptyList<Person>()
    private val prefs by lazy { getSharedPreferences("app_settings", MODE_PRIVATE) }
    private var currentPerson: Person? = null
    private var txListCache = mutableListOf<Transaction>()
    private var logsGraphValues = LongArray(0)
    private var logsGraphLastDate = 0L
    private var currentNet = 0L
    private var currentReceived = 0L
    private var currentGave = 0L
    private var currentReceivedCount = 0
    private var currentGaveCount = 0
    private var logsDisplayMode: String = "net"
    // Entry-list source filter: all | local | cloud. List only -
    // the net card + drawer totals are never affected.
    private var logsSourceFilter: String = "all"
    // Logs totals, per currency symbol: [net, pos, neg, sendCnt, recvCnt, sendAmt, recvAmt].
    // The logs* fields below always hold the SELECTED currency's slice.
    private var logsTotalsByCurrency: Map<String, LongArray> = emptyMap()
    private var logsCurrency: String = "₹"
    private var logsCurrencies: List<String> = emptyList()
    private var logsPosTotal = 0L
    private var logsNegTotal = 0L
    private var logsSendCount = 0
    private var lastLogsNetForAnim = 0L
    // (pendingLogsAnim deferral removed: logs card rolls live like detail)
    private var logsFirstLoad = true
    private lateinit var settingsPanel: View
    private var settingsPanelBusy = false
    private lateinit var appearancePanel: View
    private var appearancePanelBusy = false
    private lateinit var aboutPanel: View
    private var aboutPanelBusy = false
    private lateinit var accountPanel: View
    private var accountPanelBusy = false
    private lateinit var systemUpdatePanel: View
    private var systemUpdatePanelBusy = false
    private lateinit var currencyPanel: View
    private var currencyPanelBusy = false
    private var currencyForced = false
    private lateinit var backupPanel: View
    private var backupPanelBusy = false
    private lateinit var settingsScrim: View
    private lateinit var accountScrim: View
    private lateinit var aboutScrim: View
    private var logsReceiveCount = 0
    private var logsSendAmount = 0L
    private var logsReceiveAmount = 0L
    private var logsTotalNet = 0L
    private lateinit var tvLogNetLabel: TextView
    private var panelRadius = 0f
    private var radiusAnimator: ValueAnimator? = null
    private val maxRadiusPx: Float get() = 25 * resources.displayMetrics.density
    private lateinit var backCallback: OnBackPressedCallback
    private var panelBusy = false
    private var onLogsPage = false
    private var pageSwitching = false
    private val homeToLogsInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    private lateinit var btnAdd: MaterialButton
    private var offlineMode = false
    private var offlineSnapshot: JSONObject? = null
    // Data is local-first now - offline mode is obsolete (code kept intact)
    private val OFFLINE_FEATURE_ENABLED = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectivityListener: ((Boolean) -> Unit)? = null
    private var firstNetworkEvent = true
    private var pendingOfflineRunnable: Runnable? = null
    private var archivedView = false
    private var receivedBg: ColorStateList? = null
    private var receivedIcon: ColorStateList? = null
    private var receivedStroke: ColorStateList? = null
    private var receivedElevation = 0f
    private var gaveBg: ColorStateList? = null
    private var gaveIcon: ColorStateList? = null
    private var gaveStroke: ColorStateList? = null
    private var gaveElevation = 0f

    private fun crossfadeButton(button: MaterialButton, swap: () -> Unit) = UiUtils.crossfade(button, swap)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Public release: require Google Sign-In (offline builds skip this -
        // no account system, fully local).
        if (!BuildConfig.OFFLINE_MODE &&
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null
        ) {
            // QR join link arrived while logged out - stash it for after login
            intent?.data?.getQueryParameter("code")?.takeIf { it.length == 6 }?.let {
                prefs.edit().putString("pending_join_code", it).apply()
            }
            startActivity(android.content.Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // One-time decimal migration (must run before any data is read)
        DecimalMigration.run(this)
        // Backfill per-person receive/send aggregates (self-healing no-op afterwards)
        PersonTotals.ensureAll(this)
        // Pin legacy default-following persons to explicit currencies
        CurrencyManager.pinLegacyDefaults(this)
        // One-time server recompute of cloud money aggregates (each synced
        // person once, ever; capped per launch by the worker rate limit)
        ShareSync.healAggregatesOnce(this)
        // Push notifications: register this device's FCM token + ask the
        // Android 13+ notification permission once per install
        ShareSync.refreshPushToken(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("push_perm_asked", false)
        ) {
            prefs.edit().putBoolean("push_perm_asked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4411)
        }
        // One-time: entry dots belong to synced persons only - clear strays
        try {
            val txs = LocalStore.transactions(this)
            var changed = false
            val keys = txs.keys()
            while (keys.hasNext()) {
                val t = txs.optJSONObject(keys.next()) ?: continue
                if (t.optBoolean("unseen", false) &&
                    !ShareSync.isSynced(this, t.optString("personId", ""))
                ) {
                    t.put("unseen", false)
                    changed = true
                }
            }
            if (changed) LocalStore.persist(this)
        } catch (_: Exception) {}
        // Local-first currency check: missing selection detours through
        // the onboarding currency page (CurrencyChoiceActivity retired)
        Formatters.setCurrencySymbol(CurrencyManager.getSymbol(this))
        if (!CurrencyManager.hasLocalCurrency(this)) {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    startActivity(Intent(this, CurrencyOnboardingActivity::class.java))
                    finish()
                }
            }, 600)
        }

        // App Lock - will prompt in onResume after UI ready
        edgeToEdgeBlackIcons()
        setContentView(R.layout.activity_main)
        // Track screen off so non-instant lock mode knows the device was locked
        val screenFilter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, screenFilter)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            homeContent.setPadding(sb.left, sb.top, sb.right, 0)
            logsContent.setPadding(sb.left, sb.top, sb.right, 0)
            val navBarInset = (18 * resources.displayMetrics.density).toInt() + sb.bottom
            (bottomNav.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = navBarInset
            // Detail page: content extends BEHIND system nav bar
            detailPanel.setPadding(sb.left, sb.top, sb.right, 0)
            // Keep the send/receive buttons + last record above the bar
            findViewById<View>(R.id.detailBottomBar)?.let { bb ->
                val base = (12 * resources.displayMetrics.density).toInt()
                bb.setPadding(base, base, base, base + sb.bottom)
            }
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvRecords)?.let { rv ->
                rv.setPadding(
                    rv.paddingLeft, rv.paddingTop, rv.paddingRight,
                    (88 * resources.displayMetrics.density).toInt() + sb.bottom
                )
            }
            if (::settingsPanel.isInitialized) settingsPanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            if (::appearancePanel.isInitialized) appearancePanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            if (::aboutPanel.isInitialized) aboutPanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            if (::accountPanel.isInitialized) accountPanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            if (::systemUpdatePanel.isInitialized) systemUpdatePanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            if (::currencyPanel.isInitialized) currencyPanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            if (::backupPanel.isInitialized) backupPanel.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        homeContent = findViewById(R.id.homeContent)
        logsContent = findViewById(R.id.logsContent)
        detailPanel = findViewById(R.id.detailPanel)
        bottomNav = findViewById(R.id.bottomNav)
        scrimView = findViewById(R.id.scrimView)
        detailPanel.clipToOutline = true
        detailPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (detailPanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setPanelProgress(it) }
            onSwipeEnd = { close ->
                if (close) animatePanelTo(open = false, duration = 280L)
                else animatePanelTo(open = true)
            }
        }

        settingsPanel = findViewById(R.id.settingsPanel)
        settingsPanel.clipToOutline = true
        settingsPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (settingsPanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setSettingsProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateSettingsTo(open = false, duration = 280L)
                else animateSettingsTo(open = true)
            }
        }
        setupSettingsList()

        settingsScrim = findViewById(R.id.settingsScrim)
        settingsScrim.visibility = View.GONE
        settingsScrim.setOnClickListener {
            when {
                accountPanel.visibility == View.VISIBLE -> closeAccount()
                aboutPanel.visibility == View.VISIBLE -> closeAbout()
                appearancePanel.visibility == View.VISIBLE -> closeAppearance()
                systemUpdatePanel.visibility == View.VISIBLE -> closeSystemUpdate()
                currencyPanel.visibility == View.VISIBLE -> closeCurrency()
                backupPanel.visibility == View.VISIBLE -> closeBackup()
            }
        }
        accountScrim = findViewById(R.id.accountScrim)
        accountScrim.visibility = View.GONE
        accountScrim.setOnClickListener { closeAccount() }

        appearancePanel = findViewById(R.id.appearancePanel)
        appearancePanel.clipToOutline = true
        appearancePanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (appearancePanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setAppearanceProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateAppearanceTo(open = false, duration = 280L)
                else animateAppearanceTo(open = true)
            }
        }
        setupAppearanceList()

        aboutPanel = findViewById(R.id.aboutPanel)
        aboutPanel.clipToOutline = true
        aboutPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (aboutPanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setAboutProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateAboutTo(open = false, duration = 280L)
                else animateAboutTo(open = true)
            }
        }
        setupAbout()

        accountPanel = findViewById(R.id.accountPanel)
        accountPanel.clipToOutline = true
        accountPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (accountPanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setAccountProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateAccountTo(open = false, duration = 280L)
                else animateAccountTo(open = true)
            }
        }
        setupAccount()

        accountScrim = findViewById(R.id.accountScrim)
        accountScrim.visibility = View.GONE
        accountScrim.setOnClickListener { closeAccount() }

        systemUpdatePanel = findViewById(R.id.systemUpdatePanel)
        systemUpdatePanel.clipToOutline = true
        systemUpdatePanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (systemUpdatePanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setSystemUpdateProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateSystemUpdateTo(open = false, duration = 280L)
                else animateSystemUpdateTo(open = true)
            }
        }
        setupSystemUpdate()

        currencyPanel = findViewById(R.id.currencyPanel)
        currencyPanel.clipToOutline = true
        currencyPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (currencyPanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setCurrencyProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateCurrencyTo(open = false, duration = 280L)
                else animateCurrencyTo(open = true)
            }
        }
        setupCurrency()

        backupPanel = findViewById(R.id.backupPanel)
        backupPanel.clipToOutline = true
        backupPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, panelRadius)
            }
        }
        (backupPanel as SwipeCloseLayout).apply {
            onSwipeProgress = { setBackupProgress(it) }
            onSwipeEnd = { close ->
                if (close) animateBackupTo(open = false, duration = 280L)
                else animateBackupTo(open = true)
            }
        }
        setupBackup()

        updateSwipeEnabled()
        // Ensure settings/appearance/about/account panels get correct window insets (status bar) like detailPanel
        ViewCompat.requestApplyInsets(findViewById(R.id.main))

        rvPeople = findViewById(R.id.rvPeople)
        rvPeople.layoutManager = LinearLayoutManager(this)
        rvPeople.itemAnimator = DefaultItemAnimator().apply {
            // Rebinds (e.g. red-dot flips) apply instantly; insert/remove still animate
            supportsChangeAnimations = false
        }
        rvPeople.adapter = adapter

        progress = findViewById(R.id.progress)
        skeletonList = findViewById(R.id.skeletonList)
        skeletonLogs = findViewById(R.id.skeletonLogs)
        skeletonLogCard = findViewById(R.id.skeletonLogCard)
        tvNetAmount = findViewById(R.id.tvNetAmount)
        tvLogNetAmount = findViewById(R.id.tvLogNetAmount)
        tvLogNetLabel = findViewById(R.id.tvLogNetLabel)
        // Instant start: background pushes keep the cache fresh, so cached
        // data renders immediately with no skeleton/shimmer. True first runs
        // (nothing cached) keep the loading placeholders. Pulls still run,
        // silently, as the correctness backstop for missed pushes.
        instantStart = LocalStore.hasLocalData(this)
        if (instantStart) {
            skeletonList.isVisible = false
            skeletonLogs.isVisible = false
            skeletonLogCard.isVisible = false
            tvLogNetAmount.isVisible = true
            tvLogNetLabel.isVisible = true
            adapter.amountsLoading = false
        } else {
            // Home skeleton right away; hidden when data arrives or offline snapshot populates
            showSkeleton(8)
        }
        progress.isVisible = false
        emptyState = findViewById(R.id.emptyState)
        recordsEmpty = findViewById(R.id.recordsEmpty)
        logsEmpty = findViewById(R.id.logsEmpty)
        logsDisplayMode = prefs.getString("logs_display_mode", "net") ?: "net"
        logsCurrency = prefs.getString("logs_currency", null)
            ?: CurrencyManager.getSymbol(this)
        homeSourceFilter = prefs.getString("home_source_filter", "all") ?: "all"
        findViewById<MaterialButton>(R.id.btnTabHomeAll).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectHomeSourceFilter("all")
        }
        findViewById<MaterialButton>(R.id.btnTabHomeLocal).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectHomeSourceFilter("local")
        }
        findViewById<MaterialButton>(R.id.btnTabHomeCloud).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectHomeSourceFilter("cloud")
        }
        styleHomeSourceTabs()
        logsSourceFilter = prefs.getString("logs_source_filter", "all") ?: "all"
        val btnTabAll = findViewById<MaterialButton>(R.id.btnTabLogsAll)
        val btnTabLocal = findViewById<MaterialButton>(R.id.btnTabLogsLocal)
        val btnTabCloud = findViewById<MaterialButton>(R.id.btnTabLogsCloud)
        btnTabAll.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectLogsSourceFilter("all")
        }
        btnTabLocal.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectLogsSourceFilter("local")
        }
        btnTabCloud.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectLogsSourceFilter("cloud")
        }
        styleLogsSourceTabs()
        // Offline builds: no sync/share/join exists - the Local/Cloud
        // filters are meaningless, so both filter bars go away entirely.
        if (BuildConfig.OFFLINE_MODE) {
            findViewById<View>(R.id.filterHomeRow)?.visibility = View.GONE
            findViewById<View>(R.id.filterLogsRow)?.visibility = View.GONE
        }

        rvRecords = findViewById(R.id.rvRecords)
        rvRecords.layoutManager = LinearLayoutManager(this)
        // No per-item animator: repeated submits (open/external refreshes) would
        // otherwise blink the header/rows - DiffUtil still handles content changes
        rvRecords.itemAnimator = null
        rvRecords.adapter = ConcatAdapter(transactionHeaderAdapter, transactionAdapter)

        rvLogs = findViewById<RecyclerView>(R.id.rvLogs)
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.itemAnimator = null
        rvLogs.adapter = logsAdapter

        // Lazy feed pagination: near the bottom of an open synced person,
        // page the next 25 server entries (no-op when exhausted/offline).
        rvRecords.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                try {
                    val pid = currentPerson?.id ?: return
                    if (detailPanel.visibility != View.VISIBLE) return
                    if (offlineMode || archivedView) return
                    if (!NetworkUtils.isOnline(this@MainActivity)) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val total = rv.adapter?.itemCount ?: 0
                    if (lm.findLastVisibleItemPosition() < total - 4) return
                    if (!ShareSync.feedHasMore(pid)) return
                    fetchFeedChunk(pid, false)
                } catch (_: Exception) {}
            }
        })

        findViewById<View>(R.id.btnUnlock)?.setOnClickListener { showAppLockPrompt() }

        btnAdd = findViewById(R.id.btnAdd)
        btnAdd.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            openAddSheet()
        }

        setupProfileButton()

        sortMode = try {
            SortMode.valueOf(prefs.getString("sort_mode", "AZ") ?: "AZ")
        } catch (_: IllegalArgumentException) {
            SortMode.AZ
        }
        sortDescending = prefs.getBoolean("sort_descending", false)
        val btnOrder = findViewById<MaterialCardView>(R.id.btnOrder)
        val orderIcon = findViewById<ImageView>(R.id.imgOrderIcon)
        // Segmented pair with Sort: same background/border as the filter
        // buttons, outer corners full pill, facing corners 75% smaller
        val density = resources.displayMetrics.density
        val outerR = 17f * density
        val innerR = 4f * density
        btnOrder.shapeAppearanceModel = btnOrder.shapeAppearanceModel.toBuilder()
            .setTopLeftCornerSize(outerR).setBottomLeftCornerSize(outerR)
            .setTopRightCornerSize(innerR).setBottomRightCornerSize(innerR)
            .build()
        val btnSortRes = findViewById<MaterialButton>(R.id.btnSort)
        btnSortRes.shapeAppearanceModel = btnSortRes.shapeAppearanceModel.toBuilder()
            .setTopLeftCornerSize(innerR).setBottomLeftCornerSize(innerR)
            .setTopRightCornerSize(outerR).setBottomRightCornerSize(outerR)
            .build()
        // Icon-only rotation; the segmented card background stays fixed
        orderIcon.rotation = if (sortDescending) 180f else 0f
        btnOrder.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            sortDescending = !sortDescending
            prefs.edit().putBoolean("sort_descending", sortDescending).apply()
            orderIcon.animate().rotation(if (sortDescending) 180f else 0f)
                .setDuration(250).start()
            applySort()
            rvPeople.smoothScrollToPosition(0)
        }
        findViewById<MaterialButton>(R.id.btnSort).setIconResource(sortMode.iconRes)
        findViewById<View>(R.id.btnSort).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            sortMode = when (sortMode) {
                SortMode.AZ -> SortMode.VALUE
                SortMode.VALUE -> SortMode.RECENT
                SortMode.RECENT -> SortMode.AZ
            }
            prefs.edit().putString("sort_mode", sortMode.name).apply()
            findViewById<MaterialButton>(R.id.btnSort).setIconResource(sortMode.iconRes)
            Toast.makeText(this, sortMode.labelRes, Toast.LENGTH_SHORT).show()
            applySort()
            rvPeople.smoothScrollToPosition(0)
        }

        btnNavHome = findViewById(R.id.btnNavHome)
        btnNavLogs = findViewById(R.id.btnNavLogs)
        selectNav(btnNavHome, btnNavLogs)
        btnNavHome.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (logsContent.visibility == View.VISIBLE) {
                showHomePage()
            } else {
                rvPeople.smoothScrollToPosition(0)
            }
        }
        btnNavLogs.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (logsContent.visibility != View.VISIBLE) {
                showLogsPage()
            } else {
                rvLogs.smoothScrollToPosition(0)
            }
        }

        findViewById<View>(R.id.btnDetailBack).setOnClickListener { closePersonDetail() }
        findViewById<View>(R.id.btnDetailInfo).setOnClickListener { openNetBalance() }
        findViewById<View>(R.id.leftGroupRow).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val person = currentPerson ?: return@setOnClickListener
            showSheet(LeftGroupSheet.newInstance(person.id), LeftGroupSheet.TAG)
        }
        findViewById<View>(R.id.syncPausedRow).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showSheet(SyncPausedSheet.newInstance(), SyncPausedSheet.TAG)
        }
        findViewById<View>(R.id.btnEditCopy).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val person = currentPerson ?: return@setOnClickListener
            showSheet(EditCopySheet.newInstance(person.id), EditCopySheet.TAG)
        }
        findViewById<View>(R.id.btnDetailSync).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val person = currentPerson ?: return@setOnClickListener
            if (!ShareSync.isSynced(this, person.id) && !NetworkUtils.isOnline(this)) {
                Toast.makeText(this, R.string.no_internet, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ShareSync.isSynced(this, person.id)) {
                showSheet(SyncShareSheet.newInstance(person.id), SyncShareSheet.TAG)
            } else {
                showSheet(SyncConfirmSheet.newInstance(person.id, person.name), SyncConfirmSheet.TAG)
            }
        }
        findViewById<View>(R.id.netCard).setOnClickListener { openGraph() }
        findViewById<View>(R.id.logsNetCard).setOnClickListener { openLogsGraph() }
        findViewById<View>(R.id.logsNetCard).setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showLogsDisplaySheet()
            true
        }
        findViewById<View>(R.id.btnReceived).setOnClickListener {
            if (offlineMode && !NetworkUtils.retryConnection(this) { exitOfflineMode() }) return@setOnClickListener
            openMoney("received")
        }
        findViewById<View>(R.id.btnGave).setOnClickListener {
            if (offlineMode && !NetworkUtils.retryConnection(this) { exitOfflineMode() }) return@setOnClickListener
            openMoney("gave")
        }
        findViewById<View>(R.id.wrapReceived).setOnClickListener {
            if (archivedView) return@setOnClickListener
            if (offlineMode) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (!NetworkUtils.retryConnection(this) { exitOfflineMode() }) return@setOnClickListener
            }
            openMoney("received")
        }
        findViewById<View>(R.id.wrapGave).setOnClickListener {
            if (archivedView) return@setOnClickListener
            if (offlineMode) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (!NetworkUtils.retryConnection(this) { exitOfflineMode() }) return@setOnClickListener
            }
            openMoney("gave")
        }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (panelBusy || settingsPanelBusy || appearancePanelBusy || aboutPanelBusy || accountPanelBusy || systemUpdatePanelBusy || currencyPanelBusy || backupPanelBusy) return
                when {
                    accountPanel.visibility == View.VISIBLE -> setAccountProgress(0f)
                    aboutPanel.visibility == View.VISIBLE -> setAboutProgress(0f)
                    appearancePanel.visibility == View.VISIBLE -> setAppearanceProgress(0f)
                    systemUpdatePanel.visibility == View.VISIBLE -> setSystemUpdateProgress(0f)
                    currencyPanel.visibility == View.VISIBLE -> setCurrencyProgress(0f)
                    backupPanel.visibility == View.VISIBLE -> setBackupProgress(0f)
                    settingsPanel.visibility == View.VISIBLE -> setSettingsProgress(0f)
                    detailPanel.visibility == View.VISIBLE -> setPanelProgress(0f)
                    onLogsPage -> setLogsBackProgress(0f)
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (panelBusy || settingsPanelBusy || appearancePanelBusy || aboutPanelBusy || accountPanelBusy || systemUpdatePanelBusy || currencyPanelBusy || backupPanelBusy) return
                when {
                    accountPanel.visibility == View.VISIBLE -> setAccountProgress(backEvent.progress)
                    aboutPanel.visibility == View.VISIBLE -> setAboutProgress(backEvent.progress)
                    appearancePanel.visibility == View.VISIBLE -> setAppearanceProgress(backEvent.progress)
                    systemUpdatePanel.visibility == View.VISIBLE -> setSystemUpdateProgress(backEvent.progress)
                    currencyPanel.visibility == View.VISIBLE -> setCurrencyProgress(backEvent.progress)
                    backupPanel.visibility == View.VISIBLE -> setBackupProgress(backEvent.progress)
                    settingsPanel.visibility == View.VISIBLE -> setSettingsProgress(backEvent.progress)
                    detailPanel.visibility == View.VISIBLE -> setPanelProgress(backEvent.progress)
                    onLogsPage -> setLogsBackProgress(backEvent.progress)
                }
            }

            override fun handleOnBackCancelled() {
                if (panelBusy || settingsPanelBusy || appearancePanelBusy || aboutPanelBusy || accountPanelBusy || systemUpdatePanelBusy || currencyPanelBusy || backupPanelBusy) return
                when {
                    accountPanel.visibility == View.VISIBLE -> animateAccountTo(open = true)
                    aboutPanel.visibility == View.VISIBLE -> animateAboutTo(open = true)
                    appearancePanel.visibility == View.VISIBLE -> animateAppearanceTo(open = true)
                    systemUpdatePanel.visibility == View.VISIBLE -> animateSystemUpdateTo(open = true)
                    currencyPanel.visibility == View.VISIBLE -> animateCurrencyTo(open = true)
                    backupPanel.visibility == View.VISIBLE -> animateBackupTo(open = true)
                    settingsPanel.visibility == View.VISIBLE -> animateSettingsTo(open = true)
                    detailPanel.visibility == View.VISIBLE -> animatePanelTo(open = true)
                    onLogsPage -> resetLogsBack()
                }
            }

            override fun handleOnBackPressed() {
                if (panelBusy || settingsPanelBusy || appearancePanelBusy || aboutPanelBusy || accountPanelBusy || systemUpdatePanelBusy || currencyPanelBusy || backupPanelBusy) return
                when {
                    accountPanel.visibility == View.VISIBLE -> animateAccountTo(open = false)
                    aboutPanel.visibility == View.VISIBLE -> animateAboutTo(open = false)
                    appearancePanel.visibility == View.VISIBLE -> animateAppearanceTo(open = false)
                    systemUpdatePanel.visibility == View.VISIBLE -> animateSystemUpdateTo(open = false)
                    currencyPanel.visibility == View.VISIBLE -> if (!currencyForced) animateCurrencyTo(open = false)
                    backupPanel.visibility == View.VISIBLE -> animateBackupTo(open = false)
                    settingsPanel.visibility == View.VISIBLE -> animateSettingsTo(open = false)
                    detailPanel.visibility == View.VISIBLE -> animatePanelTo(open = false)
                    onLogsPage -> completeLogsToHome()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        connectivityListener = { online -> handleNetworkChange(online) }
        ConnectivityMonitor.addListener(connectivityListener!!)

        // Instant: cached pending update shows with zero network wait
        UpdateManager.getPendingUpdate(this)?.let { cached ->
            updatePopupShown = true
            shownUpdateTag = cached.tagName
            refreshUpdateBadge()
            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                UpdateAvailableSheet.newInstance(cached)
                    .show(supportFragmentManager, UpdateAvailableSheet.TAG)
            }, 800)
        }
        // Fresh check refreshes the cache + badge (no second popup)
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            UpdateManager.checkForUpdateInPage(this) { result ->
                runOnUiThread {
                    refreshUpdateBadge()
                    if (isFinishing || isDestroyed || updatePopupShown) return@runOnUiThread
                    val info = result.getOrNull() ?: return@runOnUiThread
                    updatePopupShown = true
                    shownUpdateTag = info.tagName
                    UpdateAvailableSheet.newInstance(info)
                        .show(supportFragmentManager, UpdateAvailableSheet.TAG)
                }
            }
        }, 3000)

        // QR join link (scan -> app opens -> code auto-joins)
        consumePendingJoinCode()
        handleJoinLink(intent)
        handleOpenPerson(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinLink(intent)
        handleOpenPerson(intent)
    }

    // Push tap -> open the person detail once people are loaded
    private var pendingOpenPersonId: String = ""

    private fun handleOpenPerson(intent: android.content.Intent?) {
        val pid = intent?.getStringExtra(PushService.EXTRA_OPEN_PERSON).orEmpty()
        if (pid.isBlank()) return
        intent?.removeExtra(PushService.EXTRA_OPEN_PERSON)
        pendingOpenPersonId = pid
        consumePendingOpenPerson()
    }

    private fun consumePendingOpenPerson() {
        if (pendingOpenPersonId.isBlank() || !listLoaded) return
        val target = peopleList.find { it.id == pendingOpenPersonId } ?: return
        pendingOpenPersonId = ""
        try {
            openPersonDetail(target)
        } catch (_: Exception) {}
    }

    private fun handleJoinLink(intent: android.content.Intent?) {
        val data = intent?.data
        // brodue://join?code=XXXXXX (or .../join/XXXXXX) and the https variant
        var code = data?.getQueryParameter("code").orEmpty()
        if (code.length != 6) {
            code = data?.lastPathSegment?.takeIf { it.length == 6 }.orEmpty()
        }
        if (code.length != 6) return
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            prefs.edit().putString("pending_join_code", code).apply()
            return
        }
        openJoinWithCode(code)
    }

    private fun consumePendingJoinCode() {
        val code = prefs.getString("pending_join_code", "").orEmpty()
        if (code.length != 6) return
        prefs.edit().remove("pending_join_code").apply()
        openJoinWithCode(code)
    }

    private fun openJoinWithCode(code: String) {
        // Offline builds can't join shared people - ignore join links
        if (BuildConfig.OFFLINE_MODE) return
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            showSheet(JoinCodeSheet.newInstance(code), JoinCodeSheet.TAG)
        }
    }

    override fun onStart() {
        super.onStart()
        if (offlineMode) return
        if (!OFFLINE_FEATURE_ENABLED || NetworkUtils.isOnline(this)) {
            attachListener()
            attachCountListener()
        } else {
            enterOfflineMode()
            Toast.makeText(this, R.string.offline_switched, Toast.LENGTH_SHORT).show()
        }
    }

    // True when the screen was turned off since the last unlock (for non-instant lock mode).
    // Starts true so the very first launch still asks once.
    private var screenWentOff = true
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) screenWentOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Safety net: any animation cancelled across stop/start leaves its
        // busy flag stuck (dead back button + dead panel opens) - clear all.
        clearPanelBusy()
        // "App Lock" child toggle controls the open-app fingerprint requirement.
        // Instant Lock ON  -> require fingerprint every time the app is resumed (minimize counts).
        // Instant Lock OFF -> only after the screen was actually locked/off.
        val master = prefs.getBoolean("app_lock_enabled", false)
        val appLockChild = prefs.getBoolean("task_lock_app", true)
        val instant = prefs.getBoolean("instant_lock_enabled", false)
        val needLock = instant || screenWentOff
        screenWentOff = false
        if (master && appLockChild && needLock) {
            showLockOverlay()
            window.decorView.postDelayed({ showAppLockPrompt() }, 300)
        } else {
            hideLockOverlay()
        }
        // Re-evaluate after install/return (bumped version clears the dot)
        refreshUpdateBadge()
        // Live repaint for background pushes (tx realtime is push-driven now)
        try {
            if (txPushReceiver == null) {
                txPushReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                        try {
                            refreshLocalData()
                        } catch (_: Exception) {}
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this, txPushReceiver, android.content.IntentFilter(PushService.ACTION_TX_PUSH),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
    }

    private var txPushReceiver: android.content.BroadcastReceiver? = null

    override fun onPause() {
        super.onPause()
        try {
            txPushReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
    }

    private var lockDialog: android.app.Dialog? = null

    private fun showLockOverlay() {
        if (lockDialog?.isShowing == true) return
        // Use a full-screen dialog that sits above all BottomSheets/Drawers
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_app_lock)
            window?.let { w ->
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.setWindowAnimations(R.style.SheetSlideAnimation)
            }
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            findViewById<View>(R.id.btnUnlockDialog)?.setOnClickListener { showAppLockPrompt() }
            // Also allow tapping the background to prompt
            findViewById<View>(R.id.rootLockDialog)?.setOnClickListener { showAppLockPrompt() }
        }
        lockDialog = dlg
        dlg.show()
        // Also keep the old overlay for fallback (hidden behind dialog, but keep entries hidden)
        findViewById<View>(R.id.rvPeople)?.visibility = View.GONE
        findViewById<View>(R.id.rvLogs)?.visibility = View.GONE
        findViewById<View>(R.id.emptyState)?.visibility = View.GONE
        findViewById<View>(R.id.logsEmpty)?.visibility = View.GONE
    }

    private fun hideLockOverlay() {
        lockDialog?.dismiss()
        lockDialog = null
        // Also hide old views if they were used
        findViewById<View>(R.id.lockOverlayBg)?.visibility = View.GONE
        findViewById<View>(R.id.lockOverlay)?.visibility = View.GONE
        if (!isFinishing && !isDestroyed) {
            updateLogsCard()
            if (peopleList.isNotEmpty()) {
                rvPeople.isVisible = true
                emptyState.isVisible = false
            } else if (listLoaded) {
                emptyState.isVisible = true
            }
            if (logsAdapter.itemCount > 0) {
                rvLogs.isVisible = true
                logsEmpty.isVisible = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopAllRealtime()
        detachListener()
        detachTransactionsListener()
        detachLogsListener()
        detachCountListener()
    }

    override fun onDestroy() {
        connectivityListener?.let { ConnectivityMonitor.removeListener(it) }
        connectivityListener = null
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        try { mainHandler.removeCallbacks(refreshRunnable) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun switchPage(show: View, hide: View, forward: Boolean) {
        if (pageSwitching) return
        pageSwitching = true
        val width = resources.displayMetrics.widthPixels.toFloat()
        val dir = if (forward) 1f else -1f

        val interpolator = if (forward) homeToLogsInterpolator else FastOutSlowInInterpolator()
        hide.animate().cancel()
        show.animate().cancel()
        hide.animate()
            .translationX(-dir * width)
            .setDuration(320)
            .setInterpolator(interpolator)
            .withEndAction {
                hide.visibility = View.GONE
                hide.translationX = 0f
                hide.alpha = 1f
                pageSwitching = false
            }
            .start()
        show.alpha = 1f
        show.translationX = dir * width
        show.visibility = View.VISIBLE
        show.animate()
            .translationX(0f)
            .setDuration(320)
            .setInterpolator(interpolator)
            .start()
        mainHandler.postDelayed({ pageSwitching = false }, 360)
    }

    private fun updateBackCallback() {
        backCallback.isEnabled =
            detailPanel.visibility == View.VISIBLE || settingsPanel.visibility == View.VISIBLE || appearancePanel.visibility == View.VISIBLE || aboutPanel.visibility == View.VISIBLE || accountPanel.visibility == View.VISIBLE || (if (::systemUpdatePanel.isInitialized) systemUpdatePanel.visibility == View.VISIBLE else false) || (if (::currencyPanel.isInitialized) currencyPanel.visibility == View.VISIBLE else false) || (if (::backupPanel.isInitialized) backupPanel.visibility == View.VISIBLE else false) || onLogsPage
    }

    // Stuck-busy guard: panel animators reset their busy flag only in
    // withEndAction, which never runs when a back-gesture (or anything else)
    // cancels the animator mid-flight. A stuck flag silently eats all future
    // back presses AND panel opens. Gesture takeover paths and onResume call
    // this; every open function also guards on visibility, so clearing is safe.
    private fun clearPanelBusy() {
        panelBusy = false
        settingsPanelBusy = false
        appearancePanelBusy = false
        aboutPanelBusy = false
        accountPanelBusy = false
        systemUpdatePanelBusy = false
        currencyPanelBusy = false
        backupPanelBusy = false
    }

    private fun showHomePage() {
        if (pageSwitching) return
        if (logsContent.visibility != View.VISIBLE) return
        switchPage(homeContent, logsContent, forward = false)
        selectNav(btnNavHome, btnNavLogs)
        detachLogsListener()
        onLogsPage = false
        setHomeNetsLoading()
        updateBackCallback()
    }

    private fun setLogsBackProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        homeContent.animate().cancel()
        logsContent.animate().cancel()
        homeContent.visibility = View.VISIBLE
        homeContent.alpha = 1f
        homeContent.translationX = -width + width * p
        logsContent.translationX = width * p
    }

    private fun resetLogsBack() {
        val width = resources.displayMetrics.widthPixels.toFloat()
        updateBackCallback()
        homeContent.animate()
            .translationX(-width)
            .setDuration(220)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction { homeContent.visibility = View.GONE }
            .start()
        logsContent.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun completeLogsToHome() {
        val width = resources.displayMetrics.widthPixels.toFloat()
        if (homeContent.visibility != View.VISIBLE) {
            homeContent.translationX = -width
        }
        homeContent.visibility = View.VISIBLE
        homeContent.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
        logsContent.animate()
            .translationX(width)
            .setDuration(220)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                logsContent.visibility = View.GONE
                logsContent.translationX = 0f
                logsContent.alpha = 1f
            }
            .start()
        selectNav(btnNavHome, btnNavLogs)
        detachLogsListener()
        onLogsPage = false
        setHomeNetsLoading()
        updateBackCallback()
    }

    private fun showLogsPage() {
        if (pageSwitching) return
        if (logsContent.visibility == View.VISIBLE) return
        switchPage(logsContent, homeContent, forward = true)
        selectNav(btnNavLogs, btnNavHome)
        onLogsPage = true
        updateBackCallback()
        // Rebuild here if refreshes skipped it while we were away
        ensureLogsCache()
        // Skeleton while logs data not yet loaded (online only; offline populates instantly).
        // Instant starts skip it: cached entries render straight away.
        if (!offlineMode && !logsListLoaded && logsEntriesCache.isEmpty() && !instantStart) {
            showLogsSkeleton()
        }
        if (offlineMode) {
            populateLogsFromSnapshot()
        } else {
            attachLogsListener()
        }
    }

    private fun selectNav(active: MaterialButton, inactive: MaterialButton) {
        active.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary_container)))
        active.setIconTint(ColorStateList.valueOf(getColor(R.color.primary)))
        active.setTextColor(getColor(R.color.primary))
        inactive.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        inactive.setIconTint(ColorStateList.valueOf(getColor(R.color.text_secondary)))
        inactive.setTextColor(getColor(R.color.text_secondary))
    }

    private fun setupProfileButton() {
        val wrap = findViewById<View>(R.id.btnProfileWrap)
        val img = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.btnProfile)
        val tvInitial = findViewById<TextView>(R.id.tvProfileInitialSmall)
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val photoUrl = user?.photoUrl
        val name = user?.displayName ?: "User"
        if (photoUrl != null) {
            tvInitial.visibility = View.GONE
            img.visibility = View.VISIBLE
            img.load(photoUrl)
        } else {
            img.visibility = View.INVISIBLE
            tvInitial.visibility = View.VISIBLE
            tvInitial.text = name.trim().take(1).uppercase().ifEmpty { "U" }
            AvatarColors.style(this, tvInitial, name)
        }
        wrap.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showSheet(ProfileSheet.newInstance(), ProfileSheet.TAG)
        }
    }

    private fun markLogsReady() {
        if (logsListLoaded) return
        logsListLoaded = true
        hideLogsSkeleton()
        if (onLogsPage) renderLogsList()
    }

    // Logs totals grouped by effective currency symbol:
    // [net, pos, neg, sendCount, receiveCount, sendAmount, receiveAmount].
    // Stored per-person aggregates are summed per currency - never mixed,
    // never rescanned from transactions.
    private fun buildLogsCurrencyTotals(peopleObj: org.json.JSONObject, defaultSym: String): Map<String, LongArray> {
        val map = linkedMapOf<String, LongArray>()
        val pk = peopleObj.keys()
        while (pk.hasNext()) {
            val o = peopleObj.optJSONObject(pk.next()) ?: continue
            if (o.optBoolean("archived", false)) continue
            val sym = o.optString("currency", "").ifBlank { defaultSym }
            val t = map.getOrPut(sym) { LongArray(7) }
            val net = o.optLong("netAmount", 0L)
            t[0] += net
            if (net > 0) t[1] += net else if (net < 0) t[2] += net
            t[3] += o.optInt("gaveCount", 0)
            t[4] += o.optInt("receivedCount", 0)
            t[5] += o.optLong("gave", 0L)
            t[6] += o.optLong("received", 0L)
        }
        return map
    }

    private fun selectedLogsTotals(): LongArray =
        logsTotalsByCurrency[logsCurrency]
            ?: logsTotalsByCurrency[CurrencyManager.getSymbol(this)]
            ?: logsTotalsByCurrency.values.firstOrNull()
            ?: LongArray(7)

    // Points the logs* fields at the selected currency's slice and orders
    // the currency buttons (default currency first).
    private fun applyLogsCurrencySelection() {
        val def = CurrencyManager.getSymbol(this)
        if (logsCurrency !in logsTotalsByCurrency) {
            logsCurrency = if (def in logsTotalsByCurrency) def
            else logsTotalsByCurrency.keys.firstOrNull() ?: def
        }
        logsCurrencies = logsTotalsByCurrency.keys.sortedWith(
            compareBy({ it != def }, { it })
        )
        val t = selectedLogsTotals()
        logsTotalNet = t[0]
        logsPosTotal = t[1]; logsNegTotal = t[2]
        logsSendCount = t[3].toInt(); logsReceiveCount = t[4].toInt()
        logsSendAmount = t[5]; logsReceiveAmount = t[6]
    }

    // Source-filtered view of the entry cache (All/Local/Cloud).
    // Totals + net card always use the full cache - untouched here.
    private fun visibleLogsEntries(): List<LogEntry> = when (logsSourceFilter) {
        "local" -> logsEntriesCache.filter { !ShareSync.isCloudPerson(this, it.personId) }
        "cloud" -> logsEntriesCache.filter { ShareSync.isCloudPerson(this, it.personId) }
        else -> logsEntriesCache.toList()
    }

    private fun renderLogsList() {
        // Sort here (not on every refresh): only the visible list needs order
        logsEntriesCache.sortWith(compareByDescending<LogEntry> { if (it.tx.savedAt > 0) it.tx.savedAt else it.tx.createdAt }.thenByDescending { it.tx.id })
        val visible = visibleLogsEntries()
        logsAdapter.submitList(visible)
        computeLogsGraph(visible)
        rvLogs.isVisible = visible.isNotEmpty()
        logsEmpty.isVisible = visible.isEmpty()
    }

    private fun styleLogsSourceTabs() {
        val tabs = mapOf(
            "all" to findViewById<MaterialButton>(R.id.btnTabLogsAll),
            "local" to findViewById<MaterialButton>(R.id.btnTabLogsLocal),
            "cloud" to findViewById<MaterialButton>(R.id.btnTabLogsCloud)
        )
        tabs.forEach { (key, btn) ->
            val sel = key == logsSourceFilter
            btn.backgroundTintList = getColorStateList(
                if (sel) R.color.primary_container else R.color.field_bg
            )
            btn.setTextColor(getColor(if (sel) R.color.primary else R.color.text_secondary))
            btn.strokeColor = getColorStateList(
                if (sel) R.color.primary else R.color.border_light
            )
        }
    }

    private fun selectLogsSourceFilter(filter: String) {
        if (filter == logsSourceFilter) return
        logsSourceFilter = filter
        prefs.edit().putString("logs_source_filter", filter).apply()
        styleLogsSourceTabs()
        animateNextLogsChange()
        renderLogsList()
    }

    private fun refreshLogsFromCache() {
        // Totals come from the stored per-person aggregates (netAmount,
        // received/gave, counts), grouped by currency - no transaction
        // scan, no sorting. Sorting happens in renderLogsList only.
        PersonTotals.ensureAll(this)
        logsTotalsByCurrency = buildLogsCurrencyTotals(
            LocalStore.people(this), CurrencyManager.getSymbol(this)
        )
        applyLogsCurrencySelection()
        saveLogsStats()
        updateLogsCard()
        if (onLogsPage && logsListLoaded) renderLogsList()
    }

    private fun upsertLogsEntry(personId: String, tx: Transaction) {
        logsEntriesCache.removeAll { it.tx.id == tx.id }
        val name = peopleList.find { it.id == personId }?.name ?: ""
        logsEntriesCache.add(LogEntry(personId, name, tx))
        refreshLogsFromCache()
    }

    private fun removeLogsEntry(txId: String) {
        if (logsEntriesCache.removeAll { it.tx.id == txId }) refreshLogsFromCache()
    }

    private fun saveLogsStats() {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return
        try {
            LocalStore.setStats(
                this,
                mapOf(
                    "logsTotalNet" to logsTotalNet,
                    "logsPosTotal" to logsPosTotal,
                    "logsNegTotal" to logsNegTotal,
                    "logsSendCount" to logsSendCount,
                    "logsReceiveCount" to logsReceiveCount,
                    "logsSendAmount" to logsSendAmount,
                    "logsReceiveAmount" to logsReceiveAmount,
                    "logsCurrency" to logsCurrency,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {}
    }

    // Rebuilds are expensive (full people + logs cache rebuild), and realtime
    // bursts fire several per second - coalesce into one trailing rebuild.
    private val refreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed) refreshLocalDataNow()
    }

    fun refreshLocalData() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 120)
    }

    private fun refreshLocalDataNow() {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return
        // Covers joins/restores that bypass startup (no-op once pinned)
        CurrencyManager.pinLegacyDefaults(this)
        // People
        val people = mutableListOf<Person>()
        val peopleObj = LocalStore.people(this)
        val pKeys = peopleObj.keys()
        while (pKeys.hasNext()) {
            val pid = pKeys.next()
            val o = peopleObj.optJSONObject(pid) ?: continue
            if (o.optBoolean("archived", false)) continue
            val realName = o.optString("name", "")
            // "Share as me": joined accounts render this person as the owner
            val roleHere = ShareSync.accessRole(this, pid)
            val shareAsMeActive = ShareSync.isCloudPerson(this, pid) &&
                o.optBoolean("shareAsMe", false) &&
                roleHere != "owner"
            val displayName = if (shareAsMeActive) o.optString("ownerName", "").ifBlank { realName } else realName
            val displayPhoto = if (shareAsMeActive) o.optString("ownerPhotoUrl", "") else ""
            // Home red dot: synced persons whose cloud tx state is newer
            // than what was last loaded. Suppressed only for persons unknown
            // before the first pull (fresh installs/joins never dot).
            val latestTx = o.optLong("txUpdatedAt", 0L)
            val loadedTx = o.optLong("txUpdatedAtLoaded", 0L)
            val known = loadedTx != 0L || ShareSync.wasKnownAtStart(pid)
            val hasUpdate = ShareSync.isSynced(this, pid) &&
                known && latestTx != loadedTx
            people.add(
                Person(
                    pid,
                    displayName,
                    o.optString("mobile", ""),
                    o.optLong("netAmount", 0L),
                    o.optLong("createdAt", 0L),
                    o.optLong("updatedAt", 0L),
                    displayPhoto,
                    hasUpdate,
                    ShareSync.isSynced(this, pid),
                    o.optBoolean("leftGroup", false)
                )
            )
        }
        peopleList = people
        applySort()
        updateHomeFilterDot()
        // Net shimmer shows on every home open until the first server
        // fetch lands; afterwards values stay revealed. Instant starts skip
        // it: the background-synced cache is already the latest truth.
        if (!serverFetched && !instantStart) setHomeNetsLoading() else revealHomeNets()
        LocalStore.setPeopleCount(this, people.size)
        // Baseline for rolling animation (selected currency's slice)
        if (logsFirstLoad) {
            val t = buildLogsCurrencyTotals(
                LocalStore.people(this), CurrencyManager.getSymbol(this)
            )[logsCurrency] ?: LongArray(7)
            lastLogsNetForAnim = when (logsDisplayMode) {
                "pos" -> t[1]
                "neg" -> kotlin.math.abs(t[2])
                "send" -> t[5]
                "receive" -> t[6]
                else -> kotlin.math.abs(t[0])
            }
        }
        // Transactions -> logs cache. Built ONLY while the logs page is
        // visible (or the cache is empty): every refresh otherwise skips the
        // full object churn + sort, which is what lags low-end devices.
        // Totals/card never need it (person aggregates). Navigating to logs
        // rebuilds via ensureLogsCache().
        if (onLogsPage || logsEntriesCache.isEmpty()) {
            rebuildLogsCache()
        } else {
            logsCacheDirty = true
        }
        refreshLogsFromCache()
        // Refresh the open person detail page (transactions + net card) instantly
        if (::detailPanel.isInitialized && detailPanel.visibility == View.VISIBLE) {
            currentPerson?.let { attachTxListener(it.id) }
        }
        // UI visibility
        progress.isVisible = false
        skeletonList.isVisible = false
        emptyState.isVisible = people.isEmpty()
        rvPeople.isVisible = people.isNotEmpty()
        listLoaded = true
        markLogsReady()
        consumePendingOpenPerson()
    }

    // Local-first: reload from local storage, pull my synced persons from
    // Firestore (fresh install / re-login), then start realtime listeners.
    // First server fetch per process: ends the home net shimmer everywhere
    private var serverFetched = false

    private fun setHomeNetsLoading() {
        if (serverFetched || adapter.amountsLoading) return
        adapter.amountsLoading = true
        try {
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    private fun revealHomeNets() {
        if (!adapter.amountsLoading) return
        adapter.amountsLoading = false
        try {
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    private fun attachListener() {
        // Snapshot pre-pull persons once per process (dot suppression basis)
        try {
            val keys = mutableSetOf<String>()
            val it = LocalStore.people(this).keys()
            while (it.hasNext()) keys.add(it.next())
            ShareSync.noteKnownPersons(keys)
        } catch (_: Exception) {}
        refreshLocalData()
        // Safety: never shimmer forever if the fetch hangs
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            serverFetched = true
            revealHomeNets()
        }, 12000)
        // Automatic backups first (local-only: must not wait on network pulls)
        AutoBackup.onAppStart(this)
        // Pool shape migration first (legacy lists -> count map), then
        // 1) restore own synced people, 2) restore joined people (grants),
        // then refresh + start realtime listeners
        ShareSync.ensurePoolShape(this) {
            runOnUiThread { pullAfterPoolShape() }
        }
        // One-time server-count bootstrap (ground-truth recount once ever)
        ShareSync.ensureCounterInit(this)
        // Live limit: Console edits land in seconds (listeners are dormant
        // until an admin actually writes - zero polling)
        ShareSync.startCapListener(this)
    }

    private fun pullAfterPoolShape() {
        if (isFinishing || isDestroyed) return
        ShareSync.pullAllMine(this) { count, err ->
            ShareSync.pullMyGrants(this) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        // First server fetch landed: reveal home nets for good
                        serverFetched = true
                        revealHomeNets()
                        refreshLocalData()
                        (ShareSync.syncedIds(this) + ShareSync.cloudPersonIds(this))
                            .distinct()
                            .forEach { ShareSync.startRealtime(this, it) }
                        // Live cap + lockdown check (server enforces truth)
                        ShareSync.fetchSyncCap(this) { maybeShowSyncLock() }
                        // Remove orphaned QR codes (app was killed before close)
                        ShareSync.cleanupStaleQrCodes(this)
                        // Cloud data is in: now a due auto-backup sees the
                        // real dataset instead of a mid-restore window
                        AutoBackup.runInBackground(this)
                        if (err != null) {
                            Toast.makeText(this, "Sync restore failed: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun stopAllRealtime() {
        ShareSync.syncedIds(this).forEach { ShareSync.stopRealtime(it) }
        ShareSync.stopCapListener()
    }

    private fun attachPeopleChildListener() = Unit

    private fun attachTransactionsListener() = Unit

    private fun handleTxUpsert() = Unit

    private fun detachTransactionsListener() = Unit

    private fun detachListener() = Unit

    // Called before signing out: cancel pending UI callbacks
    fun prepareLogout() {
        detachListener()
        detachTransactionsListener()
        detachLogsListener()
        detachCountListener()
        detachTxListener()
        pendingOfflineRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingOfflineRunnable = null
        lastWrittenCount = null
    }

    // Instant start: cached data renders immediately, no loading chrome.
    // Set once in onCreate from hasLocalData; pulls still run silently.
    private var instantStart: Boolean = false

    // Home source filter: all | local | cloud. Display only -
    // peopleList stays complete for logs names, totals, and sync.
    private var homeSourceFilter: String = "all"

    private fun visiblePeople(): List<Person> = when (homeSourceFilter) {
        "local" -> peopleList.filter { !ShareSync.isCloudPerson(this, it.id) }
        "cloud" -> peopleList.filter { ShareSync.isCloudPerson(this, it.id) }
        else -> peopleList.toList()
    }

    private fun styleHomeSourceTabs() {
        val tabs = mapOf(
            "all" to findViewById<MaterialButton>(R.id.btnTabHomeAll),
            "local" to findViewById<MaterialButton>(R.id.btnTabHomeLocal),
            "cloud" to findViewById<MaterialButton>(R.id.btnTabHomeCloud)
        )
        tabs.forEach { (key, btn) ->
            val sel = key == homeSourceFilter
            btn.backgroundTintList = getColorStateList(
                if (sel) R.color.primary_container else R.color.field_bg
            )
            btn.setTextColor(getColor(if (sel) R.color.primary else R.color.text_secondary))
            btn.strokeColor = getColorStateList(
                if (sel) R.color.primary else R.color.border_light
            )
        }
    }

    // Over-limit lockdown detector: the server denies reads/writes past the
    // live cap, so surface the recovery popup once per session (release
    // valves - disable/leave - keep working while locked).
    private var syncLockShown = false

    // Live cap changed (Console edit landed): refresh an open lock sheet
    // and re-evaluate (a raise may have reconnected, a cut may lock).
    fun onSyncCapChanged() {
        try {
            syncLockShown = false
            (supportFragmentManager.findFragmentByTag(SyncLockSheet.TAG) as? SyncLockSheet)?.refresh()
            maybeShowSyncLock()
        } catch (_: Exception) {}
    }

    fun maybeShowSyncLock(force: Boolean = false) {
        try {
            if (syncLockShown && !force) return
            if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return
            val cap = ShareSync.cachedSyncCap(this)
            val used = ShareSync.syncedCount(this)
            if (used <= cap) return
            syncLockShown = true
            showSheet(SyncLockSheet.newInstance(), SyncLockSheet.TAG)
        } catch (_: Exception) {}
    }

    // Red dot on the All tab if any person carries an update dot
    private fun updateHomeFilterDot() {
        try {
            findViewById<View>(R.id.homeAllDot).isVisible = peopleList.any { it.hasUpdate }
        } catch (_: Exception) {}
    }

    private fun selectHomeSourceFilter(filter: String) {
        if (filter == homeSourceFilter) return
        homeSourceFilter = filter
        prefs.edit().putString("home_source_filter", filter).apply()
        styleHomeSourceTabs()
        applySort()
    }

    // Lazy 25-chunk entry feed for one person. Gap check is a single
    // in-memory pass (zero reads): stored cloud counts vs local rows.
    // Complete feeds fetch nothing; gaps/scroll page 25 at a time.
    private fun ensurePersonFeed(personId: String) {
        try {
            if (offlineMode || archivedView) return
            if (!ShareSync.isCloudPerson(this, personId) && !ShareSync.isSynced(this, personId)) return
            if (!NetworkUtils.isOnline(this)) return
            val o = LocalStore.people(this).optJSONObject(personId) ?: return
            if (!o.has("receivedCount") || !o.has("gaveCount")) {
                // No counts yet (legacy/fresh): one seeding chunk, then the
                // stored counts + pushes keep it exact from here on.
                fetchFeedChunk(personId, true)
                return
            }
            val expected = o.optInt("receivedCount", 0) + o.optInt("gaveCount", 0)
            var local = 0
            val txs = LocalStore.transactions(this)
            val keys = txs.keys()
            while (keys.hasNext()) {
                if (txs.optJSONObject(keys.next())?.optString("personId", "") == personId) local++
            }
            if (local >= expected) return
            fetchFeedChunk(personId, true)
        } catch (_: Exception) {}
    }

    private fun fetchFeedChunk(personId: String, reset: Boolean) {
        try {
            ShareSync.fetchTxChunk(this, personId, reset) { added ->
                runOnUiThread {
                    if (currentPerson?.id != personId) return@runOnUiThread
                    if (added > 0) attachTxListener(personId)
                    if (!ShareSync.feedHasMore(personId)) {
                        // Feed complete: pin local counts so future opens
                        // pass the gap check with zero reads (legacy/countless
                        // docs converge here; pushes + pulls own deltas after).
                        try {
                            val o = LocalStore.people(this).optJSONObject(personId)
                                ?: return@runOnUiThread
                            if (!o.has("receivedCount") || !o.has("gaveCount")) {
                                PersonTotals.fieldMap(
                                    PersonTotals.accumulate(
                                        LocalStore.transactions(this), personId
                                    )
                                ).forEach { (k, v) -> o.put(k, v) }
                                LocalStore.persist(this)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun applySort() {
        val base = when (sortMode) {
            SortMode.AZ -> visiblePeople().sortedBy { it.name.lowercase() }
            SortMode.VALUE -> visiblePeople().sortedBy { it.netAmount }
            SortMode.RECENT -> visiblePeople().sortedByDescending { it.updatedAt }
        }
        // Order toggle: A->Z / Z->A, new->old / old->new, low->high / high->low
        adapter.submitList(if (sortDescending) base.reversed() else base)
    }

    private fun attachLogsListener() {
        // Skeleton while initial data still loading (instant starts skip it)
        if (!logsListLoaded && logsEntriesCache.isEmpty() && !instantStart) {
            showLogsSkeleton()
        } else {
            hideLogsSkeleton()
        }
        // Just refresh UI from cache
        if (logsListLoaded || logsEntriesCache.isNotEmpty()) renderLogsList()
        // Totals are already kept in sync via syncLogsForPerson
        updateLogsCard()
    }

    private fun detachLogsListener() = Unit

    private fun attachCountListener() = Unit

    private fun detachCountListener() = Unit

    private fun updateCountIfNeeded(count: Int) {
        if (lastWrittenCount != count) {
            lastWrittenCount = count
            if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return
            try {
                LocalStore.setPeopleCount(this, count)
            } catch (_: Exception) {}
        }
    }

    private fun showSkeleton(count: Int) {
        val skeleton = skeletonList as ShimmerLayout
        if (skeleton.childCount == 0) {
            val inflater = LayoutInflater.from(this)
            repeat(count.coerceAtLeast(1)) {
                skeleton.addView(inflater.inflate(R.layout.item_skeleton, skeleton, false))
            }
        }
        skeleton.isVisible = true
        rvPeople.isVisible = false
    }

    private fun showLogsSkeleton(count: Int = 8) {
        val skeleton = skeletonLogs as ShimmerLayout
        if (skeleton.childCount == 0) {
            val inflater = LayoutInflater.from(this)
            repeat(count.coerceAtLeast(1)) {
                skeleton.addView(inflater.inflate(R.layout.item_skeleton_log, skeleton, false))
            }
        }
        skeleton.isVisible = true
        rvLogs.isVisible = false
        logsEmpty.isVisible = false
        // Top card skeleton: hide label/amount bars, show shimmer bars
        skeletonLogCard.isVisible = true
        tvLogNetLabel.isVisible = false
        tvLogNetAmount.isVisible = false
    }

    private fun hideLogsSkeleton() {
        skeletonLogs.isVisible = false
        // Reveal real top card content
        skeletonLogCard.isVisible = false
        tvLogNetLabel.isVisible = true
        tvLogNetAmount.isVisible = true
    }

    private fun openAddSheet() {
        showSheet(AddPersonBottomSheet(), AddPersonBottomSheet.TAG)
    }

    private fun handleNetworkChange(online: Boolean) {
        // Live grey state for synced-person actions (independent of offline mode)
        refreshSyncedOnlineState()
        if (firstNetworkEvent) {
            firstNetworkEvent = false
            return
        }
        if (!OFFLINE_FEATURE_ENABLED) return
        if (online) {
            pendingOfflineRunnable?.let { run ->
                mainHandler.removeCallbacks(run)
                pendingOfflineRunnable = null
            }
            if (offlineMode) {
                exitOfflineMode()
                Toast.makeText(this, R.string.back_online, Toast.LENGTH_SHORT).show()
            }
        } else {
            if (offlineMode || pendingOfflineRunnable != null) return
            val run = Runnable {
                pendingOfflineRunnable = null
                if (!offlineMode) {
                    enterOfflineMode()
                    Toast.makeText(this, R.string.offline_switched, Toast.LENGTH_SHORT).show()
                }
            }
            pendingOfflineRunnable = run
            mainHandler.postDelayed(run, 1500)
        }
    }

    private fun enterOfflineMode() {
        offlineMode = true
        NetworkUtils.offlineActive = true
        offlineSnapshot = OfflineStore.load(this)
        populatePeopleFromSnapshot()
        applyOfflineUI()
        val person = currentPerson
        if (detailPanel.visibility == View.VISIBLE && person != null) {
            populateDetailFromSnapshot(person.id)
            setActionButtonsGreyed(true)
        }
        if (logsContent.visibility == View.VISIBLE) populateLogsFromSnapshot()
    }

    private fun applyOfflineUI() {
        crossfadeButton(btnAdd) {
            btnAdd.setIconResource(R.drawable.ic_reload)
            btnAdd.contentDescription = getString(R.string.reload)
        }
        btnAdd.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            // Retry connection; if still offline show "No internet"
            NetworkUtils.retryConnection(this) { exitOfflineMode() }
        }
    }

    private fun exitOfflineMode() {
        offlineMode = false
        NetworkUtils.offlineActive = false
        crossfadeButton(btnAdd) {
            btnAdd.setIconResource(R.drawable.ic_add)
            btnAdd.contentDescription = getString(R.string.add_person)
        }
        btnAdd.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            openAddSheet()
        }
        setActionButtonsGreyed(false)
        attachListener()
        attachCountListener()
        if (detailPanel.visibility == View.VISIBLE) {
            currentPerson?.let { attachTxListener(it.id) }
        }
        if (onLogsPage) attachLogsListener()
    }

    private fun populatePeopleFromSnapshot() {
        val people = mutableListOf<Person>()
        val peopleJson = OfflineStore.peopleJson(offlineSnapshot)
        val keys = peopleJson.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val obj = peopleJson.optJSONObject(id) ?: continue
            if (obj.optBoolean("archived", false)) continue
            val name = obj.optString("name", "")
            if (name.isEmpty()) continue
            val mobile = obj.optString("mobile", "")
            val net = obj.optLong("netAmount", 0L)
            val createdAt = obj.optLong("createdAt", 0L)
            val updatedAt = obj.optLong("updatedAt", createdAt)
            people.add(Person(id, name, mobile, net, createdAt, updatedAt))
        }
        peopleList = people
        applySort()
        // Sync logs baseline for offline too (so first anim is delta, not 0->total)
        if (logsFirstLoad) {
            val t = buildLogsCurrencyTotals(
                peopleJson, CurrencyManager.getSymbol(this)
            )[logsCurrency] ?: LongArray(7)
            lastLogsNetForAnim = when (logsDisplayMode) {
                "pos" -> t[1]
                "neg" -> kotlin.math.abs(t[2])
                "send" -> t[5]
                "receive" -> t[6]
                else -> kotlin.math.abs(t[0])
            }
        }
        progress.isVisible = false
        skeletonList.isVisible = false
        emptyState.isVisible = people.isEmpty()
        rvPeople.isVisible = people.isNotEmpty()
        listLoaded = true
    }

    private fun setActionButtonsGreyed(greyed: Boolean) {
        val btnReceived = findViewById<MaterialButton>(R.id.btnReceived)
        val btnGave = findViewById<MaterialButton>(R.id.btnGave)

        if (receivedBg == null) {
            receivedBg = btnReceived.backgroundTintList
            receivedIcon = btnReceived.iconTint
            receivedStroke = btnReceived.strokeColor
            receivedElevation = btnReceived.elevation
            gaveBg = btnGave.backgroundTintList
            gaveIcon = btnGave.iconTint
            gaveStroke = btnGave.strokeColor
            gaveElevation = btnGave.elevation
        }

        if (!greyed) {
            crossfadeButton(btnReceived) {
                btnReceived.isEnabled = true
                btnReceived.backgroundTintList = receivedBg
                btnReceived.iconTint = receivedIcon
                btnReceived.strokeColor = receivedStroke
                btnReceived.elevation = receivedElevation
            }
            crossfadeButton(btnGave) {
                btnGave.isEnabled = true
                btnGave.backgroundTintList = gaveBg
                btnGave.iconTint = gaveIcon
                btnGave.strokeColor = gaveStroke
                btnGave.elevation = gaveElevation
            }
            return
        }

        crossfadeButton(btnReceived) {
            // Stay clickable while greyed so taps can trigger a connection retry
            btnReceived.isEnabled = true
            btnReceived.backgroundTintList = ColorStateList.valueOf(getColor(R.color.outline_variant))
            btnReceived.iconTint = ColorStateList.valueOf(getColor(R.color.grey_soft))
            btnReceived.strokeColor = ColorStateList.valueOf(getColor(R.color.grey_soft))
            btnReceived.elevation = 0f
        }
        crossfadeButton(btnGave) {
            btnGave.isEnabled = true
            btnGave.backgroundTintList = ColorStateList.valueOf(getColor(R.color.outline_variant))
            btnGave.iconTint = ColorStateList.valueOf(getColor(R.color.grey_soft))
            btnGave.strokeColor = ColorStateList.valueOf(getColor(R.color.grey_soft))
            btnGave.elevation = 0f
        }
    }

    private fun populateLogsFromSnapshot() {
        val entries = mutableListOf<LogEntry>()
        val peopleJson = OfflineStore.peopleJson(offlineSnapshot)
        val txsJson = OfflineStore.transactionsJson(offlineSnapshot)
        // Non-archived person ids for filtering
        val activePeople = mutableMapOf<String, String>()
        val pk = peopleJson.keys()
        while (pk.hasNext()) {
            val pid = pk.next()
            val po = peopleJson.optJSONObject(pid) ?: continue
            if (po.optBoolean("archived", false)) continue
            activePeople[pid] = po.optString("name", "")
        }
        val tk = txsJson.keys()
        while (tk.hasNext()) {
            val txId = tk.next()
            val t = txsJson.optJSONObject(txId) ?: continue
            val pid = t.optString("personId", "")
            val name = activePeople[pid] ?: continue
            entries.add(
                LogEntry(
                    pid, name,
                    Transaction(
                        txId,
                        t.optString("category", "General"),
                        t.optLong("amount", 0L),
                        t.optString("type", "gave"),
                        t.optString("note", ""),
                        t.optLong("createdAt", 0L),
                        t.optLong("savedAt", 0L)
                    )
                )
            )
        }
        logsEntriesCache = entries.toMutableList()
        refreshLogsFromCache()
        logsListLoaded = true
        hideLogsSkeleton()
        renderLogsList()
    }

    private fun updateLogsCard() {
        val label: String
        val valueStr: CharSequence
        val newValue: Long
        val colorRes: Int
        val sym = logsCurrency
        when (logsDisplayMode) {
            "pos" -> {
                label = "Receivable"
                newValue = logsPosTotal
                valueStr = Formatters.amountWithSymbol(newValue, sym)
                colorRes = if (newValue == 0L) R.color.text_primary else R.color.positive
            }
            "neg" -> {
                label = "Payable"
                newValue = kotlin.math.abs(logsNegTotal)
                valueStr = Formatters.amountWithSymbol(newValue, sym)
                colorRes = if (logsNegTotal == 0L) R.color.text_primary else R.color.negative
            }
            "send" -> {
                label = "$logsSendCount Send"
                newValue = logsSendAmount
                valueStr = Formatters.amountWithSymbol(newValue, sym)
                colorRes = if (newValue == 0L) R.color.text_primary else R.color.negative
            }
            "receive" -> {
                label = "$logsReceiveCount Received"
                newValue = logsReceiveAmount
                valueStr = Formatters.amountWithSymbol(newValue, sym)
                colorRes = if (newValue == 0L) R.color.text_primary else R.color.positive
            }
            else -> {
                label = "Net Balance"
                newValue = kotlin.math.abs(logsTotalNet)
                valueStr = Formatters.amountWithSymbol(newValue, sym)
                colorRes = when {
                    logsTotalNet == 0L -> R.color.text_primary
                    logsTotalNet > 0 -> R.color.positive
                    else -> R.color.negative
                }
            }
        }
        tvLogNetLabel.text = label.uppercase()
        val color = getColor(colorRes)
        if (logsFirstLoad) {
            logsFirstLoad = false
            lastLogsNetForAnim = newValue
            UiUtils.cancelRoll(tvLogNetAmount)
            tvLogNetAmount.text = valueStr
            tvLogNetAmount.setTextColor(color)
            return
        }
        if (!onLogsPage) {
            // Silent sync while away - the stale baseline makes arrival
            // roll the catch-up delta automatically
            tvLogNetAmount.text = valueStr
            tvLogNetAmount.setTextColor(color)
            return
        }
        // On logs page: same rule as the detail card - roll every real change
        val old = lastLogsNetForAnim
        lastLogsNetForAnim = newValue
        if (tvLogNetAmount.width == 0 || old == newValue) {
            // Same echo-stomp guard as the detail net card (see updateNetCard)
            if (old == newValue && UiUtils.isRollingTo(tvLogNetAmount, newValue)) return
            UiUtils.cancelRoll(tvLogNetAmount)
            tvLogNetAmount.text = valueStr
            tvLogNetAmount.setTextColor(color)
            return
        }
        UiUtils.rollingAmount(
            tvLogNetAmount, old, newValue,
            { v -> Formatters.amountWithSymbol(v, logsCurrency) },
            tvLogNetAmount.currentTextColor, color
        )
    }

    private fun showLogsDisplaySheet() {
        val sheet = LogsDisplaySheet.newInstance(
            logsTotalsByCurrency, logsCurrencies, logsCurrency, logsDisplayMode
        )
        // Listen for selection
        supportFragmentManager.setFragmentResultListener(LogsDisplaySheet.REQ_KEY, this) { _, bundle ->
            val sel = bundle.getString("selected") ?: return@setFragmentResultListener
            logsDisplayMode = sel
            prefs.edit().putString("logs_display_mode", sel).apply()
            bundle.getString("currency")?.let { cur ->
                if (cur != logsCurrency && cur in logsTotalsByCurrency) {
                    logsCurrency = cur
                    prefs.edit().putString("logs_currency", cur).apply()
                    applyLogsCurrencySelection()
                    saveLogsStats()
                    // Currency switch = new context: snap, don't roll
                    logsFirstLoad = true
                }
            }
            findViewById<TextView>(R.id.tvAppearanceTopCardValue)?.text =
                topCardModeLabel(sel, logsCurrency)
            updateLogsCard()
            recomputeLogsGraphForMode()
        }
        sheet.show(supportFragmentManager, LogsDisplaySheet.TAG)
    }

    private fun topCardModeLabel(mode: String, currency: String = logsCurrency): String {
        val base = when (mode) {
            "pos" -> "Receivable"
            "neg" -> "Payable"
            "send" -> "Send"
            "receive" -> "Received"
            else -> "Net Balance"
        }
        return "$base ($currency)"
    }

    private fun populateDetailFromSnapshot(personId: String) {
        val txs = mutableListOf<Transaction>()
        val txsJson = OfflineStore.transactionsJson(offlineSnapshot)
        val keys = txsJson.keys()
        while (keys.hasNext()) {
            val txId = keys.next()
            val t = txsJson.optJSONObject(txId) ?: continue
            if (t.optString("personId", "") != personId) continue
            txs.add(
                Transaction(
                    txId,
                    t.optString("category", "General"),
                    t.optLong("amount", 0L),
                    t.optString("type", "gave"),
                    t.optString("note", ""),
                    t.optLong("createdAt", 0L),
                    t.optLong("savedAt", 0L)
                )
            )
        }
        txs.sortWith(compareByDescending<Transaction> { if (it.savedAt > 0) it.savedAt else it.createdAt }.thenByDescending { it.id })
        transactionAdapter.submitList(txs)
        transactionHeaderAdapter.visible = txs.isNotEmpty()
        // Totals from the stored snapshot person record, not the entries
        val po = OfflineStore.peopleJson(offlineSnapshot).optJSONObject(personId)
        currentReceived = po?.optLong("received", 0L) ?: 0L
        currentGave = po?.optLong("gave", 0L) ?: 0L
        currentReceivedCount = po?.optInt("receivedCount", 0) ?: 0
        currentGaveCount = po?.optInt("gaveCount", 0) ?: 0
        currentNet = po?.optLong("netAmount", 0L) ?: 0L
        updateNetCard()
        rvRecords.isVisible = txs.isNotEmpty()
        recordsEmpty.isVisible = txs.isEmpty()
    }

    private fun openPersonDetail(person: Person, keepArchivedView: Boolean = false) {
        if (panelBusy) return
        if (detailPanel.visibility == View.VISIBLE && currentPerson?.id == person.id) return
        if (!keepArchivedView) archivedView = false
        currentPerson = person
        findViewById<TextView>(R.id.detailName).text = person.name
        val avatar = findViewById<TextView>(R.id.detailAvatar)
        val avatarPhoto = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.detailAvatarPhoto)
        if (person.photoUrl.isNotBlank()) {
            avatarPhoto.visibility = View.VISIBLE
            avatar.visibility = View.INVISIBLE
            avatarPhoto.load(person.photoUrl) { crossfade(true) }
        } else {
            avatarPhoto.visibility = View.GONE
            avatar.visibility = View.VISIBLE
            avatar.text = person.name.trim().take(1).uppercase()
            AvatarColors.style(this, avatar, person.name)
        }

        currentNet = 0
        currentReceived = 0
        currentGave = 0
        currentReceivedCount = 0
        currentGaveCount = 0
        UiUtils.cancelRoll(tvNetAmount)
        tvNetAmount.text = Formatters.amount(0)
        tvNetAmount.setTextColor(getColor(R.color.text_primary))
        lastNetForAnim = 0L
        netFirstLoad = true
        transactionAdapter.submitList(emptyList())
        transactionHeaderAdapter.visible = false
        recordsEmpty.isVisible = true
        if (offlineMode) {
            populateDetailFromSnapshot(person.id)
            setActionButtonsGreyed(true)
        } else {
            attachTxListener(person.id)
            // Lazy entry feed: cached rows render instantly above; the newest
            // 25-chunk backfills any gap (fresh installs/joins/missed pushes)
            // and scroll pages the rest. Zero reads when already complete.
            ensurePersonFeed(person.id)
        }

        detailPanel.visibility = View.VISIBLE
        detailPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setPanelRadius(maxRadiusPx)
        animatePanelTo(open = true)
        updateBackCallback()
        applyDetailRole(person.id)
    }

    // Role-based controls in the detail page
    // - read-only: no Send/Receive buttons
    // - write/owner: can add entries (writers write through to the database)
    private fun applyDetailRole(personId: String) {
        // Archived inspect: pure view-only, no action buttons at all
        if (archivedView) {
            findViewById<View>(R.id.wrapReceived).visibility = View.GONE
            findViewById<View>(R.id.wrapGave).visibility = View.GONE
            findViewById<View>(R.id.btnReceived).visibility = View.GONE
            findViewById<View>(R.id.btnGave).visibility = View.GONE
            findViewById<View>(R.id.btnDetailSync).visibility = View.GONE
            findViewById<View>(R.id.btnEditCopy).visibility = View.GONE
            // ...but group-status alerts still show for left/orphaned groups
            val leftPerson = try {
                LocalStore.people(this).optJSONObject(personId)
            } catch (_: Exception) { null }
            if (leftPerson?.optBoolean("leftGroup", false) == true) {
                findViewById<View>(R.id.leftGroupRow).visibility = View.VISIBLE
                findViewById<TextView>(R.id.tvLeftGroupPill).text =
                    when (leftPerson?.optString("leftReason", "left")) {
                        "disabled" -> getString(R.string.left_group_disabled)
                        "removed" -> getString(R.string.left_group_removed)
                        "foreign" -> getString(R.string.left_group_foreign)
                        else -> getString(R.string.left_group)
                    }
            } else {
                findViewById<View>(R.id.leftGroupRow).visibility = View.GONE
            }
            findViewById<View>(R.id.syncPausedRow).visibility = View.GONE
            return
        }
        // Group-status pill for locally-kept left/orphaned persons
        val leftPerson = try {
            LocalStore.people(this).optJSONObject(personId)
        } catch (_: Exception) { null }
        val leftGroup = leftPerson?.optBoolean("leftGroup", false) == true
        findViewById<View>(R.id.leftGroupRow).visibility =
            if (leftGroup) View.VISIBLE else View.GONE
        if (leftGroup) {
            findViewById<TextView>(R.id.tvLeftGroupPill).text = when (leftPerson?.optString("leftReason", "left")) {
                "disabled" -> getString(R.string.left_group_disabled)
                "removed" -> getString(R.string.left_group_removed)
                "foreign" -> getString(R.string.left_group_foreign)
                else -> getString(R.string.left_group)
            }
        }
        val role = ShareSync.accessRole(this, personId)
        val isSynced = ShareSync.isSynced(this, personId)
        val wrapReceived = findViewById<View>(R.id.wrapReceived)
        val wrapGave = findViewById<View>(R.id.wrapGave)
        val btnReceived = findViewById<View>(R.id.btnReceived)
        val btnGave = findViewById<View>(R.id.btnGave)
        val btnEditCopy = findViewById<View>(R.id.btnEditCopy)
        val btnDetailSync = findViewById<View>(R.id.btnDetailSync)
        if (leftGroup) {
            // Left person: no send/receive, no sync - only the Edit-copy pill
            wrapReceived.visibility = View.GONE
            wrapGave.visibility = View.GONE
            btnReceived.visibility = View.GONE
            btnGave.visibility = View.GONE
            btnDetailSync.visibility = View.GONE
            btnEditCopy.visibility = View.VISIBLE
            findViewById<View>(R.id.syncPausedRow).visibility = View.GONE
            return
        }
        // Over-limit lockdown: local entries stay visible, but nothing may
        // be added - send/receive go away and the paused banner explains why.
        val locked = isSynced && ShareSync.syncedCount(this) > ShareSync.cachedSyncCap(this)
        findViewById<View>(R.id.syncPausedRow).visibility =
            if (locked) View.VISIBLE else View.GONE
        if (locked) {
            // Greyed, not gone: visible but dead. Sync icon stays live -
            // disabling from its drawer is the way back in.
            setActionButtonsGreyed(true)
            wrapReceived.isEnabled = false
            wrapGave.isEnabled = false
            (btnReceived as? MaterialButton)?.isEnabled = false
            (btnGave as? MaterialButton)?.isEnabled = false
            btnEditCopy.visibility = View.GONE
            btnDetailSync.visibility = View.VISIBLE
            refreshDetailSyncIcon(personId)
            return
        }
        findViewById<View>(R.id.syncPausedRow).visibility = View.GONE
        wrapReceived.isEnabled = true
        wrapGave.isEnabled = true
        (btnReceived as? MaterialButton)?.isEnabled = true
        (btnGave as? MaterialButton)?.isEnabled = true
        val canAdd = when {
            !isSynced -> true // local-only person, full controls
            role == "owner" || role == "write" -> true
            else -> false // read
        }
        val vis = if (canAdd) View.VISIBLE else View.GONE
        wrapReceived.visibility = vis
        wrapGave.visibility = vis
        btnReceived.visibility = vis
        btnGave.visibility = vis
        btnEditCopy.visibility = View.GONE
        // Sync icon: everyone (viewers/editors get a members-only drawer).
        // Offline builds have no sync at all - no icon, no drawer.
        if (BuildConfig.OFFLINE_MODE) {
            btnDetailSync.visibility = View.GONE
            refreshSyncedOnlineState()
            return
        }
        btnDetailSync.visibility = View.VISIBLE
        refreshDetailSyncIcon(personId)
        refreshSyncedOnlineState()
    }

    // Sync icon state (off by default, on once the person is synced)
    private fun refreshDetailSyncIcon(personId: String) {
        if (currentPerson?.id != personId) return
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDetailSync)
        btn.setIconResource(
            if (ShareSync.isSynced(this, personId)) R.drawable.ic_sync_on
            else R.drawable.ic_sync_off
        )
    }

    // Called by PersonCurrencySheet: same data, new symbols - force rebind
    // (DiffUtil can't see the currency change inside identical items).
    fun onPersonCurrencyChanged() {
        refreshLocalData()
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                adapter.notifyDataSetChanged()
                logsAdapter.notifyDataSetChanged()
            }
        }
    }

    // Called by SyncConfirmSheet after a person is synced to cloud
    fun onPersonSyncChanged(personId: String) {
        if (currentPerson?.id == personId) applyDetailRole(personId)
        // Home rows (sync icon), detail and logs all refresh instantly
        refreshLocalData()
        refreshLogsFromCache()
    }

    // Open the share drawer once, right after a fresh sync
    fun openSyncShare(personId: String) {
        if (BuildConfig.OFFLINE_MODE) return
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            showSheet(SyncShareSheet.newInstance(personId), SyncShareSheet.TAG)
        }, 250)
    }

    // Repaints record rows after an entry dot clears (no data rebuild)
    fun repaintTxDots() {
        try {
            transactionAdapter.notifyDataSetChanged()
        } catch (_: Exception) {}
        try {
            logsAdapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    // Marks viewport-visible entries of one person as seen.
    // Entries outside the viewport keep their dots.
    private fun markViewportSeen(personId: String) {
        try {
            val lm = rvRecords.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                ?: return
            val headerOff = if (transactionHeaderAdapter.visible) 1 else 0
            val cur = transactionAdapter.currentList
            val txs = LocalStore.transactions(this)
            var changed = false
            for (pos in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) {
                val tx = cur.getOrNull(pos - headerOff) ?: continue
                val o = txs.optJSONObject(tx.id) ?: continue
                if (o.optString("personId", "") != personId) continue
                if (o.optBoolean("unseen", false)) {
                    o.put("unseen", false)
                    changed = true
                }
            }
            if (changed) LocalStore.persist(this)
        } catch (_: Exception) {}
    }

    // Person dot clears only when none of its entries still have dots:
    // then loaded snaps to latest and the home row updates live.
    fun refreshPersonDot(personId: String) {
        try {
            var remaining = false
            val txs = LocalStore.transactions(this)
            val keys = txs.keys()
            while (keys.hasNext()) {
                val t = txs.optJSONObject(keys.next()) ?: continue
                if (t.optString("personId", "") == personId && t.optBoolean("unseen", false)) {
                    remaining = true
                    break
                }
            }
            if (remaining) return
            ShareSync.markTxLoadedCurrent(this, personId)
            val idx = peopleList.indexOfFirst { it.id == personId }
            if (idx >= 0 && peopleList[idx].hasUpdate) {
                val updated = peopleList.toMutableList()
                updated[idx] = updated[idx].copy(hasUpdate = false)
                peopleList = updated
                applySort()
                updateHomeFilterDot()
            }
        } catch (_: Exception) {}
    }

    // Re-renders the open detail header after a name/mobile edit,
    // so changes show instantly without closing the page
    fun refreshOpenPersonHeader(personId: String) {
        try {
            if (!::detailPanel.isInitialized || detailPanel.visibility != View.VISIBLE) return
            if (currentPerson?.id != personId) return
            val o = LocalStore.people(this).optJSONObject(personId) ?: return
            // Re-resolve display name the same way refreshLocalData does
            val realName = o.optString("name", "")
            val roleHere = ShareSync.accessRole(this, personId)
            val shareAsMeActive = ShareSync.isCloudPerson(this, personId) &&
                o.optBoolean("shareAsMe", false) && roleHere != "owner"
            val displayName = if (shareAsMeActive) {
                o.optString("ownerName", "").ifBlank { realName }
            } else {
                realName
            }
            val displayPhoto = if (shareAsMeActive) o.optString("ownerPhotoUrl", "") else ""
            currentPerson = currentPerson?.copy(name = displayName, mobile = o.optString("mobile", ""))
            findViewById<TextView>(R.id.detailName).text = displayName
            val avatar = findViewById<TextView>(R.id.detailAvatar)
            val avatarPhoto =
                findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.detailAvatarPhoto)
            if (displayPhoto.isNotBlank()) {
                avatarPhoto.visibility = View.VISIBLE
                avatar.visibility = View.INVISIBLE
                avatarPhoto.load(displayPhoto) { crossfade(true) }
            } else {
                avatarPhoto.visibility = View.GONE
                avatar.visibility = View.VISIBLE
                avatar.text = displayName.trim().take(1).uppercase()
                AvatarColors.style(this, avatar, displayName)
            }
        } catch (_: Exception) {}
    }

    // Called by SwipeLeaveSheet after leaving a shared person
    fun onPersonLeft(personId: String) {
        if (currentPerson?.id == personId) closePersonDetail()
        refreshLocalData()
    }

    // Copy a left person into a fresh editable local person and archive
    // the left original. Animates: drawer closes, old row wipes out,
    // then the new row slides in.
    fun makeEditableCopy(oldId: String): Boolean {
        val newId = duplicateLeftPerson(oldId) ?: return false
        Toast.makeText(this, R.string.copy_created, Toast.LENGTH_SHORT).show()
        // Back to home first so the list swap is visible
        closePersonDetail()
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) {
                refreshLocalData()
                return@postDelayed
            }
            try {
                // Stage 1: wipe the old row (data already archived underneath)
                val current = adapter.currentList.toList()
                adapter.submitList(current.filter { it.id != oldId }) {
                    // Stage 2: add the new row
                    mainHandler.postDelayed({
                        if (isFinishing || isDestroyed) return@postDelayed
                        refreshLocalData()
                    }, 450)
                }
            } catch (_: Exception) {
                refreshLocalData()
            }
        }, 350)
        return true
    }

    // Data half of the copy (no UI): duplicates the person + its
    // transactions locally and archives the original. Returns the new id.
    private fun duplicateLeftPerson(oldId: String): String? {
        try {
            val old = LocalStore.people(this).optJSONObject(oldId) ?: return null
            val now = System.currentTimeMillis()
            val newId = java.util.UUID.randomUUID().toString()
            var net = 0L
            val txs = LocalStore.transactions(this)
            val tKeys = txs.keys()
            val toCopy = mutableListOf<org.json.JSONObject>()
            while (tKeys.hasNext()) {
                val tid = tKeys.next()
                val t = txs.optJSONObject(tid) ?: continue
                if (t.optString("personId", "") == oldId) toCopy.add(t)
            }
            toCopy.forEach { t ->
                val copy = org.json.JSONObject(t.toString())
                val map = mutableMapOf<String, Any?>()
                val keys = copy.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "unseen") continue
                    val v = copy.opt(k)
                    if (v != null && v !== org.json.JSONObject.NULL) map[k] = v
                }
                val nid = java.util.UUID.randomUUID().toString()
                map["id"] = nid
                map["personId"] = newId
                LocalStore.putTransaction(this, nid, map)
                val amount = (map["amount"] as? Number)?.toLong() ?: 0L
                net += if (map["type"] == "received") amount else -amount
            }
            LocalStore.upsertPerson(
                this, newId,
                mapOf(
                    "name" to old.optString("name", ""),
                    "mobile" to old.optString("mobile", ""),
                    "netAmount" to net,
                    "archived" to false,
                    "createdAt" to now,
                    "updatedAt" to now,
                    "txUpdatedAt" to now,
                    "txUpdatedAtLoaded" to now,
                    // Duplicated entries keep the original person's currency
                    "currency" to old.optString("currency", "")
                        .ifBlank { CurrencyManager.getSymbol(this) }
                )
            )
            LocalStore.updatePersonFields(this, oldId, mapOf("archived" to true))
            return newId
        } catch (_: Exception) {
            return null
        }
    }

    fun openArchivedPerson(person: Person) {
        archivedView = true
        openPersonDetail(person, keepArchivedView = true)
        if (!offlineMode) setActionButtonsGreyed(true)
        // Self-heal: if the list stayed empty despite stored transactions,
        // load them directly (inspect must never show a blank page)
        rvRecords.postDelayed({
            try {
                if (currentPerson?.id != person.id) return@postDelayed
                if (txListCache.isNotEmpty()) return@postDelayed
                val txs = LocalStore.transactions(this)
                val keys = txs.keys()
                var stored = false
                while (keys.hasNext()) {
                    if (txs.optJSONObject(keys.next())?.optString("personId", "") == person.id) {
                        stored = true
                        break
                    }
                }
                if (stored) attachTxListener(person.id)
            } catch (_: Exception) {}
        }, 400)
    }

    private fun setPanelProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density // ~56dp slide like native
        detailPanel.animate().cancel()
        homeContent.animate().cancel()
        logsContent.animate().cancel()
        bottomNav.animate().cancel()
        scrimView.animate().cancel()
        radiusAnimator?.cancel()
        detailPanel.translationX = width * p
        scrimView.alpha = 0.35f * (1f - p)
        homeContent.translationX = -slide * (1f - p)
        logsContent.translationX = -slide * (1f - p)
        bottomNav.translationX = -slide * (1f - p)
        // Keep scale at 1, no shrink
        homeContent.scaleX = 1f
        homeContent.scaleY = 1f
        logsContent.scaleX = 1f
        logsContent.scaleY = 1f
        bottomNav.scaleX = 1f
        bottomNav.scaleY = 1f
        setPanelRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setPanelRadius(radius: Float) {
        panelRadius = radius
        detailPanel.invalidateOutline()
    }

    private fun animatePanelRadius(target: Float, duration: Long, startDelay: Long = 0L) {
        radiusAnimator?.cancel()
        radiusAnimator = ValueAnimator.ofFloat(panelRadius, target).apply {
            addUpdateListener { setPanelRadius(it.animatedValue as Float) }
            setDuration(duration)
            setStartDelay(startDelay)
            interpolator = FastOutSlowInInterpolator()
            start()
        }
    }

    private fun refreshCurrencyViews() {
        Formatters.setCurrencySymbol(CurrencyManager.getSymbol(this))
        adapter.notifyDataSetChanged()
        logsAdapter.notifyDataSetChanged()
        transactionAdapter.notifyDataSetChanged()
        updateLogsCard()
        updateNetCard()
        setupSettingsList()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CURRENCY && resultCode == RESULT_OK) {
            refreshCurrencyViews()
        }
    }

    companion object {
        private const val REQ_CURRENCY = 2001
        private const val REQ_AUTO_EVERY = "auto_every"
        private const val REQ_AUTO_KEEP = "auto_keep"
    }

    private fun setupSettingsList() {
        val currency = CurrencyManager.getCurrency(this)
        val currencyDesc = if (currency != null) "${currency.symbol} ${currency.name}" else "Select your currency"
        val rv = findViewById<RecyclerView>(R.id.rvSettings)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        // Offline builds: account/backup/update rows don't exist - the app
        // is local-only, so only device-local settings remain.
        val settingsRows = listOf(
            Triple("Account", "Manage your account", R.drawable.ic_account),
            Triple("Appearance", "Theme and display", R.drawable.ic_appearance),
            Triple("Currency", currencyDesc, R.drawable.ic_currency),
            Triple("Backup and Restore", "Backup your data", R.drawable.ic_backup),
            Triple("System Update", "Check for updates", R.drawable.ic_system_update),
            Triple("About", "App info and version", R.drawable.ic_about)
        ).filter { (title, _, _) ->
            !BuildConfig.OFFLINE_MODE || (title != "Account" && title != "Backup and Restore" && title != "System Update")
        }
        rv.adapter = SettingsAdapter(
            settingsRows,
            badgeTitles = if (UpdateManager.getPendingUpdate(this) != null) {
                setOf("System Update")
            } else {
                emptySet()
            }
        ) { title ->
            when (title) {
                "Account" -> openAccount()
                "Currency" -> openCurrency()
                "Appearance" -> openAppearance()
                "About" -> openAbout()
                "Backup and Restore" -> openBackup()
                "System Update" -> openSystemUpdate()
                else -> android.widget.Toast.makeText(this, "$title — Coming soon", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { closeSettings() }
        // Set actual version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tvSettingsVersion).text = "v${pInfo.versionName}"
        } catch (_: Exception) {
            findViewById<TextView>(R.id.tvSettingsVersion).text = "v1.0"
        }
    }

    private fun setupAbout() {
        findViewById<View>(R.id.btnAboutBack).setOnClickListener { closeAbout() }
        findViewById<View>(R.id.cardGithub).setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Arun-Kaswan")))
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "Cannot open link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // Fill version and build type
        val tvVersion = findViewById<TextView>(R.id.tvAboutVersion)
        val tvBuildType = findViewById<TextView>(R.id.tvAboutBuildType)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pInfo.versionName}"
        } catch (_: Exception) {
            tvVersion.text = "v2.5"
        }
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        tvBuildType.text = if (isDebug) "Debug" else "Release"
    }

    private fun setupAppearanceList() {
        val rv = findViewById<RecyclerView>(R.id.rvAppearance)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        val items = listOf(
            Triple("Follow System", "", R.drawable.ic_appearance_follow),
            Triple("Light", "", R.drawable.ic_appearance_light),
            Triple("Dark", "", R.drawable.ic_appearance_dark),
            Triple("Amoled", "", R.drawable.ic_appearance_amoled)
        )
        rv.adapter = AppearanceAdapter(items, selectedKey = "Light") { title ->
            if (title == "Light") return@AppearanceAdapter
            android.widget.Toast.makeText(this, "$title theme - Coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnAppearanceBack).setOnClickListener { closeAppearance() }

        val switchHome = findViewById<IosSwitch>(R.id.switchHomeCategoryIcon)
        val switchLogs = findViewById<IosSwitch>(R.id.switchLogsCategoryIcon)
        switchHome.isChecked = prefs.getBoolean("home_use_category_icon", false)
        switchLogs.isChecked = prefs.getBoolean("logs_use_category_icon", false)
        // Make the whole card clickable
        findViewById<View>(R.id.cardHomeCategoryIcon).setOnClickListener { switchHome.performClick() }
        findViewById<View>(R.id.cardLogsCategoryIcon).setOnClickListener { switchLogs.performClick() }
        switchHome.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("home_use_category_icon", checked).apply()
            // Refresh peoples list
            transactionAdapter.notifyDataSetChanged()
            // Also refresh detail if needed
            rvPeople.adapter?.notifyDataSetChanged()
        }
        switchLogs.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("logs_use_category_icon", checked).apply()
            logsAdapter.notifyDataSetChanged()
        }

        val switchViewCard = findViewById<IosSwitch>(R.id.switchViewCardOnlySelected)
        switchViewCard.isChecked = prefs.getBoolean("view_card_only_selected", false)
        findViewById<View>(R.id.cardViewCardOnlySelected).setOnClickListener { switchViewCard.performClick() }
        switchViewCard.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("view_card_only_selected", checked).apply()
            // Refresh any open RecordDetailSheet so it re-renders with new rule
            supportFragmentManager.findFragmentByTag(RecordDetailSheet.TAG)?.let {
                if (it is RecordDetailSheet && it.isAdded) {
                    it.refreshForAppearanceChange()
                }
            }
        }

        // Top Card: opens the same LogsDisplaySheet as long-press on logs top card
        // Read saved mode here - setupAppearanceList() runs before logsDisplayMode is loaded in onCreate
        val savedMode = prefs.getString("logs_display_mode", "net") ?: "net"
        findViewById<TextView>(R.id.tvAppearanceTopCardValue).text = topCardModeLabel(savedMode)
        findViewById<View>(R.id.cardAppearanceTopCard).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showLogsDisplaySheet()
        }

        // Grouped corners + gap for Top Card + Use category icon, same as Settings page
        // (View Card stays a separate standalone card)
        val logCards = listOf(
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardAppearanceTopCard),
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardLogsCategoryIcon)
        )
        val d = resources.displayMetrics.density
        val outerRadius = 18 * d
        val innerRadius = 8 * d
        logCards.forEachIndexed { i, card ->
            card.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == 0) outerRadius else innerRadius)
                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == 0) outerRadius else innerRadius)
                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == logCards.lastIndex) outerRadius else innerRadius)
                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == logCards.lastIndex) outerRadius else innerRadius)
                .build()
            (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = ((if (i == 0) 8f else 4f) * d).toInt()
                card.layoutParams = lp
            }
        }
    }

    fun openSettings() {
        if (settingsPanelBusy) return
        if (settingsPanel.visibility == View.VISIBLE) return
        // Rebuild rows so the update dot is always current
        setupSettingsList()
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setSettingsRadius(maxRadiusPx)
        animateSettingsTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
    }

    fun closeSettings() {
        animateSettingsTo(open = false)
    }

    private fun updateSwipeEnabled() {
        val accountVisible = accountPanel.visibility == View.VISIBLE
        val aboutVisible = aboutPanel.visibility == View.VISIBLE
        val appearanceVisible = appearancePanel.visibility == View.VISIBLE
        val systemUpdateVisible = if (::systemUpdatePanel.isInitialized) systemUpdatePanel.visibility == View.VISIBLE else false
        val currencyVisible = if (::currencyPanel.isInitialized) currencyPanel.visibility == View.VISIBLE else false
        val backupVisible = if (::backupPanel.isInitialized) backupPanel.visibility == View.VISIBLE else false
        val settingsVisible = settingsPanel.visibility == View.VISIBLE
        val detailVisible = detailPanel.visibility == View.VISIBLE
        (accountPanel as SwipeCloseLayout).swipeEnabled = accountVisible
        (aboutPanel as SwipeCloseLayout).swipeEnabled = aboutVisible && !accountVisible
        (appearancePanel as SwipeCloseLayout).swipeEnabled = appearanceVisible && !aboutVisible && !accountVisible
        (systemUpdatePanel as SwipeCloseLayout).swipeEnabled = systemUpdateVisible && !appearanceVisible && !aboutVisible && !accountVisible
        (currencyPanel as SwipeCloseLayout).swipeEnabled = currencyVisible && !currencyForced && !systemUpdateVisible && !appearanceVisible && !aboutVisible && !accountVisible
        (backupPanel as SwipeCloseLayout).swipeEnabled = backupVisible && !currencyVisible && !systemUpdateVisible && !appearanceVisible && !aboutVisible && !accountVisible
        (settingsPanel as SwipeCloseLayout).swipeEnabled = settingsVisible && !backupVisible && !currencyVisible && !systemUpdateVisible && !appearanceVisible && !aboutVisible && !accountVisible
        (detailPanel as SwipeCloseLayout).swipeEnabled = detailVisible && !settingsVisible && !backupVisible && !currencyVisible && !systemUpdateVisible && !appearanceVisible && !aboutVisible && !accountVisible
    }

    private fun setSettingsProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        settingsPanel.animate().cancel()
        homeContent.animate().cancel()
        logsContent.animate().cancel()
        bottomNav.animate().cancel()
        scrimView.animate().cancel()
        radiusAnimator?.cancel()
        settingsPanel.translationX = width * p
        scrimView.alpha = 0.35f * (1f - p)
        homeContent.translationX = -slide * (1f - p)
        logsContent.translationX = -slide * (1f - p)
        homeContent.scaleX = 1f
        homeContent.scaleY = 1f
        logsContent.scaleX = 1f
        logsContent.scaleY = 1f
        bottomNav.translationX = -slide * (1f - p)
        homeContent.scaleX = 1f
        homeContent.scaleY = 1f
        logsContent.scaleX = 1f
        logsContent.scaleY = 1f
        bottomNav.scaleX = 1f
        bottomNav.scaleY = 1f
        setSettingsRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setSettingsRadius(radius: Float) {
        // Reuse panelRadius for settings as well
        panelRadius = radius
        settingsPanel.invalidateOutline()
        detailPanel.invalidateOutline()
    }

    private fun animateSettingsTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        settingsPanelBusy = true
        if (open) {
            settingsPanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    settingsPanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            homeContent.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            logsContent.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            bottomNav.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            settingsPanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    settingsPanel.visibility = View.GONE
                    settingsPanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            homeContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            logsContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            bottomNav.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        }
    }

    fun openAppearance() {
        if (appearancePanelBusy) return
        if (appearancePanel.visibility == View.VISIBLE) return
        appearancePanel.visibility = View.VISIBLE
        appearancePanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setAppearanceRadius(maxRadiusPx)
        animateAppearanceTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
    }

    fun closeAppearance() {
        animateAppearanceTo(open = false)
    }

    private fun setAppearanceProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        appearancePanel.animate().cancel()
        settingsPanel.animate().cancel()
        settingsScrim.animate().cancel()
        radiusAnimator?.cancel()
        appearancePanel.translationX = width * p
        settingsScrim.alpha = 0.35f * (1f - p)
        settingsPanel.translationX = -slide * (1f - p)
        settingsPanel.scaleX = 1f
        settingsPanel.scaleY = 1f
        setAppearanceRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setAppearanceRadius(radius: Float) {
        panelRadius = radius
        appearancePanel.invalidateOutline()
        detailPanel.invalidateOutline()
        settingsPanel.invalidateOutline()
        if (::aboutPanel.isInitialized) aboutPanel.invalidateOutline()
    }

    fun openAbout() {
        if (aboutPanelBusy) return
        if (aboutPanel.visibility == View.VISIBLE) return
        aboutPanel.visibility = View.VISIBLE
        aboutPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setAboutRadius(maxRadiusPx)
        animateAboutTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
    }

    fun closeAbout() {
        animateAboutTo(open = false)
    }

    private fun setupAccount() {
        findViewById<View>(R.id.btnAccountBack).setOnClickListener { closeAccount() }
        findViewById<View>(R.id.btnAccountLogout).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            SwipeLogoutSheet.newInstance().show(supportFragmentManager, SwipeLogoutSheet.TAG)
        }
        // Fill account info
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val user = auth.currentUser
        val tvName = findViewById<TextView>(R.id.tvAccountName)
        val tvEmail = findViewById<TextView>(R.id.tvAccountEmail)
        val imgPhoto = findViewById<android.widget.ImageView>(R.id.imgAccountPhoto)
        val tvInitial = findViewById<TextView>(R.id.tvAccountInitial)
        val name = user?.displayName ?: "User"
        val email = user?.email ?: ""
        val photoUrl = user?.photoUrl
        tvName.text = name
        tvEmail.text = email
        if (photoUrl != null) {
            tvInitial.visibility = View.GONE
            imgPhoto.visibility = View.VISIBLE
            imgPhoto.load(photoUrl)
        } else {
            imgPhoto.visibility = View.INVISIBLE
            tvInitial.visibility = View.VISIBLE
            tvInitial.text = name.trim().take(1).uppercase().ifEmpty { "U" }
            AvatarColors.style(this, tvInitial, name)
        }
        // App Lock toggle - require auth before enabling
        val switchLock = findViewById<IosSwitch>(R.id.switchAppLock)
        // Click only the header row - not the whole card, so expanded child
        // toggles don't flip the master switch
        val cardLock = findViewById<View>(R.id.rowFingerprintLock)
        switchLock.isChecked = prefs.getBoolean("app_lock_enabled", false)
        cardLock.setOnClickListener { switchLock.performClick() }

        // Task-lock child toggles (expand below Fingerprint Lock with animation when enabled)
        val lockChildren = findViewById<View>(R.id.lockChildren)
        val APP_CHILD = "task_lock_app"
        val childSwitches = listOf(
            Triple(R.id.switchTaskLockApp, APP_CHILD, "App Lock"),
            Triple(R.id.switchTaskLockAdd, "task_lock_add", "Add Entry"),
            Triple(R.id.switchTaskLockEditEntry, "task_lock_edit_entry", "Edit Entry"),
            Triple(R.id.switchTaskLockArchive, "task_lock_archive", "Archive"),
            Triple(R.id.switchTaskLockEditPerson, "task_lock_edit_person", "Edit Person")
        )
        var childProgrammatic = false
        fun childDefault(key: String) = key == APP_CHILD
        var onAppChildToggled: ((Boolean) -> Unit)? = null
        // Set initial states before attaching listeners so no toasts fire on open
        childSwitches.forEach { (id, key, _) ->
            val sw = findViewById<IosSwitch>(id)
            sw.isChecked = prefs.getBoolean(key, childDefault(key))
        }
        childSwitches.forEach { (id, key, label) ->
            findViewById<IosSwitch>(id).setOnCheckedChangeListener { sw, checked ->
                if (childProgrammatic) return@setOnCheckedChangeListener
                if (!checked) {
                    // At least one child toggle must stay on
                    val anyOtherOn = childSwitches.any { (oid, okey, _) ->
                        oid != id && prefs.getBoolean(okey, childDefault(okey))
                    }
                    if (!anyOtherOn) {
                        childProgrammatic = true
                        (sw as IosSwitch).isChecked = true
                        childProgrammatic = false
                        android.widget.Toast.makeText(this, "At least one option must stay on", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }
                }
                prefs.edit().putBoolean(key, checked).apply()
                if (key == APP_CHILD) onAppChildToggled?.invoke(checked)
            }
        }
        // Instant Lock card - visible only while Fingerprint Lock + App Lock child are on
        val instantCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardInstantLock)
        val appLockCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardAppLock)
        val dP = resources.displayMetrics.density
        val privacyOuter = 18 * dP
        val privacyInner = 8 * dP
        var privacyGroupFraction = 0f
        fun applyPrivacyCorners(f: Float) {
            val mid = privacyOuter + (privacyInner - privacyOuter) * f
            fun shape(top: Float, bottom: Float) = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, top)
                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, top)
                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, bottom)
                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, bottom)
                .build()
            appLockCard.shapeAppearanceModel = shape(privacyOuter, mid)
            instantCard.shapeAppearanceModel = shape(mid, privacyOuter)
            (instantCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = ((8f - 4f * f) * dP).toInt()
                instantCard.layoutParams = lp
            }
        }
        val switchInstantLock = findViewById<IosSwitch>(R.id.switchInstantLock)
        switchInstantLock.isChecked = prefs.getBoolean("instant_lock_enabled", false)
        switchInstantLock.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("instant_lock_enabled", checked).apply()
        }
        // Tap anywhere on the card to toggle Instant Lock
        instantCard.setOnClickListener { switchInstantLock.performClick() }
        fun refreshInstantLockCard(show: Boolean, animate: Boolean) {
            switchInstantLock.isEnabled = show
            // Smoothly morph the Privacy pair between standalone (18dp) and grouped
            // (18dp outer / 8dp inner, 4dp gap) corners like the Settings page
            val startF = privacyGroupFraction
            val endF = if (show) 1f else 0f
            if (animate && startF != endF) {
                ValueAnimator.ofFloat(startF, endF).apply {
                    duration = if (show) 280L else 240L
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener {
                        privacyGroupFraction = it.animatedValue as Float
                        applyPrivacyCorners(privacyGroupFraction)
                    }
                    start()
                }
            } else {
                privacyGroupFraction = endF
                applyPrivacyCorners(endF)
            }
            if (animate) animateViewHeight(instantCard, show) else {
                instantCard.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        var lockExpanded = false
        fun animateLockChildren(show: Boolean) = animateViewHeight(lockChildren, show)
        fun refreshLockChildren(animate: Boolean) {
            val enabled = prefs.getBoolean("app_lock_enabled", false)
            childSwitches.forEach { (id, _, _) ->
                val sw = findViewById<IosSwitch>(id)
                sw.isEnabled = enabled
                sw.alpha = if (enabled) 1f else 0.45f
            }
            refreshInstantLockCard(enabled && prefs.getBoolean(APP_CHILD, true), animate)
            if (enabled == lockExpanded) return
            lockExpanded = enabled
            if (animate) animateLockChildren(enabled) else {
                lockChildren.visibility = if (enabled) View.VISIBLE else View.GONE
            }
        }
        onAppChildToggled = { checked ->
            refreshInstantLockCard(checked && prefs.getBoolean("app_lock_enabled", false), true)
        }
        refreshLockChildren(animate = false)

        var isProgrammaticChange = false
        var pendingEnable = false
        switchLock.setOnCheckedChangeListener { _, checked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (checked) {
                if (!isBiometricAvailable()) {
                    android.widget.Toast.makeText(this, "Fingerprint not available", android.widget.Toast.LENGTH_SHORT).show()
                    isProgrammaticChange = true
                    switchLock.isChecked = false
                    isProgrammaticChange = false
                    return@setOnCheckedChangeListener
                }
                // Keep switch visually ON while prompting - will be reverted only on failure
                pendingEnable = true
                val executor = ContextCompat.getMainExecutor(this)
                val prompt = androidx.biometric.BiometricPrompt(this, executor,
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                            prefs.edit().putBoolean("app_lock_enabled", true).apply()
                            // First enable: everything off except App Lock (open app)
                            if (!prefs.contains("lock_children_configured")) {
                                prefs.edit()
                                    .putBoolean("task_lock_app", true)
                                    .putBoolean("task_lock_add", false)
                                    .putBoolean("task_lock_edit_entry", false)
                                    .putBoolean("task_lock_archive", false)
                                    .putBoolean("task_lock_edit_person", false)
                                    .putBoolean("lock_children_configured", true)
                                    .apply()
                            }
                            pendingEnable = false
                            childProgrammatic = true
                            childSwitches.forEach { (id, key, _) ->
                                findViewById<IosSwitch>(id).isChecked = prefs.getBoolean(key, key == APP_CHILD)
                            }
                            childProgrammatic = false
                            refreshLockChildren(animate = true)
                            // Don't show lock immediately - it will show on next resume/close
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            // User cancelled or failed - revert to OFF, don't close app
                            isProgrammaticChange = true
                            switchLock.isChecked = false
                            isProgrammaticChange = false
                            prefs.edit().putBoolean("app_lock_enabled", false).apply()
                            pendingEnable = false
                            if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                // User pressed cancel - just keep off
                            }
                        }
                        override fun onAuthenticationFailed() {
                            // Don't revert immediately, allow retry
                        }
                    })
                val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Enable App Lock")
                    .setSubtitle("Confirm fingerprint or device password to enable")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                try { prompt.authenticate(info) } catch (_: Exception) {
                    isProgrammaticChange = true
                    switchLock.isChecked = false
                    isProgrammaticChange = false
                    prefs.edit().putBoolean("app_lock_enabled", false).apply()
                    pendingEnable = false
                }
            } else {
                // Turning off - no auth needed
                if (pendingEnable) {
                    // Was pending enable, just cancel
                    pendingEnable = false
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("app_lock_enabled", false).apply()
                hideLockOverlay()
                refreshLockChildren(animate = true)
            }
        }
    }

    // ---------- System Update Panel ----------
    private var pendingSystemUpdateInfo: UpdateManager.ReleaseInfo? = null

    // Bottom pill states: null = hidden, otherwise Update / Downloading / Install
    private var systemUpdateBottomAction: String? = null
    private var systemUpdateDownloading = false
    private var systemUpdateDownloadProgress = 0
    private var updatePopupShown = false
    private var shownUpdateTag: String? = null

    // Red dot on the logs profile picture while an update is pending
    fun refreshUpdateBadge() {
        try {
            findViewById<View>(R.id.updateBadgeDot)?.visibility =
                if (UpdateManager.getPendingUpdate(this) != null) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun setupSystemUpdate() {
        findViewById<View>(R.id.btnSystemUpdateBack).setOnClickListener { closeSystemUpdate() }
        findViewById<View>(R.id.btnSystemUpdateRetry).setOnClickListener { checkSystemUpdate() }
        findViewById<View>(R.id.btnSystemUpdateAction).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            when (systemUpdateBottomAction) {
                "install" -> {
                    val downloaded = UpdateManager.getDownloaded(this)
                    if (downloaded != null) {
                        UpdateManager.installApk(this, downloaded.second)
                    } else {
                        checkSystemUpdate()
                    }
                }
                "update" -> {
                    val info = pendingSystemUpdateInfo ?: return@setOnClickListener
                    if (!info.apkUrl.endsWith(".apk")) {
                        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(info.htmlUrl))) } catch (_: Exception) {}
                        return@setOnClickListener
                    }
                    startSystemUpdateDownload(info)
                }
                else -> Unit
            }
        }
    }

    private fun showSystemUpdateState(loading: Boolean = false, upToDate: Boolean = false, updateAvailable: Boolean = false, downloading: Boolean = false, error: String? = null, bottomAction: String? = null) {
        findViewById<View>(R.id.systemUpdateLoading).visibility = if (loading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.systemUpdateUpToDate).visibility = if (upToDate) View.VISIBLE else View.GONE
        findViewById<View>(R.id.systemUpdateCard).visibility = if (updateAvailable) View.VISIBLE else View.GONE
        findViewById<View>(R.id.systemUpdateDownloading).visibility = if (downloading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.systemUpdateError).visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) findViewById<TextView>(R.id.tvSystemUpdateError).text = error
        systemUpdateBottomAction = bottomAction
        val bottomBar = findViewById<View>(R.id.systemUpdateBottomBar)
        val btnAction = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSystemUpdateAction)
        if (bottomAction == null) {
            bottomBar.visibility = View.GONE
        } else {
            bottomBar.visibility = View.VISIBLE
            when (bottomAction) {
                "install" -> {
                    btnAction.text = "Install"
                    btnAction.isEnabled = true
                }
                "downloading" -> {
                    btnAction.text = "Downloading… $systemUpdateDownloadProgress%"
                    btnAction.isEnabled = false
                }
                else -> {
                    btnAction.text = "Update"
                    btnAction.isEnabled = true
                }
            }
        }
    }

    fun openSystemUpdate() {
        if (systemUpdatePanelBusy) return
        if (systemUpdatePanel.visibility == View.VISIBLE) return
        systemUpdatePanel.visibility = View.VISIBLE
        systemUpdatePanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setSystemUpdateRadius(maxRadiusPx)
        animateSystemUpdateTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
        checkSystemUpdate()
    }

    fun closeSystemUpdate() {
        animateSystemUpdateTo(open = false)
    }

    private fun checkSystemUpdate() {
        // Drop day-old / already-installed files first
        UpdateManager.cleanupStaleUpdate(this)
        // A download may still be running from before the page closed
        if (systemUpdateDownloading) {
            showSystemUpdateState(downloading = true, bottomAction = "downloading")
            findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressSystemUpdate).progress =
                systemUpdateDownloadProgress
            findViewById<TextView>(R.id.tvSystemUpdateProgress).text =
                "Downloading... $systemUpdateDownloadProgress%"
            return
        }
        showSystemUpdateState(loading = true)
        UpdateManager.checkForUpdateInPage(this) { result ->
            runOnUiThread {
                result.onSuccess { info ->
                    if (info != null) {
                        pendingSystemUpdateInfo = info
                        val current = UpdateManager.currentVersionName(this)
                        val latest = info.tagName.removePrefix("v")
                        findViewById<TextView>(R.id.tvSystemUpdateLatestVersion).text = "v$latest"
                        findViewById<TextView>(R.id.tvSystemUpdateCurrentLabel).text = "You're on v$current"
                        val navy = getColor(R.color.navy_text)
                        val secondary = getColor(R.color.text_secondary)
                        val body = info.body.take(2000).trim()
                            .ifEmpty { "A new version is available with improvements and fixes." }
                        findViewById<TextView>(R.id.tvSystemUpdateBody).text =
                            ReleaseNotes.render(body, navy, secondary)
                        // Already downloaded this exact version? Offer Install straight away
                        val downloaded = UpdateManager.getDownloaded(this)
                        if (downloaded != null && downloaded.first.trim().removePrefix("v")
                                .equals(latest.trim(), ignoreCase = true)
                        ) {
                            showSystemUpdateState(updateAvailable = true, bottomAction = "install")
                        } else {
                            showSystemUpdateState(updateAvailable = true, bottomAction = "update")
                        }
                    } else {
                        val current = UpdateManager.currentVersionName(this)
                        findViewById<TextView>(R.id.tvSystemUpdateUptodateDesc).text = "v$current is the latest version"
                        showSystemUpdateState(upToDate = true)
                    }
                }.onFailure { e ->
                    showSystemUpdateState(error = e.message ?: "Failed to check for updates")
                }
            }
        }
    }

    // Popup Update button: downloads instantly without opening the page.
    // Shares state with the System Update page (reopen shows progress/Install).
    fun startPopupDownload(apkUrl: String, tag: String) {
        if (systemUpdateDownloading) return
        systemUpdateDownloading = true
        systemUpdateDownloadProgress = 0
        Toast.makeText(this, R.string.downloading_update, Toast.LENGTH_SHORT).show()
        UpdateManager.downloadApkDirect(this, apkUrl, UpdateManager.fileNameFor(tag),
            onProgress = { p ->
                systemUpdateDownloadProgress = p
                updateDownloadProgressViews(p)
            },
            onComplete = { file ->
                onDownloadComplete(tag, file, silent = true)
            },
            onError = { err ->
                systemUpdateDownloading = false
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Shared download state (update popup polls the same source as the page)
    fun isUpdateDownloading(): Boolean = systemUpdateDownloading
    fun updateDownloadProgress(): Int = systemUpdateDownloadProgress

    fun installDownloadedApk() {
        val downloaded = UpdateManager.getDownloaded(this)
        if (downloaded != null) {
            UpdateManager.installApk(this, downloaded.second)
        } else {
            openSystemUpdate()
        }
    }

    // Reflects live progress onto the page when it happens to be open
    private fun updateDownloadProgressViews(p: Int) {
        try {
            if (systemUpdatePanel.visibility != View.VISIBLE) return
            findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressSystemUpdate).progress = p
            findViewById<TextView>(R.id.tvSystemUpdateProgress).text = "Downloading... $p%"
            if (systemUpdateBottomAction == "downloading") {
                findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSystemUpdateAction).text =
                    "Downloading… $p%"
            }
        } catch (_: Exception) {}
    }

    private fun onDownloadComplete(tag: String, file: java.io.File, silent: Boolean) {
        systemUpdateDownloading = false
        UpdateManager.saveDownloaded(this, tag, file)
        try {
            if (systemUpdatePanel.visibility == View.VISIBLE) {
                showSystemUpdateState(updateAvailable = true, bottomAction = "install")
            }
        } catch (_: Exception) {}
    }

    private fun startSystemUpdateDownload(info: UpdateManager.ReleaseInfo) {
        if (systemUpdateDownloading) return
        systemUpdateDownloading = true
        systemUpdateDownloadProgress = 0
        showSystemUpdateState(downloading = true, bottomAction = "downloading")
        val progressBar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressSystemUpdate)
        val tvProgress = findViewById<TextView>(R.id.tvSystemUpdateProgress)
        val btnAction = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSystemUpdateAction)
        progressBar.progress = 0
        tvProgress.text = "Downloading... 0%"
        val tag = info.tagName
        UpdateManager.downloadApkDirect(this, info.apkUrl, UpdateManager.fileNameFor(tag),
            onProgress = { p ->
                systemUpdateDownloadProgress = p
                progressBar.progress = p
                tvProgress.text = "Downloading... $p%"
                if (systemUpdateBottomAction == "downloading") btnAction.text = "Downloading… $p%"
            },
            onComplete = { file ->
                // No auto-install: the pill flips to Install, file removed
                // automatically after install / next day via cleanup
                onDownloadComplete(tag, file, silent = false)
                showSystemUpdateState(updateAvailable = true, bottomAction = "install")
            },
            onError = { err ->
                systemUpdateDownloading = false
                showSystemUpdateState(error = err)
            }
        )
    }

    private fun setSystemUpdateProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        systemUpdatePanel.animate().cancel()
        settingsPanel.animate().cancel()
        settingsScrim.animate().cancel()
        radiusAnimator?.cancel()
        systemUpdatePanel.translationX = width * p
        settingsScrim.alpha = 0.35f * (1f - p)
        settingsPanel.translationX = -slide * (1f - p)
        settingsPanel.scaleX = 1f
        settingsPanel.scaleY = 1f
        setSystemUpdateRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setSystemUpdateRadius(radius: Float) {
        panelRadius = radius
        systemUpdatePanel.invalidateOutline()
        detailPanel.invalidateOutline()
        settingsPanel.invalidateOutline()
        if (::aboutPanel.isInitialized) aboutPanel.invalidateOutline()
        if (::appearancePanel.isInitialized) appearancePanel.invalidateOutline()
        if (::accountPanel.isInitialized) accountPanel.invalidateOutline()
    }

    private fun animateSystemUpdateTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        systemUpdatePanelBusy = true
        if (open) {
            settingsScrim.visibility = View.VISIBLE
            settingsScrim.alpha = 0f
            systemUpdatePanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    systemUpdatePanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            settingsPanel.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            settingsScrim.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            systemUpdatePanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    systemUpdatePanel.visibility = View.GONE
                    systemUpdatePanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            settingsPanel.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            settingsScrim.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).withEndAction { settingsScrim.visibility = View.GONE }.start()
        }
    }

    private fun isBiometricAvailable(): Boolean {
        val mgr = androidx.biometric.BiometricManager.from(this)
        return mgr.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showAppLockPrompt() {
        if (!prefs.getBoolean("app_lock_enabled", false)) return
        if (!prefs.getBoolean("task_lock_app", true)) return
        if (!isBiometricAvailable()) return
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = androidx.biometric.BiometricPrompt(this, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    hideLockOverlay()
                    // Re-trigger data load if needed
                    if (peopleList.isEmpty()) {
                        attachListener()
                        attachCountListener()
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Don't close app automatically - keep lock screen, user can retry via Unlock button
                    // Stay on lock overlay
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock BroDue")
            .setSubtitle("Use fingerprint to open app")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        try { prompt.authenticate(info) } catch (_: Exception) {}
    }

    // ---------- Backup and Restore Panel ----------
    private val restoreLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(this, "Reading backup...", Toast.LENGTH_SHORT).show()
            BackupManager.loadBackup(this, uri) { data, needsPin, err ->
                runOnUiThread {
                    if (needsPin) {
                        // PIN protected - ask for the same PIN used to create it
                        PinSheet.showVerify(
                            this,
                            "Enter PIN",
                            "Enter the 6-digit PIN used for this backup",
                            onConfirmed = { pin ->
                                BackupManager.loadWithPin(this, uri, pin) { d, e2 ->
                                    runOnUiThread {
                                        if (d == null) {
                                            Toast.makeText(this, e2 ?: getString(R.string.save_error), Toast.LENGTH_SHORT).show()
                                            return@runOnUiThread
                                        }
                                        askReplaceOrMerge(d)
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
                    askReplaceOrMerge(data)
                }
            }
        }
    }

    private fun askReplaceOrMerge(data: Map<String, Any?>) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setMessage("Choose how to restore the .brodue backup:")
            .setPositiveButton("Replace") { _, _ ->
                Toast.makeText(this, "Replacing...", Toast.LENGTH_SHORT).show()
                BackupManager.applyWithRules(this, data, "replace") { ok ->
                    runOnUiThread { handleRestoreResult(ok) }
                }
            }
            .setNegativeButton("Merge") { _, _ ->
                Toast.makeText(this, "Merging...", Toast.LENGTH_SHORT).show()
                BackupManager.applyWithRules(this, data, "merge") { ok ->
                    runOnUiThread { handleRestoreResult(ok) }
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun handleRestoreResult(ok: Boolean) {
        if (ok) {
            Toast.makeText(this, "Data restored", Toast.LENGTH_SHORT).show()
            refreshAfterRestore()
        } else {
            Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
        }
    }

    // Animated expand/collapse shared by settings pages
    private fun animateViewHeight(view: View, show: Boolean, onDone: () -> Unit = {}) {
        if (show) {
            if (view.visibility == View.VISIBLE) { onDone(); return }
            view.visibility = View.VISIBLE
            view.alpha = 0f
            // Pin height to 0 synchronously: otherwise the first laid-out
            // frame is full-height (invisible but pushing siblings = jerk)
            view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply { height = 0 }
            view.requestLayout()
            // Wait one layout pass so width is correct before measuring,
            // otherwise the target height gets over-measured and glitches
            view.post {
                val widthPx = view.width.takeIf { it > 0 }
                    ?: (view.parent as? ViewGroup)?.width?.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val target = view.measuredHeight.coerceAtLeast(1)
                ValueAnimator.ofInt(0, target).apply {
                    duration = 280L
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener {
                        val h = it.animatedValue as Int
                        view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply { height = h }
                        view.alpha = h.toFloat() / target
                        view.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            view.alpha = 1f
                            view.requestLayout()
                            onDone()
                        }
                    })
                    start()
                }
            }
        } else {
            if (view.visibility != View.VISIBLE) { onDone(); return }
            val startH = view.height.takeIf { it > 0 } ?: run { onDone(); return }
            ValueAnimator.ofInt(startH, 0).apply {
                duration = 240L
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    val h = it.animatedValue as Int
                    view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply { height = h }
                    view.alpha = h.toFloat() / startH
                    view.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        view.requestLayout()
                        onDone()
                    }
                })
                start()
            }
        }
    }

    fun setupBackup() {
        findViewById<View>(R.id.btnBackupBack).setOnClickListener { closeBackup() }

        // Grouped corners same as other settings pages: 18dp outer / 8dp inner, 4dp gaps
        fun styleGroup(list: List<com.google.android.material.card.MaterialCardView>) {
            val den = resources.displayMetrics.density
            val outer = 18 * den
            val inner = 8 * den
            list.forEachIndexed { i, card ->
                card.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == 0) outer else inner)
                    .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == 0) outer else inner)
                    .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == list.lastIndex) outer else inner)
                    .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == list.lastIndex) outer else inner)
                    .build()
                (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.topMargin = ((if (i == 0) 8f else 4f) * den).toInt()
                    card.layoutParams = lp
                }
            }
        }
        styleGroup(
            listOf(
                findViewById(R.id.cardBackup),
                findViewById(R.id.cardRestore)
            )
        )
        styleGroup(
            listOf(
                findViewById(R.id.cardAccountEnc),
                findViewById(R.id.cardPinEnc)
            )
        )

        // Encryption options - at least one must always stay on (account based is the baseline)
        val swAccount = findViewById<IosSwitch>(R.id.switchAccountEnc)
        val swPin = findViewById<IosSwitch>(R.id.switchPinEnc)
        var progAccount = false
        var progPin = false
        swAccount.isChecked = prefs.getBoolean("backup_account_enc", true)
        swPin.isChecked = prefs.getBoolean("backup_pin_enc", false)
        findViewById<View>(R.id.cardAccountEnc).setOnClickListener { swAccount.performClick() }
        findViewById<View>(R.id.cardPinEnc).setOnClickListener { swPin.performClick() }

        swAccount.setOnCheckedChangeListener { _, checked ->
            if (progAccount) return@setOnCheckedChangeListener
            if (!checked && !swPin.isChecked) {
                progAccount = true
                swAccount.isChecked = true
                progAccount = false
                Toast.makeText(this, "At least one encryption must stay on", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("backup_account_enc", checked).apply()
        }
        swPin.setOnCheckedChangeListener { _, checked ->
            if (progPin) return@setOnCheckedChangeListener
            if (checked) {
                // Ask for a 6-digit PIN (entered twice for verification).
                // The toggle stays ON only if the PIN was actually set.
                requestNewPin(
                    onSet = { pin ->
                        prefs.edit()
                            .putBoolean("backup_pin_enc", true)
                            .putString("backup_pin", pin)
                            .apply()
                    },
                    onCancel = {
                        progPin = true
                        swPin.isChecked = false
                        progPin = false
                    }
                )
            } else {
                if (!swAccount.isChecked) {
                    progPin = true
                    swPin.isChecked = true
                    progPin = false
                    Toast.makeText(this, "At least one encryption must stay on", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("backup_pin_enc", false).remove("backup_pin").apply()
            }
        }

        // ---------- Automatic Backup ----------
        val swAuto = findViewById<IosSwitch>(R.id.switchAutoBackup)
        val cardAutoToggle =
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardAutoBackup)
        val cardAutoEvery =
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardAutoEvery)
        val cardAutoKeep =
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardAutoKeep)
        val tvAutoSub = findViewById<TextView>(R.id.tvAutoBackupSub)
        val tvAutoEveryValue = findViewById<TextView>(R.id.tvAutoEveryValue)
        val tvAutoKeepValue = findViewById<TextView>(R.id.tvAutoKeepValue)
        var progAuto = false

        val autoDen = resources.displayMetrics.density
        val autoOuter = 18 * autoDen
        val autoInner = 8 * autoDen
        fun autoShape(top: Float, bottom: Float) =
            com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, top)
                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, top)
                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, bottom)
                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, bottom)
                .build()

        // Static end-states: toggle top always outer; rows preset while invisible
        fun setAutoCorners(expanded: Boolean) {
            cardAutoToggle.shapeAppearanceModel =
                autoShape(autoOuter, if (expanded) autoInner else autoOuter)
            cardAutoEvery.shapeAppearanceModel = autoShape(autoInner, autoInner)
            cardAutoKeep.shapeAppearanceModel = autoShape(autoInner, autoOuter)
        }

        // Smoothly morphs the toggle's bottom radius with the rows
        fun animateAutoCorners(expand: Boolean) {
            if (expand) {
                cardAutoEvery.shapeAppearanceModel = autoShape(autoInner, autoInner)
                cardAutoKeep.shapeAppearanceModel = autoShape(autoInner, autoOuter)
            }
            val (from, to) = if (expand) autoOuter to autoInner else autoInner to autoOuter
            ValueAnimator.ofFloat(from, to).apply {
                duration = if (expand) 280L else 240L
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    cardAutoToggle.shapeAppearanceModel =
                        autoShape(autoOuter, it.animatedValue as Float)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        setAutoCorners(expand)
                    }
                })
                start()
            }
        }

        fun refreshAutoBackupUI(animate: Boolean = false) {
            val s = AutoBackup.settings(this)
            if (swAuto.isChecked != s.enabled) {
                progAuto = true
                swAuto.isChecked = s.enabled
                progAuto = false
            }
            val everyLabel = AutoBackup.intervalLabel(s.intervalH)
            tvAutoEveryValue.text = everyLabel
            tvAutoKeepValue.text = s.keep.toString()
            val lastAt = AutoBackup.lastBackupAt(this)
            val lastSize = AutoBackup.formatSize(AutoBackup.lastBackupSize(this))
            val lastStr = if (lastAt > 0) {
                " · Last: ${Formatters.date(lastAt)}, ${Formatters.time(lastAt)}" +
                    (if (lastSize.isNotBlank()) " · $lastSize" else "")
            } else {
                " · Last: never"
            }
            tvAutoSub.text =
                if (s.enabled) "Every $everyLabel · Keeps last ${s.keep}$lastStr" else "Off"
            if (!animate) {
                cardAutoEvery.visibility = if (s.enabled) View.VISIBLE else View.GONE
                cardAutoKeep.visibility = if (s.enabled) View.VISIBLE else View.GONE
                setAutoCorners(s.enabled)
                return
            }
            if (s.enabled) {
                animateAutoCorners(true)
                animateViewHeight(cardAutoEvery, true)
                animateViewHeight(cardAutoKeep, true)
            } else {
                animateAutoCorners(false)
                animateViewHeight(cardAutoEvery, false)
                animateViewHeight(cardAutoKeep, false)
            }
        }
        refreshAutoBackupUI()

        findViewById<View>(R.id.cardAutoBackup).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            swAuto.performClick()
        }
        swAuto.setOnCheckedChangeListener { _, checked ->
            if (progAuto) return@setOnCheckedChangeListener
            // Turning OFF deletes nothing - existing backups are kept
            AutoBackup.setEnabled(this, checked)
            refreshAutoBackupUI(animate = true)
            AutoBackupScheduler.schedule(this)
            if (checked) {
                // Opportunity: a due backup may be waiting; enforce the
                // keep-limit immediately either way
                Thread {
                    try {
                        AutoBackup.enforceNow(applicationContext)
                        AutoBackup.runIfDueSync(applicationContext)
                    } catch (_: Exception) {}
                }.start()
            }
        }
        cardAutoEvery.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val s = AutoBackup.settings(this)
            val options = AutoBackup.intervalOptions.map { opt -> opt.second }
            val sel = AutoBackup.intervalOptions
                .indexOfFirst { opt -> opt.first == s.intervalH }.coerceAtLeast(0)
            supportFragmentManager.setFragmentResultListener(REQ_AUTO_EVERY, this) { _, b ->
                val idx = b.getInt(SingleChoiceSheet.EXTRA_INDEX, -1)
                if (idx in options.indices) {
                    AutoBackup.setInterval(this, AutoBackup.intervalOptions[idx].first)
                    refreshAutoBackupUI()
                    AutoBackupScheduler.schedule(this)
                }
            }
            SingleChoiceSheet.newInstance(
                REQ_AUTO_EVERY, getString(R.string.backup_every), options, sel
            ).show(supportFragmentManager, SingleChoiceSheet.TAG)
        }
        cardAutoKeep.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val s = AutoBackup.settings(this)
            val options = (1..10).map { n -> "$n" }
            supportFragmentManager.setFragmentResultListener(REQ_AUTO_KEEP, this) { _, b ->
                val idx = b.getInt(SingleChoiceSheet.EXTRA_INDEX, -1)
                if (idx in options.indices) {
                    AutoBackup.setKeep(this, idx + 1)
                    refreshAutoBackupUI()
                    AutoBackupScheduler.schedule(this)
                    // New limit applies now, not on the next backup run
                    Thread {
                        try {
                            AutoBackup.enforceNow(applicationContext)
                        } catch (_: Exception) {}
                    }.start()
                }
            }
            SingleChoiceSheet.newInstance(
                REQ_AUTO_KEEP, getString(R.string.store_last_backups), options, s.keep - 1
            ).show(supportFragmentManager, SingleChoiceSheet.TAG)
        }

        findViewById<View>(R.id.cardBackup).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val accountEnc = prefs.getBoolean("backup_account_enc", true)
            val pinEnc = prefs.getBoolean("backup_pin_enc", false)
            val pin = if (pinEnc) prefs.getString("backup_pin", null) else null
            if (pinEnc && pin.isNullOrBlank()) {
                // PIN toggled on but not set - ask now
                requestNewPin(onSet = { newPin ->
                    prefs.edit().putString("backup_pin", newPin).apply()
                    doBackup(accountEnc, newPin, pinEnc = true)
                })
                return@setOnClickListener
            }
            doBackup(accountEnc, pin, pinEnc)
        }
        findViewById<View>(R.id.cardRestore).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            try {
                restoreLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 6-digit PIN drawer (Set a PIN -> Confirm PIN, Finish greyed until both match)
    private fun requestNewPin(onSet: (String) -> Unit, onCancel: () -> Unit = {}) {
        PinSheet.showSetup(this, onSet = onSet, onCancel = onCancel)
    }

    private fun doBackup(accountEnc: Boolean, pin: String?, pinEnc: Boolean) {
        val flags = (if (accountEnc) BackupManager.FLAG_ACCOUNT else 0) or
            (if (pinEnc) BackupManager.FLAG_PIN else 0)
        Toast.makeText(this, "Creating backup...", Toast.LENGTH_SHORT).show()
        BackupManager.createBackup(this, flags, pin) { ok, name ->
            runOnUiThread {
                if (ok && name != null) {
                    Toast.makeText(
                        this,
                        "Backup saved to Download/BroDue/_backup/${BackupManager.accountDir()}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // After a restore, reload everything from local storage
    private fun refreshAfterRestore() {
        logsEntriesCache.clear()
        txListCache.clear()
        logsListLoaded = false
        listLoaded = false
        peopleList = emptyList()
        onLogsPage = false
        CurrencyManager.clearCache()
        refreshLocalData()
    }

    fun openBackup() {
        if (backupPanelBusy) return
        if (backupPanel.visibility == View.VISIBLE) return
        backupPanel.visibility = View.VISIBLE
        backupPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setBackupRadius(maxRadiusPx)
        animateBackupTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
    }

    fun closeBackup() {
        animateBackupTo(open = false)
    }

    private fun setBackupProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        backupPanel.animate().cancel()
        settingsPanel.animate().cancel()
        settingsScrim.animate().cancel()
        radiusAnimator?.cancel()
        backupPanel.translationX = width * p
        settingsScrim.alpha = 0.35f * (1f - p)
        settingsPanel.translationX = -slide * (1f - p)
        settingsPanel.scaleX = 1f
        settingsPanel.scaleY = 1f
        setBackupRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setBackupRadius(radius: Float) {
        panelRadius = radius
        backupPanel.invalidateOutline()
        detailPanel.invalidateOutline()
        settingsPanel.invalidateOutline()
        appearancePanel.invalidateOutline()
        aboutPanel.invalidateOutline()
    }

    private fun animateBackupTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        backupPanelBusy = true
        if (open) {
            settingsScrim.visibility = View.VISIBLE
            settingsScrim.alpha = 0f
            backupPanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    backupPanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            settingsPanel.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            settingsScrim.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            backupPanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    backupPanel.visibility = View.GONE
                    backupPanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            settingsScrim.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { settingsScrim.visibility = View.GONE }
                .start()
            settingsPanel.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            if (settingsPanel.visibility != View.VISIBLE) {
                scrimView.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                homeContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                logsContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                bottomNav.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            } else {
                scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
    }

    // ---------- Currency Panel ----------
    fun setupCurrency() {
        findViewById<View>(R.id.btnCurrencyBack).setOnClickListener { closeCurrency() }
        refreshCurrencyUi()
    }

    fun refreshCurrencyUi() {
        // List with currently selected currency marked
        val rv = findViewById<RecyclerView>(R.id.rvCurrency)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = CurrencyAdapter(CurrencyManager.currencies) { selected -> onCurrencySelected(selected) }
        val sym = CurrencyManager.getSymbol(this)
        CurrencyManager.currencies.find { it.symbol == sym }?.let { adapter.setSelected(it) }
        rv.adapter = adapter
    }

    fun onCurrencySelected(selected: Currency) {
        // Instantly update local + database, no continue button
        CurrencyManager.saveLocal(this, selected)
        Formatters.setCurrencySymbol(selected.symbol)
        CurrencyManager.saveToDatabase(this, selected) { _ -> }
        // Selection made - release forced mode so the user can now dismiss the page
        currencyForced = false
        findViewById<View>(R.id.btnCurrencyBack)?.visibility = View.VISIBLE
        (currencyPanel as? SwipeCloseLayout)?.swipeEnabled = true
        updateSwipeEnabled()
        refreshCurrencyUi()
        adapter.notifyDataSetChanged()
        logsAdapter.notifyDataSetChanged()
        transactionAdapter.notifyDataSetChanged()
        updateLogsCard()
        updateNetCard()
        setupSettingsList()
    }

    fun openCurrency(force: Boolean = false) {
        if (currencyPanelBusy) return
        if (currencyPanel.visibility == View.VISIBLE) return
        currencyForced = force
        setupCurrency()
        findViewById<View>(R.id.btnCurrencyBack).visibility = if (force) View.GONE else View.VISIBLE
        (currencyPanel as SwipeCloseLayout).swipeEnabled = !force
        currencyPanel.visibility = View.VISIBLE
        currencyPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setCurrencyRadius(maxRadiusPx)
        animateCurrencyTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
    }

    fun closeCurrency() {
        if (currencyForced) return
        animateCurrencyTo(open = false)
    }

    private fun setCurrencyProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        currencyPanel.animate().cancel()
        settingsPanel.animate().cancel()
        settingsScrim.animate().cancel()
        radiusAnimator?.cancel()
        currencyPanel.translationX = width * p
        settingsScrim.alpha = 0.35f * (1f - p)
        settingsPanel.translationX = -slide * (1f - p)
        settingsPanel.scaleX = 1f
        settingsPanel.scaleY = 1f
        setCurrencyRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setCurrencyRadius(radius: Float) {
        panelRadius = radius
        currencyPanel.invalidateOutline()
        detailPanel.invalidateOutline()
        settingsPanel.invalidateOutline()
        appearancePanel.invalidateOutline()
        aboutPanel.invalidateOutline()
    }

    private fun animateCurrencyTo(open: Boolean, duration: Long = 380L) {
        if (!open && currencyForced) return
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        currencyPanelBusy = true
        if (open) {
            settingsScrim.visibility = View.VISIBLE
            settingsScrim.alpha = 0f
            currencyPanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    currencyPanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            settingsPanel.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            settingsScrim.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            currencyPanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    currencyPanel.visibility = View.GONE
                    currencyPanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            settingsScrim.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { settingsScrim.visibility = View.GONE }
                .start()
            settingsPanel.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            if (settingsPanel.visibility != View.VISIBLE) {
                scrimView.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                homeContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                logsContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                bottomNav.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            } else {
                scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
    }

    fun openAccount() {
        if (accountPanelBusy) return
        if (accountPanel.visibility == View.VISIBLE) return
        // Refresh info in case user changed
        setupAccount()
        accountPanel.visibility = View.VISIBLE
        accountPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
        setAccountRadius(maxRadiusPx)
        animateAccountTo(open = true)
        updateBackCallback()
        updateSwipeEnabled()
    }

    fun closeAccount() {
        animateAccountTo(open = false)
    }

    private fun setAccountProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        accountPanel.animate().cancel()
        settingsPanel.animate().cancel()
        accountScrim.animate().cancel()
        radiusAnimator?.cancel()
        accountPanel.translationX = width * p
        accountScrim.alpha = 0.35f * (1f - p)
        settingsPanel.translationX = -slide * (1f - p)
        settingsPanel.scaleX = 1f
        settingsPanel.scaleY = 1f
        setAccountRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setAccountRadius(radius: Float) {
        panelRadius = radius
        accountPanel.invalidateOutline()
        detailPanel.invalidateOutline()
        settingsPanel.invalidateOutline()
        appearancePanel.invalidateOutline()
        aboutPanel.invalidateOutline()
    }

    private fun animateAccountTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        accountPanelBusy = true
        if (open) {
            accountScrim.visibility = View.VISIBLE
            accountScrim.alpha = 0f
            accountPanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    accountPanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            settingsPanel.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            accountScrim.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            accountPanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    accountPanel.visibility = View.GONE
                    accountPanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            accountScrim.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { accountScrim.visibility = View.GONE }
                .start()
            settingsPanel.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            if (settingsPanel.visibility != View.VISIBLE) {
                scrimView.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                homeContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                logsContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                bottomNav.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            } else {
                scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
    }

    private fun setAboutProgress(progress: Float) {
        clearPanelBusy()
        val p = progress.coerceIn(0f, 1f)
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        aboutPanel.animate().cancel()
        settingsPanel.animate().cancel()
        settingsScrim.animate().cancel()
        radiusAnimator?.cancel()
        aboutPanel.translationX = width * p
        settingsScrim.alpha = 0.35f * (1f - p)
        settingsPanel.translationX = -slide * (1f - p)
        settingsPanel.scaleX = 1f
        settingsPanel.scaleY = 1f
        setAboutRadius(maxRadiusPx * (p / 0.01f).coerceIn(0f, 1f))
    }

    private fun setAboutRadius(radius: Float) {
        panelRadius = radius
        aboutPanel.invalidateOutline()
        detailPanel.invalidateOutline()
        settingsPanel.invalidateOutline()
        appearancePanel.invalidateOutline()
    }

    private fun animateAboutTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        aboutPanelBusy = true
        if (open) {
            settingsScrim.visibility = View.VISIBLE
            settingsScrim.alpha = 0f
            aboutPanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    aboutPanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            settingsPanel.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            settingsScrim.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            aboutPanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    aboutPanel.visibility = View.GONE
                    aboutPanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            settingsScrim.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { if (appearancePanel.visibility != View.VISIBLE) settingsScrim.visibility = View.GONE }
                .start()
            settingsPanel.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            if (settingsPanel.visibility != View.VISIBLE) {
                scrimView.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                homeContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                logsContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                bottomNav.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            } else {
                scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
    }

    private fun animateAppearanceTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        appearancePanelBusy = true
        if (open) {
            settingsScrim.visibility = View.VISIBLE
            settingsScrim.alpha = 0f
            appearancePanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    appearancePanelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            settingsPanel.animate().translationX(-slide).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            settingsScrim.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            appearancePanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    appearancePanel.visibility = View.GONE
                    appearancePanelBusy = false
                    updateBackCallback()
                    updateSwipeEnabled()
                }
                .start()
            settingsScrim.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { settingsScrim.visibility = View.GONE }
                .start()
            settingsPanel.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            if (settingsPanel.visibility != View.VISIBLE) {
                scrimView.animate().alpha(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                homeContent.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
                bottomNav.animate().translationX(0f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            } else {
                scrimView.animate().alpha(0.35f).setDuration(duration).setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
    }

    private fun animatePanelTo(open: Boolean, duration: Long = 380L) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val slide = 56 * resources.displayMetrics.density
        panelBusy = true
        if (open) {
            detailPanel.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    panelBusy = false
                    updateSwipeEnabled()
                }
                .start()
            animatePanelRadius(0f, (duration * 0.01f).toLong(), (duration * 0.99f).toLong())
            homeContent.animate()
                .translationX(-slide)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            logsContent.animate()
                .translationX(-slide)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            bottomNav.animate()
                .translationX(-slide)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            scrimView.animate()
                .alpha(0.35f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        } else {
            detachTxListener()
            val closedPersonId = currentPerson?.id
            currentPerson = null
            detailPanel.animate()
                .translationX(width)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    detailPanel.visibility = View.GONE
                    panelBusy = false
                    archivedView = false
                    if (!offlineMode) setActionButtonsGreyed(false)
                    updateBackCallback()
                    updateSwipeEnabled()
                    // Viewport-visible entries count as seen; invisible ones
                    // keep their dots (app kill included - nothing runs then)
                    closedPersonId?.let { markViewportSeen(it) }
                    closedPersonId?.let { refreshPersonDot(it) }
                    // Repaint home rows
                    refreshLocalData()
                }
                .start()
            animatePanelRadius(maxRadiusPx, (duration * 0.01f).toLong())
            homeContent.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            logsContent.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            bottomNav.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            scrimView.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    private fun closePersonDetail() {
        animatePanelTo(open = false)
    }

    // Re-enable record-row animations briefly for a user add/remove.
    // (The animator stays null otherwise so opens/refreshes never blink.)
    // Same insert/remove animation the home list uses, for one submit only
    // (rvLogs normally has no animator so realtime refreshes never blink)
    fun animateNextLogsChange() {
        // Low-end: DiffUtil still swaps content, just without the animation
        if (UiUtils.reducedMotion(this)) return
        try {
            rvLogs.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                // Rebinds apply instantly; insert/remove still animate
                supportsChangeAnimations = false
            }
            mainHandler.postDelayed({
                try {
                    if (!isFinishing && !isDestroyed) rvLogs.itemAnimator = null
                } catch (_: Exception) {}
            }, 600)
        } catch (_: Exception) {}
    }

    fun animateNextRecordsChange() {
        if (UiUtils.reducedMotion(this)) return
        try {
            rvRecords.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
            mainHandler.postDelayed({
                try {
                    if (!isFinishing && !isDestroyed) rvRecords.itemAnimator = null
                } catch (_: Exception) {}
            }, 600)
        } catch (_: Exception) {}
    }

    // Detail totals come straight from the stored person record (which
    // mirrors the server document for synced persons) - entries are only
    // sorted/listed here, never summed.
    private fun readStoredDetailTotals(personId: String) {
        PersonTotals.ensureAll(this)
        val o = LocalStore.people(this).optJSONObject(personId)
        currentNet = o?.optLong("netAmount", 0L) ?: 0L
        currentReceived = o?.optLong("received", 0L) ?: 0L
        currentGave = o?.optLong("gave", 0L) ?: 0L
        currentReceivedCount = o?.optInt("receivedCount", 0) ?: 0
        currentGaveCount = o?.optInt("gaveCount", 0) ?: 0
    }

    private fun recalcTxTotals() {
        currentPerson?.let { readStoredDetailTotals(it.id) }
        // Keep sorted
        txListCache.sortWith(compareByDescending<Transaction> { if (it.savedAt > 0) it.savedAt else it.createdAt }.thenByDescending { it.id })
        // Per-person currency: DiffUtil can't see adapter-level changes, force rebind
        val sym = currentPerson?.let { CurrencyManager.symbolForPerson(this, it.id) }
        if (transactionAdapter.currencySymbol != sym) {
            transactionAdapter.currencySymbol = sym
            transactionAdapter.notifyDataSetChanged()
        }
        transactionAdapter.submitList(txListCache.toList())
        transactionHeaderAdapter.visible = txListCache.isNotEmpty()
        updateNetCard()
        rvRecords.isVisible = txListCache.isNotEmpty()
        recordsEmpty.isVisible = txListCache.isEmpty()
    }

    // Detail panel streams only THIS person's records from the flat transactions collection.
    // Initial snapshot loads silently FIRST so partial-sum updates never trigger the
    // rolling animation; the child listener then handles only real changes.
    private fun attachTxListener(personId: String) {
        // Local-first: read person's transactions from local storage
        detachTxListener()
        txListCache = mutableListOf()
        val txs = LocalStore.transactions(this)
        val keys = txs.keys()
        while (keys.hasNext()) {
            val txId = keys.next()
            val t = txs.optJSONObject(txId) ?: continue
            if (t.optString("personId", "") != personId) continue
            txListCache.add(
                Transaction(
                    txId,
                    t.optString("category", "General"),
                    t.optLong("amount", 0L),
                    t.optString("type", "gave"),
                    t.optString("note", ""),
                    t.optLong("createdAt", 0L),
                    t.optLong("savedAt", 0L),
                    t.optBoolean("unseen", false)
                )
            )
        }
        recalcTxTotals()
    }

    private fun detachTxListener() = Unit

    private var lastNetForAnim = 0L
    private var netFirstLoad = true
    private fun updateNetCard() {
        val old = lastNetForAnim
        val newVal = currentNet
        // Detail net follows the per-person currency override
        val detailSymbol = currentPerson?.let { CurrencyManager.symbolForPerson(this, it.id) }
            ?: Formatters.getCurrencySymbol()
        val fmt: (Long) -> CharSequence = { v -> Formatters.amountWithSymbol(v, detailSymbol) }
        val color = getColor(
            when {
                newVal > 0 -> R.color.positive
                newVal < 0 -> R.color.negative
                else -> R.color.text_primary
            }
        )
        // No animation on first load
        if (netFirstLoad) {
            netFirstLoad = false
            lastNetForAnim = newVal
            tvNetAmount.text = fmt(newVal)
            tvNetAmount.setTextColor(color)
            return
        }
        lastNetForAnim = newVal
        // If view not yet laid out, just set
        if (tvNetAmount.width == 0 || old == newVal) {
            // An identical roll is already playing (e.g. realtime echo right
            // after save) - touching the text would flash final then roll back
            if (old == newVal && UiUtils.isRollingTo(tvNetAmount, newVal)) return
            UiUtils.cancelRoll(tvNetAmount)
            tvNetAmount.text = fmt(newVal)
            tvNetAmount.setTextColor(color)
            return
        }
        UiUtils.rollingAmount(
            tvNetAmount, old, newVal, fmt,
            tvNetAmount.currentTextColor, color
        )
    }

    private fun openNetBalance() {
        val person = currentPerson ?: return
        showSheet(
            NetBalanceSheet.newInstance(
                person.id,
                person.name,
                person.mobile,
                currentNet,
                currentReceived,
                currentGave,
                currentReceivedCount,
                currentGaveCount,
                ArrayList(transactionAdapter.currentList),
                onArchived = { closePersonDetail() },
                archived = archivedView
            ),
            NetBalanceSheet.TAG
        )
    }

    // Long-press on a home-page person card -> open the same i-icon drawer
    private fun openNetBalanceFor(person: Person) {
        try {
            // Stored person record (mirrors the server document for synced
            // persons) - no transaction scan.
            PersonTotals.ensureAll(this)
            val o = LocalStore.people(this).optJSONObject(person.id)
            val net = o?.optLong("netAmount", person.netAmount.toLong()) ?: person.netAmount.toLong()
            val received = o?.optLong("received", 0L) ?: 0L
            val gave = o?.optLong("gave", 0L) ?: 0L
            val receivedCount = o?.optInt("receivedCount", 0) ?: 0
            val gaveCount = o?.optInt("gaveCount", 0) ?: 0
            showSheet(
                NetBalanceSheet.newInstance(
                    person.id, person.name, person.mobile,
                    net, received, gave, receivedCount, gaveCount,
                    arrayListOf(),
                    onArchived = {},
                    archived = false
                ),
                NetBalanceSheet.TAG
            )
        } catch (e: Exception) {
            showSheet(
                NetBalanceSheet.newInstance(
                    person.id, person.name, person.mobile,
                    person.netAmount.toLong(), 0L, 0L, 0, 0,
                    arrayListOf(),
                    onArchived = {},
                    archived = false
                ),
                NetBalanceSheet.TAG
            )
        }
    }
    private fun openGraph() {
        val txs = transactionAdapter.currentList
        if (txs.isEmpty()) {
            showSheet(GraphSheet.newInstance(LongArray(0), 0L), GraphSheet.TAG)
            return
        }
        val sorted = txs.sortedBy { it.createdAt }
        val values = LongArray(sorted.size)
        var running = 0L
        sorted.forEachIndexed { i, tx ->
            // Same sign convention as the detail net + logs graph:
            // received plots positive (green), gave plots negative (red)
            running += if (tx.type == "received") tx.amount else -tx.amount
            values[i] = running
        }
        val lastDate = txs.maxOfOrNull { if (it.savedAt > 0) it.savedAt else it.createdAt } ?: 0L
        showSheet(GraphSheet.newInstance(values, lastDate), GraphSheet.TAG)
    }

    private var lastLogsEntries: List<LogEntry> = emptyList()
    private fun computeLogsGraph(entries: List<LogEntry>) {
        lastLogsEntries = entries
        val sorted = entries.sortedBy { it.tx.createdAt }
        val values = LongArray(sorted.size)
        var running = 0L
        sorted.forEachIndexed { i, e ->
            running += when (logsDisplayMode) {
                "pos" -> if (e.tx.type == "received") e.tx.amount else 0L
                "neg" -> if (e.tx.type == "gave") e.tx.amount else 0L
                "send" -> if (e.tx.type == "gave") e.tx.amount else 0L
                "receive" -> if (e.tx.type == "received") e.tx.amount else 0L
                else -> if (e.tx.type == "received") e.tx.amount else -e.tx.amount
            }
            // For neg, keep as positive cumulative for graph but display as -ve via color; if want negative, invert
            // Keep as is: pos/receive as positive, neg/send as positive cumulative (red)
            values[i] = running
        }
        logsGraphValues = values
        logsGraphLastDate = entries.maxOfOrNull { if (it.tx.savedAt > 0) it.tx.savedAt else it.tx.createdAt } ?: 0L
    }

    private fun recomputeLogsGraphForMode() {
        if (lastLogsEntries.isNotEmpty()) computeLogsGraph(lastLogsEntries)
    }

    private fun openLogsGraph() {
        if (logsGraphValues.isEmpty()) {
            showSheet(GraphSheet.newInstance(LongArray(0), 0L, logsDisplayMode), GraphSheet.TAG)
            return
        }
        showSheet(GraphSheet.newInstance(logsGraphValues, logsGraphLastDate, logsDisplayMode), GraphSheet.TAG)
    }

    // Synced persons save database-first: no entries without internet.
    // Local persons keep working, except their sync button needs internet.
    private fun refreshSyncedOnlineState() {
        try {
            if (archivedView || offlineMode) return
            if (!::detailPanel.isInitialized || detailPanel.visibility != View.VISIBLE) return
            val pid = currentPerson?.id ?: return
            // Locked stays grey+dead regardless of connectivity
            if (ShareSync.isSynced(this, pid) &&
                ShareSync.syncedCount(this) > ShareSync.cachedSyncCap(this)
            ) {
                setActionButtonsGreyed(true)
                findViewById<View>(R.id.wrapReceived)?.isEnabled = false
                findViewById<View>(R.id.wrapGave)?.isEnabled = false
                (findViewById<View>(R.id.btnReceived) as? MaterialButton)?.isEnabled = false
                (findViewById<View>(R.id.btnGave) as? MaterialButton)?.isEnabled = false
                return
            }
            val online = NetworkUtils.isOnline(this)
            setActionButtonsGreyed(ShareSync.isSynced(this, pid) && !online)
            val btnSync = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDetailSync)
            val syncOffline = !ShareSync.isSynced(this, pid) && !online
            btnSync.isEnabled = !syncOffline
            btnSync.iconTint = android.content.res.ColorStateList.valueOf(
                getColor(if (syncOffline) R.color.grey_soft else R.color.text_primary)
            )
        } catch (_: Exception) {}
    }

    private fun openMoney(direction: String) {
        if (archivedView) return
        val person = currentPerson ?: return
        if (ShareSync.isSynced(this, person.id) &&
            ShareSync.syncedCount(this) > ShareSync.cachedSyncCap(this)
        ) {
            showSheet(SyncPausedSheet.newInstance(), SyncPausedSheet.TAG)
            return
        }
        if (ShareSync.isSynced(this, person.id) && !NetworkUtils.isOnline(this)) {
            Toast.makeText(this, R.string.no_internet_save, Toast.LENGTH_SHORT).show()
            return
        }
        TaskLockGuard.gate(this, "add", "Add Entry") {
            showSheet(
                MoneySheet.newInstance(person.id, person.name, direction),
                MoneySheet.TAG
            )
        }
    }

    private fun openRecordDetail(tx: Transaction) {
        val person = currentPerson ?: return
        showSheet(
            RecordDetailSheet.newInstance(tx, person.id, readOnly = archivedView),
            RecordDetailSheet.TAG
        )
    }

    fun showSheet(fragment: DialogFragment, tag: String) {
        val showing = supportFragmentManager.fragments.any {
            it is DialogFragment && it.dialog?.isShowing == true
        }
        if (showing) return
        fragment.show(supportFragmentManager, tag)
    }

    // Runs an action once all sheets are fully dismissed (works around showSheet's
    // isShowing guard rejecting while the previous sheet is still animating out)
    fun runWhenSheetsClosed(delayMs: Long = 250L, action: () -> Unit) {
        fun poll() {
            val anyShowing = supportFragmentManager.fragments.any {
                it is DialogFragment && it.dialog?.isShowing == true
            }
            if (anyShowing) mainHandler.postDelayed({ poll() }, 100) else action()
        }
        mainHandler.postDelayed({ poll() }, delayMs)
    }

    fun openArchiveSheet() {
        showSheet(
            ArchiveBottomSheet.newInstance { person -> openArchivedPerson(person) },
            ArchiveBottomSheet.TAG
        )
    }

    fun openAppInfo() {
        showSheet(GlobalStatsSheet.newInstance(offlineSnapshot), GlobalStatsSheet.TAG)
    }

    private class SettingsAdapter(
        private val items: List<Triple<String, String, Int>>,
        private val badgeTitles: Set<String> = emptySet(),
        private val onClick: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SettingsAdapter.VH>() {
        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val imgIcon: android.widget.ImageView = view.findViewById(R.id.imgSettingIcon)
            val tvName: TextView = view.findViewById(R.id.tvSettingName)
            val tvDesc: TextView = view.findViewById(R.id.tvSettingDesc)
            val dot: View = view.findViewById(R.id.settingDot)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
            return VH(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, desc, iconRes) = items[position]
            holder.tvName.text = name
            holder.tvDesc.text = desc
            holder.imgIcon.setImageResource(iconRes)
            holder.dot.visibility = if (name in badgeTitles) View.VISIBLE else View.GONE
            // Minimal space between settings: 4dp top for all except first
            val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = if (position == 0) 0 else (4 * holder.itemView.resources.displayMetrics.density).toInt()
            holder.itemView.layoutParams = lp
            // Grouped corners: first top rounded, last bottom, middle none
            val card = holder.itemView as com.google.android.material.card.MaterialCardView
            val outerRadius = 18 * holder.itemView.resources.displayMetrics.density
            val innerRadius = 8 * holder.itemView.resources.displayMetrics.density
            val shape = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == 0) outerRadius else innerRadius)
                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == 0) outerRadius else innerRadius)
                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == itemCount - 1) outerRadius else innerRadius)
                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == itemCount - 1) outerRadius else innerRadius)
                .build()
            card.shapeAppearanceModel = shape
            holder.itemView.setOnClickListener { onClick(name) }
        }
    }

    private class AppearanceAdapter(
        private val items: List<Triple<String, String, Int>>,
        private val selectedKey: String = "Light",
        private val onClick: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<AppearanceAdapter.VH>() {
        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val card: com.google.android.material.card.MaterialCardView = view as com.google.android.material.card.MaterialCardView
            val imgIcon: android.widget.ImageView = view.findViewById(R.id.imgAppearanceIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppearanceName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_appearance, parent, false)
            return VH(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, _, iconRes) = items[position]
            holder.tvName.text = name
            holder.imgIcon.setImageResource(iconRes)
            val isSelected = name == selectedKey
            val isEnabled = name == "Light"
            holder.card.setCardBackgroundColor(
                holder.itemView.context.getColor(if (isSelected) R.color.primary_container else R.color.white)
            )
            holder.card.strokeColor = holder.itemView.context.getColor(if (isSelected) R.color.primary else R.color.card_border)
            holder.card.strokeWidth = (1 * holder.itemView.context.resources.displayMetrics.density).toInt()
            holder.card.alpha = if (isEnabled) 1f else 0.45f
            holder.itemView.isClickable = isEnabled
            holder.itemView.isFocusable = isEnabled
            holder.tvName.setTextColor(holder.itemView.context.getColor(if (!isEnabled) R.color.grey_soft else if (isSelected) R.color.primary else R.color.navy_text))
            holder.imgIcon.imageTintList = holder.itemView.context.getColorStateList(if (!isEnabled) R.color.grey_soft else if (isSelected) R.color.primary else R.color.text_secondary)
            holder.itemView.setOnClickListener { onClick(name) }
        }
    }
}
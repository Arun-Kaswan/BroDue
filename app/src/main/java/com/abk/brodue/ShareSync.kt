package com.abk.brodue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

// Per-person sync & sharing on Cloud Firestore.
// Structure:
//   people-shared/{personId}              owner, name, mobile, netAmount, archived, createdAt, updatedAt, code
//   people-shared/{personId}/transactions/{txId}
//   sharing-codes/{code}                  personId, owner
//   access/{personId}/{uid}               role: "owner" | "read" | "write"

// Races any Firestore Task against a timeout tuned per call site.
// Late results are dropped; failures land in the normal onFailure path.
internal fun <T> Task<T>.timeout(ms: Long): Task<T> {
    val tcs = TaskCompletionSource<T>()
    val handler = Handler(Looper.getMainLooper())
    val timer = Runnable {
        try {
            tcs.trySetException(java.util.concurrent.TimeoutException("Request timed out"))
        } catch (_: Exception) {}
    }
    handler.postDelayed(timer, ms)
    addOnCompleteListener { task ->
        handler.removeCallbacks(timer)
        if (tcs.task.isComplete) return@addOnCompleteListener
        try {
            if (task.isSuccessful) tcs.trySetResult(task.result)
            else tcs.trySetException(task.exception ?: Exception("Request failed"))
        } catch (_: Exception) {}
    }
    return tcs.task
}

object ShareSync {

    // Offline builds (cloned repos without google-services.json) run fully
    // local: every cloud entry below returns early, and db() throws so any
    // unguarded path still fails safe inside existing try/catch blocks.
    fun isCloudEnabled(): Boolean = !BuildConfig.OFFLINE_MODE

    private fun db(): FirebaseFirestore {
        if (BuildConfig.OFFLINE_MODE) throw IllegalStateException("offline build")
        return FirebaseFirestore.getInstance()
    }

    private fun uid(): String = UserDb.requireUid()

    // Timeout tiers (ms), tuned per task below
    internal const val T_QUICK = 10_000L // single reads/writes (codes, members, flags)
    internal const val T_ACTION = 15_000L // user-waited joins/leaves/syncs
    internal const val T_SAVE = 20_000L // database-first entry saves (dialog locked)
    internal const val T_BULK = 30_000L // restores and startup pulls

    // Offline fallback cap; the live cap comes from fetchSyncCap()
    // (user-limits/{uid}.max, else config/limits.maxSyncedPersons, else this).
    // Change the fleet-wide number with ONE Console edit (config/limits),
    // or per-account with user-limits/{uid}. Server rules enforce truth.
    const val MAX_SYNCED_PERSONS = 25
    const val POOL_HARD_MAX = 100

    private const val PREF_SYNC_CAP = "sync_cap"
    private const val PREF_SYNC_CAP_AT = "sync_cap_at"
    private const val SYNC_CAP_TTL_MS = 24 * 60 * 60 * 1000L

    fun cachedSyncCap(ctx: Context): Int {
        return try {
            val c = LocalStore.getSetting(ctx, PREF_SYNC_CAP) as? Number ?: return MAX_SYNCED_PERSONS
            c.toInt().takeIf { it in 1..POOL_HARD_MAX } ?: MAX_SYNCED_PERSONS
        } catch (_: Exception) { MAX_SYNCED_PERSONS }
    }

    // Live cap listeners: config/limits + own override stream silently (1
    // read each at startup, then nothing until an admin actually edits).
    // Edits land on-device in seconds - no polling, no restarts, no resets.
    private var capGlobalListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var capUserListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var capGlobal: Int? = null
    private var capUser: Int? = null

    private fun recomputeCap(ctx: Context) {
        try {
            val cap = (capUser?.takeIf { it in 1..POOL_HARD_MAX }
                ?: capGlobal?.takeIf { it in 1..POOL_HARD_MAX }
                ?: MAX_SYNCED_PERSONS)
            LocalStore.setSetting(ctx, PREF_SYNC_CAP, cap)
            LocalStore.setSetting(ctx, PREF_SYNC_CAP_AT, System.currentTimeMillis())
            (ctx as? MainActivity)?.onSyncCapChanged()
        } catch (_: Exception) {}
    }

    fun startCapListener(ctx: Context) {
        try {
            val me = uid()
            stopCapListener()
            capGlobalListener = db().collection("config").document("limits")
                .addSnapshotListener { snap, _ ->
                    try {
                        capGlobal = if (snap != null && snap.exists()) {
                            (snap.get("maxSyncedPersons") as? Number)?.toInt()
                        } else {
                            null
                        }
                        recomputeCap(ctx)
                    } catch (_: Exception) {}
                }
            capUserListener = db().collection("user-limits").document(me)
                .addSnapshotListener { snap, _ ->
                    try {
                        capUser = if (snap != null && snap.exists()) {
                            (snap.get("max") as? Number)?.toInt()
                        } else {
                            null
                        }
                        recomputeCap(ctx)
                    } catch (_: Exception) {}
                }
        } catch (_: Exception) {}
    }

    fun stopCapListener() {
        try {
            capGlobalListener?.remove()
            capUserListener?.remove()
        } catch (_: Exception) {}
        capGlobalListener = null
        capUserListener = null
    }

    // Refresh the cap (≤2 tiny reads, at most once per 24h - otherwise the
    // cache serves with ZERO reads). Per-account override wins, then the
    // global value, then the fallback. The server rules enforce the truth
    // regardless, so a stale cache only affects UX copy. Always invokes onDone.
    fun fetchSyncCap(ctx: Context, onDone: (Int) -> Unit) {
        if (!isCloudEnabled()) { onDone(MAX_SYNCED_PERSONS); return }
        try {
            val at = (LocalStore.getSetting(ctx, PREF_SYNC_CAP_AT) as? Number)?.toLong() ?: 0L
            if (System.currentTimeMillis() - at < SYNC_CAP_TTL_MS) {
                onDone(cachedSyncCap(ctx))
                return
            }
        } catch (_: Exception) {}
        fun store(cap: Int) {
            try {
                LocalStore.setSetting(ctx, PREF_SYNC_CAP, cap)
                LocalStore.setSetting(ctx, PREF_SYNC_CAP_AT, System.currentTimeMillis())
            } catch (_: Exception) {}
            onDone(cap)
        }
        try {
            val me = uid()
            db().collection("user-limits").document(me).get().timeout(T_QUICK)
                .addOnSuccessListener { uSnap ->
                    val over = (uSnap.get("max") as? Number)?.toInt()
                        ?.takeIf { it in 1..POOL_HARD_MAX }
                    if (over != null) {
                        store(over)
                        return@addOnSuccessListener
                    }
                    db().collection("config").document("limits").get().timeout(T_QUICK)
                        .addOnSuccessListener { gSnap ->
                            store(
                                (gSnap.get("maxSyncedPersons") as? Number)?.toInt()
                                    ?.takeIf { it in 1..POOL_HARD_MAX } ?: MAX_SYNCED_PERSONS
                            )
                        }
                        .addOnFailureListener { onDone(cachedSyncCap(ctx)) }
                }
                .addOnFailureListener { onDone(cachedSyncCap(ctx)) }
        } catch (_: Exception) { onDone(cachedSyncCap(ctx)) }
    }

    // Field-path quoting: uids can contain spaces/specials (seen in the
    // wild), which break dotted update paths unless backtick-quoted.
    private fun fp(vararg parts: String): String = parts.joinToString(".") { seg ->
        if (seg.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) seg
        else "`" + seg.replace("\\", "\\\\").replace("`", "\\`") + "`"
    }

    // Cloudflare Worker that enforces rate-limited preview/join.
    // Keep in sync with worker/wrangler.toml `name` + your workers.dev subdomain.
    private const val WORKER_BASE = "https://brodue-join.abk-github-b.workers.dev"
    // Worker shared secret: injected at build time from gitignored
    // local.properties (worker.appSecret=...). Never hardcoded - cloned
    // repos build with "" and worker-authenticated calls stay inert.
    private val WORKER_APP_SECRET: String
        get() = try {
            BuildConfig.WORKER_APP_SECRET
        } catch (_: Exception) {
            ""
        }
    var lastWorkerError: String? = null
        private set

    // Slots doc: { slots: [personId | null × ≤25] } - index = syncSlot.
    private fun slotsRef(uidStr: String) =
        db().collection("user-sync-slots").document(uidStr)

    // Local mirror of our slot bindings (personId -> slot), so owner
    // merge-updates can re-send syncSlot and satisfy the quota pin.
    private const val SYNC_SLOTS = "sync_slots"

    fun localSyncSlot(ctx: Context, personId: String): Int? {
        return try {
            val s = LocalStore.getSetting(ctx, SYNC_SLOTS) as? String ?: return null
            val v = JSONObject(s).opt(personId) as? Number ?: return null
            v.toInt().takeIf { it in 0 until POOL_HARD_MAX }
        } catch (_: Exception) { null }
    }

    private fun setLocalSyncSlot(ctx: Context, personId: String, slot: Int) {
        try {
            val raw = LocalStore.getSetting(ctx, SYNC_SLOTS) as? String
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            obj.put(personId, slot)
            LocalStore.setSetting(ctx, SYNC_SLOTS, obj.toString())
        } catch (_: Exception) {}
    }

    fun syncedCount(ctx: Context): Int = syncedIds(ctx).size

    private fun limitMessage(ctx: Context, cap: Int = cachedSyncCap(ctx)): String = try {
        ctx.getString(R.string.sync_limit_reached, cap)
    } catch (_: Exception) {
        "Sync limit reached"
    }

    // Pool shape: {personId: index} map (legacy lists auto-migrate on touch).
    // Size IS the live count; releases remove keys. First free index < 100.
    private fun readPoolMap(snap: com.google.firebase.firestore.DocumentSnapshot): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        try {
            when (val raw = snap.get("slots")) {
                is Map<*, *> -> raw.forEach { (k, v) ->
                    val idx = (v as? Number)?.toInt()
                    if (k is String && k.isNotBlank() && idx != null && idx >= 0) out[k] = idx
                }
                is List<*> -> raw.forEachIndexed { i, v ->
                    if (v is String && v.isNotBlank()) out[v] = i
                }
            }
        } catch (_: Exception) {}
        return out
    }

    // Claim (or reuse) a sync slot for personId. Idempotent: a person that
    // already holds a slot keeps it, so resyncs never consume new slots.
    // onDone(slot >= 0) or onDone(-1) when full / onDone(-2) on error.
    fun claimSyncSlot(ctx: Context, personId: String, onDone: (Int) -> Unit) {
        if (!isCloudEnabled()) { onDone(-2); return }
        try {
            val me = uid()
            db().runTransaction { tx ->
                val snap = tx.get(slotsRef(me))
                val slots = if (snap.exists()) readPoolMap(snap) else mutableMapOf()
                slots[personId]?.let { return@runTransaction it }
                val used = slots.values.toSet()
                var idx = 0
                while (idx in used && idx < POOL_HARD_MAX) idx++
                if (idx >= POOL_HARD_MAX) return@runTransaction -1
                slots[personId] = idx
                tx.set(slotsRef(me), mapOf("slots" to slots))
                idx
            }.timeout(T_ACTION)
                .addOnSuccessListener {
                    if (it >= 0) setLocalSyncSlot(ctx, personId, it)
                    onDone(it)
                }
                .addOnFailureListener { onDone(-2) }
        } catch (_: Exception) { onDone(-2) }
    }

    // Server-owned count bumps after client-owned sync (+1) / disable+leave
    // (-1). Fire-and-forget: the worker writes user-counters (clients can
    // never touch it), skips +1 when already bound (idempotent resyncs),
    // and any drift self-heals at the next lockdown gate via recount.
    private fun bumpServerCount(ctx: Context, personId: String, op: Int) {
        try {
            workerPost("/countBump", JSONObject().put("personId", personId).put("op", op), 15000) { _, _ -> }
        } catch (_: Exception) {}
    }

    // One-time counter bootstrap (ground-truth recount server-side).
    // Flag-guarded: exactly one call ever per account.
    private const val PREF_COUNTER_INIT = "counter_init_v1"

    fun ensureCounterInit(ctx: Context) {
        if (!isCloudEnabled()) return
        try {
            if ((LocalStore.getSetting(ctx, PREF_COUNTER_INIT) as? Boolean) == true) return
            workerPost("/syncInit", JSONObject(), 15000) { ok, _ ->
                if (ok) {
                    try {
                        LocalStore.setSetting(ctx, PREF_COUNTER_INIT, true)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    // One-time pool migration (list -> {personId: index} map), gated before
    // the first pull cycle: legacy lists would fail the map-based count and
    // wrongly lock users out. Flag-guarded, so exactly one read ever.
    private const val PREF_POOL_SHAPE = "pool_shape_v1"

    fun ensurePoolShape(ctx: Context, onDone: () -> Unit) {
        if (!isCloudEnabled()) { onDone(); return }
        try {
            if ((LocalStore.getSetting(ctx, PREF_POOL_SHAPE) as? Boolean) == true) {
                onDone()
                return
            }
            val me = uid()
            slotsRef(me).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    try {
                        val raw = snap.get("slots")
                        if (raw is List<*>) {
                            val map = mutableMapOf<String, Int>()
                            raw.forEachIndexed { i, v ->
                                if (v is String && v.isNotBlank()) map[v] = i
                            }
                            slotsRef(me).set(mapOf("slots" to map))
                        }
                        LocalStore.setSetting(ctx, PREF_POOL_SHAPE, true)
                    } catch (_: Exception) {}
                    onDone()
                }
                .addOnFailureListener { onDone() }
        } catch (_: Exception) {
            onDone()
        }
    }

    // Best-effort slot release (disable/leave/delete). Removes the key, so
    // the pool size (the server-enforced count) drops. Never blocks UI.
    fun releaseSyncSlot(ctx: Context, personId: String) {
        try {
            val me = uid()
            slotsRef(me).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    try {
                        if (!snap.exists()) return@addOnSuccessListener
                        val slots = readPoolMap(snap)
                        if (slots.remove(personId) != null) {
                            slotsRef(me).set(mapOf("slots" to slots))
                        }
                    } catch (_: Exception) {}
                }
                .addOnFailureListener {}
        } catch (_: Exception) {}
    }

    // ---------- Worker helpers (Cloudflare) ----------
    private fun workerCall(
        path: String,
        code: String,
        profile: Map<String, String>? = null,
        onDone: (Boolean, JSONObject?) -> Unit
    ) {
        val payload = JSONObject().put("code", code)
        if (profile != null) payload.put("profile", JSONObject(profile as Map<*, *>))
        workerPost(path, payload, 15000, onDone)
    }

    // Ask the worker to recompute a person's 5 money aggregates from its
    // real transactions (the only writer allowed to set them). Fire-and-
    // forget: rate-limited server-side; pull-side trigger covers failures.
    private fun triggerRecalc(ctx: Context, personId: String) {
        try {
            workerPost("/recalc", JSONObject().put("personId", personId)) { _, _ -> }
        } catch (_: Exception) {}
    }

    // Push tokens for worker-sent notifications: { tokens: [..], updatedAt }.
    // Self-only rules; the worker reads via Admin SDK. Merged per device
    // (cap 10) so reinstalls and multi-device setups all stay reachable.
    fun registerPushToken(ctx: Context, token: String) {
        if (!isCloudEnabled()) return
        try {
            if (token.isBlank()) return
            val me = uid()
            val ref = db().collection("user-push").document(me)
            ref.get()
                .addOnSuccessListener { snap ->
                    try {
                        val cur = (snap.get("tokens") as? List<*>)
                            ?.mapNotNull { it as? String }?.toMutableList() ?: mutableListOf()
                        cur.remove(token)
                        cur.add(0, token)
                        val capped = cur.take(10)
                        ref.set(mapOf("tokens" to capped, "updatedAt" to System.currentTimeMillis()))
                    } catch (_: Exception) {}
                }
                .addOnFailureListener {}
        } catch (_: Exception) {}
    }

    fun refreshPushToken(ctx: Context) {
        if (!isCloudEnabled()) return
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { registerPushToken(ctx, it ?: "") }
                .addOnFailureListener {}
        } catch (_: Exception) {}
    }

    // Logout: drop this device's token so a signed-out phone stays silent.
    // Best-effort; reinstall-rot also prunes via UNREGISTERED cleanup.
    fun unregisterPushToken(ctx: Context) {
        if (!isCloudEnabled()) return
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    try {
                        if (token.isNullOrBlank()) return@addOnSuccessListener
                        val me = uid()
                        val ref = db().collection("user-push").document(me)
                        ref.get()
                            .addOnSuccessListener { snap ->
                                try {
                                    val cur = (snap.get("tokens") as? List<*>)
                                        ?.mapNotNull { it as? String }?.toMutableList()
                                        ?: return@addOnSuccessListener
                                    if (cur.remove(token)) {
                                        ref.set(mapOf("tokens" to cur))
                                    }
                                } catch (_: Exception) {}
                            }
                            .addOnFailureListener {}
                    } catch (_: Exception) {}
                }
                .addOnFailureListener {}
        } catch (_: Exception) {}
    }

    // One-time heal for pre-feature cloud values (which any client could
    // have set): recompute each synced person once, ever. Capped per
    // startup to respect the worker's hourly cap; the flag is set only on
    // success so failures retry next launch.
    private const val RECALC_HEALED = "recalc_healed"

    fun healAggregatesOnce(ctx: Context) {
        if (!isCloudEnabled()) return
        try {
            val raw = LocalStore.getSetting(ctx, RECALC_HEALED) as? String
            val healed = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            var queued = 0
            syncedIds(ctx).forEach { pid ->
                if (healed.optBoolean(pid, false)) return@forEach
                if (queued >= 5) return@forEach
                queued++
                workerPost("/recalc", JSONObject().put("personId", pid)) { ok, _ ->
                    if (ok) {
                        try {
                            healed.put(pid, true)
                            LocalStore.setSetting(ctx, RECALC_HEALED, healed.toString())
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun workerPost(
        path: String,
        payload: JSONObject,
        timeoutMs: Int = 15000,
        onDone: (Boolean, JSONObject?) -> Unit
    ) {
        if (!isCloudEnabled()) { onDone(false, null); return }
        try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) { onDone(false, JSONObject().put("error", "Sign in first.")); return }
            user.getIdToken(false)
                .addOnSuccessListener { res ->
                    val token = res.token
                    if (token.isNullOrBlank()) { onDone(false, null); return@addOnSuccessListener }
                    Thread {
                        try {
                            val url = java.net.URL("$WORKER_BASE$path")
                            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                                requestMethod = "POST"
                                doOutput = true
                                connectTimeout = timeoutMs
                                readTimeout = timeoutMs
                                setRequestProperty("Content-Type", "application/json")
                                if (WORKER_APP_SECRET.isNotBlank()) setRequestProperty("x-app-secret", WORKER_APP_SECRET)
                            }
                            payload.put("idToken", token)
                            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                            val body = try {
                                val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
                                stream.bufferedReader().readText()
                            } catch (_: Exception) { "" }
                            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject().put("error", body) }
                            try { android.util.Log.d("JoinWorker", "$path -> ${conn.responseCode} $body") } catch (_: Exception) {}
                            Handler(Looper.getMainLooper()).post { onDone(conn.responseCode in 200..299, json) }
                        } catch (e: Exception) {
                            try { android.util.Log.e("JoinWorker", "$path failed", e) } catch (_: Exception) {}
                            Handler(Looper.getMainLooper()).post { onDone(false, JSONObject().put("error", e.localizedMessage ?: "Network error")) }
                        }
                    }.start()
                }
                .addOnFailureListener { onDone(false, null) }
        } catch (_: Exception) { onDone(false, null) }
    }

    // ---------- Local sync-flag helpers ----------
    private const val SYNC_FLAGS = "sync_flags"

    fun isSynced(ctx: Context, personId: String): Boolean {
        val flags = LocalStore.getSetting(ctx, SYNC_FLAGS) as? String ?: return false
        return try { JSONObject(flags).optBoolean(personId, false) } catch (_: Exception) { false }
    }

    private fun setSynced(ctx: Context, personId: String, on: Boolean) {
        val flags = try { LocalStore.getSetting(ctx, SYNC_FLAGS) as? String } catch (_: Exception) { null }
        val obj = flags?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (on) obj.put(personId, true) else obj.remove(personId)
        LocalStore.setSetting(ctx, SYNC_FLAGS, obj.toString())
    }

    fun syncedIds(ctx: Context): List<String> {
        val flags = LocalStore.getSetting(ctx, SYNC_FLAGS) as? String ?: return emptyList()
        return try {
            val obj = JSONObject(flags)
            val out = mutableListOf<String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (obj.optBoolean(k, false)) out.add(k)
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    // A person is a cloud person if it came from people-shared
    // (dbOwner marker or a role entry for the current user)
    fun isCloudPerson(ctx: Context, personId: String): Boolean {
        return try {
            val o = LocalStore.people(ctx).optJSONObject(personId)
            (o?.optString("dbOwner", "")?.isNotBlank() == true) || accessRole(ctx, personId).isNotEmpty()
        } catch (_: Exception) { false }
    }

    fun cloudPersonIds(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val peopleObj = LocalStore.people(ctx)
            val keys = peopleObj.keys()
            while (keys.hasNext()) {
                val pid = keys.next()
                if (isCloudPerson(ctx, pid)) out.add(pid)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun setMembersInfo(ctx: Context, personId: String, infoObj: JSONObject) {
        val info = try { LocalStore.getSetting(ctx, "shared_info") as? String } catch (_: Exception) { null }
        val obj = info?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val personObj = obj.optJSONObject(personId) ?: JSONObject()
        personObj.put("membersInfo", infoObj.toString())
        obj.put(personId, personObj)
        LocalStore.setSetting(ctx, "shared_info", obj.toString())
    }

    // Resolve the creator of an entry: owner uid -> owner profile,
    // otherwise lookup in memberInfo. Returns (name, email, photoUrl)
    fun creatorProfile(ctx: Context, personId: String, creatorUid: String): Triple<String, String, String>? {
        if (!isCloudEnabled()) return null
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return null
            val ownerUid = p.optString("dbOwner", "")
            if (creatorUid.isNotEmpty() && creatorUid == ownerUid) {
                return Triple(
                    p.optString("ownerName", "").ifBlank { "Owner" },
                    p.optString("ownerEmail", ""),
                    p.optString("ownerPhotoUrl", "")
                )
            }
            if (creatorUid.isBlank()) return null
            val info = LocalStore.getSetting(ctx, "shared_info") as? String ?: return null
            val personObj = JSONObject(info).optJSONObject(personId) ?: return null
            val membersInfo = personObj.optString("membersInfo", "")
            if (membersInfo.isBlank()) return null
            val m = JSONObject(membersInfo).optJSONObject(creatorUid) ?: return null
            return Triple(
                m.optString("name", ""),
                m.optString("email", ""),
                m.optString("photoUrl", "")
            )
        } catch (_: Exception) {
            return null
        }
    }

    // DB-backed creator resolution - every viewer (owner or member) can read
    // the person doc, so this works across devices & after restart.
    // Returns (label, photoUrl) for the createdByUid (owner info for the owner,
    // memberInfo for members; uid short as last resort).
    fun resolveCreator(
        ctx: Context,
        personId: String,
        creatorUid: String,
        onDone: (String, String) -> Unit
    ) {
        try {
            db().collection("people-shared").document(personId).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    if (snap.exists()) {
                        // refresh local cache (ownerInfo + memberInfo) for offline fallback
                        applyRemotePerson(ctx, snap)
                        val ownerUid = snap.getString("owner") ?: ""
                        val info = snap.get("ownerInfo") as? Map<*, *>
                        val members = snap.get("members") as? Map<*, *>
                        val memberInfo = snap.get("memberInfo") as? Map<*, *>
                        val useOwner = creatorUid.isBlank() || creatorUid == ownerUid
                        if (useOwner) {
                            val name = info?.get("name") as? String ?: ""
                            val email = info?.get("email") as? String ?: ""
                            val photo = info?.get("photoUrl") as? String ?: ""
                            val label = when {
                                name.isNotBlank() && email.isNotBlank() -> "Created by $name · $email"
                                name.isNotBlank() -> "Created by $name"
                                else -> "Created by ${ownerUid.take(8)}"
                            }
                            onDone(label, photo)
                        } else {
                            val mInfo = memberInfo?.get(creatorUid) as? Map<*, *>
                            val name = mInfo?.get("name") as? String ?: ""
                            val email = mInfo?.get("email") as? String ?: ""
                            val photo = mInfo?.get("photoUrl") as? String ?: ""
                            val label = when {
                                name.isNotBlank() && email.isNotBlank() -> "Created by $name · $email"
                                name.isNotBlank() -> "Created by $name"
                                else -> "Created by ${creatorUid.take(8)}"
                            }
                            onDone(label, photo)
                        }
                    } else {
                        // offline / no db: fallback to locally cached resolution
                        val p = creatorProfile(ctx, personId, creatorUid)
                        if (p != null) {
                            val (name, email, photo) = p
                            val label = if (name.isNotBlank() && email.isNotBlank()) "Created by $name · $email"
                            else if (name.isNotBlank()) "Created by $name"
                            else "Created by ${creatorUid.take(8)}"
                            onDone(label, photo)
                        } else {
                            onDone("Created by ${creatorUid.take(8)}", "")
                        }
                    }
                }
                .addOnFailureListener {
                    val p = creatorProfile(ctx, personId, creatorUid)
                    onDone(
                        (p?.first ?: "").ifBlank { "Created by ${creatorUid.take(8)}" },
                        p?.third ?: ""
                    )
                }
        } catch (e: Exception) {
            onDone("Created by ${creatorUid.take(8)}", "")
        }
    }

    // Raw creator resolution (name/email/photo, no label formatting)
    fun resolveCreatorRaw(
        ctx: Context,
        personId: String,
        creatorUid: String,
        onDone: (String, String, String) -> Unit
    ) {
        resolveCreator(ctx, personId, creatorUid) { label, photo ->
            // strip the "Created by " prefix / extract email after " · "
            val parts = label.removePrefix("Created by ").split(" · ", limit = 2)
            onDone(parts[0], parts.getOrElse(1) { "" }, photo)
        }
    }

    // Resolve a member uid to "Name · email" (falls back to short uid)
    fun memberLabel(ctx: Context, personId: String, uidStr: String): String {
        val info = LocalStore.getSetting(ctx, "shared_info") as? String ?: return uidStr
        return try {
            val personObj = JSONObject(info).optJSONObject(personId) ?: return uidStr
            val membersInfo = personObj.optString("membersInfo", "")
            if (membersInfo.isBlank()) return uidStr
            val m = JSONObject(membersInfo).optJSONObject(uidStr) ?: return uidStr
            val name = m.optString("name", "")
            val email = m.optString("email", "")
            when {
                name.isNotBlank() && email.isNotBlank() -> "$name · $email"
                name.isNotBlank() -> name
                else -> uidStr.take(8)
            }
        } catch (_: Exception) { uidStr }
    }

    fun shareAsMe(ctx: Context, personId: String): Boolean {
        return try {
            LocalStore.people(ctx).optJSONObject(personId)?.optBoolean("shareAsMe", false) == true
        } catch (_: Exception) { false }
    }

    // Confirm-callback variant: visual state must wait for the database
    fun setShareAsMe(personId: String, on: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        try {
            db().collection("people-shared").document(personId).update("shareAsMe", on).timeout(T_QUICK)
                .addOnSuccessListener { onDone?.invoke(true) }
                .addOnFailureListener { onDone?.invoke(false) }
        } catch (_: Exception) {
            onDone?.invoke(false)
        }
    }

    // Optimistic local mirror (no cloud mirror-back). The toggle reads local,
    // so without this it looks stuck until the realtime echo returns.
    fun setLocalShareAsMe(ctx: Context, personId: String, on: Boolean) {
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return
            p.put("shareAsMe", on)
            LocalStore.persist(ctx)
        } catch (_: Exception) {}
    }

    fun accessRole(ctx: Context, personId: String): String {
        val info = LocalStore.getSetting(ctx, "shared_info") as? String ?: return ""
        return try {
            JSONObject(info).optJSONObject(personId)?.optString("role", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun setAccessRole(ctx: Context, personId: String, owner: String, role: String) {
        val info = try { LocalStore.getSetting(ctx, "shared_info") as? String } catch (_: Exception) { null }
        val obj = info?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        obj.put(personId, JSONObject().put("owner", owner).put("role", role))
        LocalStore.setSetting(ctx, "shared_info", obj.toString())
    }

    // ---------- Enable / disable sync ----------
    fun enableSync(ctx: Context, personId: String, onDone: (Boolean, String?) -> Unit) {
        if (!isCloudEnabled()) { onDone(false, null); return }
        val uidSelf = try { uid() } catch (e: Exception) { onDone(false, e.message); return }
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId)
            if (p == null) { onDone(false, "Person not found"); return }
            // Live cap first (UX fast-fail; the server rules enforce truth).
            // Resyncs of an already-synced person never consume a new slot.
            fetchSyncCap(ctx) { cap ->
                if (!isSynced(ctx, personId) && syncedCount(ctx) >= cap) {
                    onDone(false, limitMessage(ctx, cap)); return@fetchSyncCap
                }
                // Syncing pins an explicit currency locally first, so the
                // currency sent to the database is never the default fallback
                if (p.optString("currency", "").isBlank()) {
                    p.put("currency", CurrencyManager.getSymbol(ctx))
                    LocalStore.persist(ctx)
                }
                // One atomic transaction: claim the sync slot, check whether the
                // person doc exists yet, and create-or-merge accordingly. (A
                // standalone pre-read can't distinguish "missing doc" from a
                // rules-denied read, so it must live inside the transaction.)
                claimSlotAndWrite(ctx, personId, p, uidSelf, onDone)
            }
        } catch (e: Exception) {
            onDone(false, e.localizedMessage)
        }
    }

    // Atomic claim + create-or-merge. Returns (slot, fresh) on success,
    // -1 when the pool is full, -2 on error. Transaction retries make slot
    // races between two devices safe (claim-then-write can never orphan).
    private fun claimSlotAndWrite(
        ctx: Context, personId: String, p: JSONObject, uidSelf: String,
        onDone: (Boolean, String?) -> Unit
    ) {
        try {
            val fu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            // The 5 money aggregates are NEVER sent: fresh docs must carry
            // none (rules), resync merges must not touch them (rules), and
            // the worker /recalc computes the truth from real transactions.
            val doc = mapOf(
                "owner" to uidSelf,
                "members" to mapOf(uidSelf to "owner"),
                "code" to "",
                "ownerInfo" to mapOf(
                    "name" to (fu?.displayName ?: ""),
                    "email" to (fu?.email ?: ""),
                    "photoUrl" to (fu?.photoUrl?.toString() ?: "")
                ),
                "name" to p.optString("name", ""),
                "mobile" to p.optString("mobile", ""),
                "archived" to p.optBoolean("archived", false),
                "createdAt" to p.optLong("createdAt", 0L),
                "updatedAt" to p.optLong("updatedAt", 0L),
                    "currency" to p.optString("currency", "")
                        .ifBlank { CurrencyManager.getSymbol(ctx) },
                    // Preserve txUpdatedAt: bumping it on (re)sync dots every
                    // member with no new entries (phantom red dots). Fresh
                    // syncs (no stamp yet) still seed it with now.
                    "txUpdatedAt" to (p.optLong("txUpdatedAt", 0L)
                        .takeIf { it > 0 } ?: System.currentTimeMillis()),
                // Share-as-me defaults ON for every fresh sync
                "shareAsMe" to true,
                "units" to "minor"
            )
            db().runTransaction { tx ->
                // ALL reads first: Firestore aborts transactions that read
                // after writing ("all reads must be executed before writes").
                val personRef = db().collection("people-shared").document(personId)
                val slotsSnap = tx.get(slotsRef(uidSelf))
                val personSnap = tx.get(personRef)
                // 1) slot claim on the {personId: index} map (legacy lists
                // normalize on touch). Idempotent reuse keeps resyncs free.
                val slots = if (slotsSnap.exists()) readPoolMap(slotsSnap) else mutableMapOf()
                var slot = slots[personId]?.takeIf { it in 0 until POOL_HARD_MAX } ?: -1
                if (slot == -1) {
                    val used = slots.values.toSet()
                    var idx = 0
                    while (idx in used && idx < POOL_HARD_MAX) idx++
                    if (idx >= POOL_HARD_MAX) {
                        // Pool ceiling hit: commit nothing, report it
                        return@runTransaction mapOf("slot" to -1, "fresh" to false)
                    }
                    slots[personId] = idx
                    slot = idx
                }
                // 2) writes: slot pool + person doc (fresh create carries no
                // aggregates per the rules; resync merge leaves them alone).
                // (Count-vs-cap is enforced by the rules at create/join and
                // by the lockdown gates everywhere else - this stays atomic.)
                tx.set(slotsRef(uidSelf), mapOf("slots" to slots))
                val fresh = !personSnap.exists()
                val full = doc + mapOf("syncSlot" to slot)
                if (fresh) tx.set(personRef, full)
                else tx.set(personRef, full, com.google.firebase.firestore.SetOptions.merge())
                mapOf("slot" to slot, "fresh" to fresh)
            }.timeout(T_ACTION)
                .addOnSuccessListener { res ->
                    @Suppress("UNCHECKED_CAST")
                    val m = res as? Map<*, *>
                    val slot = (m?.get("slot") as? Number)?.toInt() ?: -2
                    val fresh = m?.get("fresh") as? Boolean ?: false
                    if (slot == -1) { onDone(false, limitMessage(ctx)); return@addOnSuccessListener }
                    if (slot < 0) { onDone(false, null); return@addOnSuccessListener }
                    setLocalSyncSlot(ctx, personId, slot)
                    // Server-owned count follows (worker-written; reconciled on drift)
                    bumpServerCount(ctx, personId, +1)
                    pushTransactions(ctx, personId)
                    setSynced(ctx, personId, true)
                    setAccessRole(ctx, personId, uidSelf, "owner")
                    setLocalShareCode(ctx, personId, "")
                    setLocalShareAsMe(ctx, personId, true)
                    runCatching {
                        val lp = LocalStore.people(ctx).optJSONObject(personId)
                        if (lp != null) {
                            lp.put("leftGroup", false)
                            val latest = lp.optLong("txUpdatedAt", 0L)
                            lp.put("txUpdatedAtLoaded", latest)
                            LocalStore.persist(ctx)
                        }
                    }
                    // Fresh docs start aggregateless: worker fills the truth.
                    if (fresh) triggerRecalc(ctx, personId)
                    startRealtime(ctx, personId)
                    onDone(true, null)
                }
                .addOnFailureListener { e ->
                    onDone(false, e.localizedMessage)
                }
        } catch (e: Exception) {
            onDone(false, e.localizedMessage)
        }
    }

    fun disableSync(ctx: Context, personId: String) {
        stopRealtime(personId)
        setSynced(ctx, personId, false)
        clearTxDots(ctx, personId)
    }

    // Owner-only full disable: removes the person's transactions, sharing
    // codes, member grants and the person doc from the cloud, then converts
    // the local copy to a plain local person (all data kept, locally only).
    // Blocking: call off the main thread. Callbacks post back to the caller.
    fun disableSyncFull(ctx: Context, personId: String, onDone: (Boolean, String?) -> Unit) {
        // Detach listeners and mark unsynced FIRST so our own cloud purge
        // can't echo back and wipe the local transactions. (Safe on failure
        // too: local-only is always the safe direction, and both sync and
        // disable remain retryable.)
        stopRealtime(personId)
        setSynced(ctx, personId, false)
        // The purge itself runs in the worker (/syncStop): as admin it is
        // immune to lockdown-denied reads that would strand the client-side
        // purge (tx list, person get). Local ownership pre-check only.
        try {
            val me = uid()
            val localOwner = LocalStore.people(ctx).optJSONObject(personId)
                ?.optString("dbOwner", "")
            if (!localOwner.isNullOrBlank() && localOwner != me) {
                postDone(onDone, false, "Only the owner can disable sync")
                return
            }
            workerPost("/syncStop", JSONObject().put("personId", personId), 30000) { ok, json ->
                if (ok) {
                    finishDisableLocal(ctx, personId)
                    postDone(onDone, true, null)
                } else {
                    // Idempotent miss (already gone): still convert locally.
                    val err = json?.optString("error").orEmpty()
                    if (err.contains("not found", true) || err.contains("no longer exists", true)) {
                        finishDisableLocal(ctx, personId)
                        postDone(onDone, true, null)
                    } else {
                        postDone(onDone, false, err.ifBlank { null })
                    }
                }
            }
        } catch (e: Exception) {
            postDone(onDone, false, e.localizedMessage)
        }
    }

    private fun postDone(onDone: (Boolean, String?) -> Unit, ok: Boolean, err: String?) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(ok, err) }
        } catch (_: Exception) {
            try {
                onDone(ok, err)
            } catch (_: Exception) {}
        }
    }

    private fun finishDisableLocal(ctx: Context, personId: String) {
        try {
            stopRealtime(personId)
            setSynced(ctx, personId, false)
            clearAccessRole(ctx, personId)
            clearTxDots(ctx, personId)
            // Freed slot returns to the pool (best-effort, never blocks).
            // No count bump: /syncStop decremented server-side (double -1
            // would undercount and over-admit; drift reconciles anyway).
            releaseSyncSlot(ctx, personId)
            val p = LocalStore.people(ctx).optJSONObject(personId)
            if (p != null) {
                p.remove("dbOwner")
                p.put("shareAsMe", false)
                setLocalShareCode(ctx, personId, "")
                LocalStore.persist(ctx)
            }
        } catch (_: Exception) {}
    }

    // Dots belong to synced persons only: wipe them when leaving sync
    fun clearTxDots(ctx: Context, personId: String) {
        try {
            val txs = LocalStore.transactions(ctx)
            var changed = false
            val keys = txs.keys()
            while (keys.hasNext()) {
                val t = txs.optJSONObject(keys.next()) ?: continue
                if (t.optString("personId", "") == personId && t.optBoolean("unseen", false)) {
                    t.put("unseen", false)
                    changed = true
                }
            }
            if (changed) LocalStore.persist(ctx)
        } catch (_: Exception) {}
    }

    fun deleteSharedPerson(personId: String) {
        if (!isCloudEnabled()) return
        try {
            db().collection("people-shared").document(personId).delete()
        } catch (_: Exception) {}
    }

    // ---------- Sharing codes ----------
    // One single code per person. Re-sharing shows the SAME code.
    // If a new code is generated, the previous code doc is deleted
    // (old code no longer works - it's replaced).
    fun generateCode(ctx: Context, personId: String, onDone: (String?) -> Unit) {
        if (!isCloudEnabled()) { onDone(null); return }
        try {
            db().collection("people-shared").document(personId).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    val existing = snap.getString("code").orEmpty()
                    val ownerInfo = snap.get("ownerInfo") as? Map<*, *>
                    val personName = snap.getString("name").orEmpty()
                    val netAmount = snap.getLong("netAmount") ?: 0L
                    if (existing.isNotBlank()) {
                        // reuse + clean up any stale duplicate code docs
                        deleteOtherCodes(personId, existing)
                        refreshCodePreview(
                            existing, personName, netAmount, ownerInfo,
                            snap.getString("currency").orEmpty()
                        )
                        setLocalShareCode(ctx, personId, existing)
                        onDone(existing)
                    } else {
                        val code = (0 until 6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
                        db().collection("sharing-codes").document(code)
                            .set(
                                mapOf("personId" to personId, "owner" to uid()) +
                                    previewFields(
                                        personName, netAmount, ownerInfo,
                                        snap.getString("currency").orEmpty()
                                    )
                            )
                            .timeout(T_QUICK)
                            .addOnSuccessListener {
                                deleteOtherCodes(personId, code)
                                runCatching {
                                    db().collection("people-shared").document(personId)
                                        .update("code", code)
                                }
                                setLocalShareCode(ctx, personId, code)
                                onDone(code)
                            }
                            .addOnFailureListener { onDone(null) }
                    }
                }
                .addOnFailureListener { onDone(null) }
        } catch (e: Exception) { onDone(null) }
    }

    // Disable the current sharing code (sync itself stays on).
    // Deletes the sharing-codes doc and clears the code field.
    fun disableCode(ctx: Context, personId: String, onDone: (Boolean) -> Unit) {
        if (!isCloudEnabled()) { onDone(false); return }
        try {
            db().collection("people-shared").document(personId).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    val code = snap.getString("code").orEmpty()
                    val clearField = {
                        setLocalShareCode(ctx, personId, "")
                        runCatching {
                            db().collection("people-shared").document(personId).update("code", "")
                        }
                    }
                    if (code.isBlank()) { clearField(); onDone(true); return@addOnSuccessListener }
                    db().collection("sharing-codes").document(code).delete().timeout(T_QUICK)
                        .addOnCompleteListener { clearField(); onDone(true) }
                }
                .addOnFailureListener { onDone(false) }
        } catch (e: Exception) { onDone(false) }
    }

    // Always mint a FRESH code (replaces any existing one).
    fun regenerateCode(ctx: Context, personId: String, onDone: (String?) -> Unit) {
        if (!isCloudEnabled()) { onDone(null); return }
        try {
            val code = (0 until 6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
            val p = LocalStore.people(ctx).optJSONObject(personId)
            val fu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val ownerInfo = mapOf(
                "name" to (fu?.displayName ?: ""),
                "email" to (fu?.email ?: ""),
                "photoUrl" to (fu?.photoUrl?.toString() ?: "")
            )
            db().collection("sharing-codes").document(code)
                .set(
                    mapOf("personId" to personId, "owner" to uid()) +
                        previewFields(
                            p?.optString("name", "").orEmpty(),
                            p?.optLong("netAmount", 0L) ?: 0L,
                            ownerInfo,
                            p?.optString("currency", "").orEmpty()
                        )
                )
                .timeout(T_QUICK)
                .addOnSuccessListener {
                    deleteOtherCodes(personId, code)
                    runCatching {
                        db().collection("people-shared").document(personId).update("code", code)
                    }
                    setLocalShareCode(ctx, personId, code)
                    onDone(code)
                }
                .addOnFailureListener { onDone(null) }
        } catch (e: Exception) { onDone(null) }
    }

    // Preview shown before joining (read from the code doc alone, so no
    // person-doc read permission is needed for non-members).
    data class InvitePreview(
        val personId: String,
        val personName: String,
        val netAmount: Long,
        val ownerName: String,
        val ownerEmail: String,
        val ownerPhoto: String,
        val currency: String = "",
        val ownerUid: String = ""
    )

    fun previewInvite(ctx: Context, code: String, onDone: (InvitePreview?) -> Unit) {
        if (!isCloudEnabled()) { onDone(null); return }
        // Primary: Cloudflare Worker (rate-limited, works after rules lock down sharing-codes).
        // Fallback: direct Firestore read (keeps old builds working until rules publish).
        lastWorkerError = null
        workerCall("/preview", code) { ok, json ->
            if (json?.has("error") == true) lastWorkerError = json.optString("error")
            else if (!ok) lastWorkerError = "Worker unreachable"
            if (ok && json != null && json.has("personId")) {
                onDone(
                    InvitePreview(
                        json.optString("personId"),
                        json.optString("personName"),
                        json.optLong("netAmount"),
                        json.optString("ownerName"),
                        json.optString("ownerEmail"),
                        json.optString("ownerPhotoUrl"),
                        json.optString("currency"),
                        json.optString("owner")
                    )
                )
                return@workerCall
            }
            // Fallback: direct read (will fail once strict rules are published)
            try {
                val codeUp = code.trim().uppercase()
                db().collection("sharing-codes").document(codeUp).get().timeout(T_QUICK)
                    .addOnSuccessListener { snap ->
                        if (!snap.exists()) { onDone(null); return@addOnSuccessListener }
                        if (snap.getBoolean("qr") == true &&
                            System.currentTimeMillis() - (snap.getLong("createdAt") ?: 0L) > QR_TTL_MS
                        ) {
                            runCatching { snap.reference.delete() }
                            onDone(null); return@addOnSuccessListener
                        }
                        onDone(
                            InvitePreview(
                                snap.getString("personId").orEmpty(),
                                snap.getString("personName").orEmpty(),
                                snap.getLong("netAmount") ?: 0L,
                                snap.getString("ownerName").orEmpty(),
                                snap.getString("ownerEmail").orEmpty(),
                                snap.getString("ownerPhotoUrl").orEmpty(),
                                snap.getString("currency").orEmpty(),
                                snap.getString("owner").orEmpty()
                            )
                        )
                    }
                    .addOnFailureListener { e ->
                        val workerErr = json?.optString("error")?.takeIf { it.isNotBlank() } ?: e.localizedMessage
                        try { android.util.Log.w("JoinWorker", "preview fallback failed: $workerErr") } catch (_: Exception) {}
                        // If worker gave a real reason (e.g. rate-limit, invalid code), keep it as null-preview
                        // but log it; UI will show the generic string, logcat has the detail.
                        onDone(null)
                    }
            } catch (_: Exception) { onDone(null) }
            // If worker already told us why, log it even on fallback success path
            if (json?.has("error") == true) {
                try { android.util.Log.w("JoinWorker", "preview worker error: ${json.optString("error")} code=$code ok=$ok") } catch (_: Exception) {}
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun previewFields(
        personName: String,
        netAmount: Long,
        ownerInfo: Map<*, *>?,
        currency: String = ""
    ): Map<String, Any?> =
        mapOf(
            "personName" to personName,
            "netAmount" to netAmount,
            "ownerName" to (ownerInfo?.get("name") as? String ?: ""),
            "ownerEmail" to (ownerInfo?.get("email") as? String ?: ""),
            "ownerPhotoUrl" to (ownerInfo?.get("photoUrl") as? String ?: ""),
            "currency" to currency
        )

    // Refresh the preview fields on an existing code doc (self-heals codes
    // created before previews existed + keeps netAmount fresh).
    private fun refreshCodePreview(
        code: String,
        personName: String,
        netAmount: Long,
        ownerInfo: Map<*, *>?,
        currency: String = ""
    ) {
        try {
            db().collection("sharing-codes").document(code)
                .update(previewFields(personName, netAmount, ownerInfo, currency))
        } catch (_: Exception) {}
    }

    // One-time QR join code: separate from the main share code - never
    // touches the person's `code` field and never deletes other codes.
    // The caller deletes it when the QR is closed.
    fun generateQrCode(ctx: Context, personId: String, onDone: (String?) -> Unit) {
        try {
            val code = (0 until 6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
            val p = LocalStore.people(ctx).optJSONObject(personId)
            val fu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val ownerInfo = mapOf(
                "name" to (fu?.displayName ?: ""),
                "email" to (fu?.email ?: ""),
                "photoUrl" to (fu?.photoUrl?.toString() ?: "")
            )
            db().collection("sharing-codes").document(code)
                .set(
                    mapOf(
                        "personId" to personId,
                        "owner" to uid(),
                        "qr" to true,
                        "createdAt" to System.currentTimeMillis()
                    ) + previewFields(
                        p?.optString("name", "").orEmpty(),
                        p?.optLong("netAmount", 0L) ?: 0L,
                        ownerInfo,
                        p?.optString("currency", "").orEmpty()
                    )
                )
                .timeout(T_QUICK)
                .addOnSuccessListener { onDone(code) }
                .addOnFailureListener { onDone(null) }
        } catch (e: Exception) { onDone(null) }
    }

    fun deleteQrCode(code: String) {
        if (code.isBlank()) return
        try {
            db().collection("sharing-codes").document(code).delete()
        } catch (_: Exception) {}
    }

    // Remove every sharing-codes doc for this person except `keep`.
    // Owner-scoped query (rules forbid listing other people's codes).
    private fun deleteOtherCodes(personId: String, keep: String) {
        try {
            db().collection("sharing-codes")
                .whereEqualTo("owner", uid())
                .get()
                .addOnSuccessListener { snap ->
                    snap.documents.forEach { d ->
                        if (d.id != keep && d.getString("personId") == personId) d.reference.delete()
                    }
                }
        } catch (_: Exception) {}
    }

    // QR codes live 2 minutes max (also enforced by Firestore rules).
    const val QR_TTL_MS = 2 * 60 * 1000L

    // Delete my own expired QR codes (covers app kill/force-stop where
    // QrSheet.onDismiss never ran). Main share codes are never touched.
    fun cleanupStaleQrCodes(ctx: Context) {
        if (!isCloudEnabled()) return
        try {
            val me = uid()
            db().collection("sharing-codes").whereEqualTo("owner", me).get()
                .addOnSuccessListener { snap ->
                    val now = System.currentTimeMillis()
                    snap.documents.forEach { d ->
                        if (d.getBoolean("qr") == true &&
                            now - (d.getLong("createdAt") ?: 0L) > QR_TTL_MS
                        ) {
                            d.reference.delete()
                        }
                    }
                }
        } catch (_: Exception) {}
    }

    fun redeemCode(ctx: Context, code: String, onDone: (Boolean, String?) -> Unit) {
        if (!isCloudEnabled()) { onDone(false, null); return }
        // Primary: Cloudflare Worker (rate-limited, holds the join payload lock).
        val codeUp = code.trim().uppercase()
        if (!isSynced(ctx, codeUp) && false) { /* keep linter quiet */ }
        // Local limit gate before spending a worker call
        // (rejoining an already-synced person is free)
        // We need the personId to check synced, so we let the worker tell us
        // on the first try; for a fast local reject we peek at nothing here.
        val fu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val profile = mapOf(
            "name" to (fu?.displayName ?: ""),
            "email" to (fu?.email ?: ""),
            "photoUrl" to (fu?.photoUrl?.toString() ?: "")
        )
        lastWorkerError = null
        workerCall("/join", codeUp, profile) { ok, json ->
            if (json?.has("error") == true) lastWorkerError = json.optString("error")
            else if (!ok) lastWorkerError = "Worker unreachable"
            if (ok && json != null && json.has("personId")) {
                val pid = json.optString("personId")
                if (pid.isBlank()) { onDone(false, "Invalid sharing code"); return@workerCall }
                // Mirror the slot locally (idempotent) so owner merge-updates keep syncSlot
                claimSyncSlot(ctx, pid) { }
                // Worker already wrote the grant; pull the person locally
                pullPerson(ctx, pid) { pullOk, _, err ->
                    // Surface already-member as success (idempotent join)
                    if (json.optBoolean("already", false)) { onDone(true, pid); return@pullPerson }
                    onDone(pullOk, if (pullOk) pid else err ?: json.optString("error").ifBlank { null })
                }
                return@workerCall
            }
            val workerErr = json?.optString("error")
            // Definitive worker rejections: don't fall back (would just re-hit rate-limit)
            val definitive = workerErr != null && (
                workerErr.contains("limit reached", true) ||
                workerErr.contains("own person", true) ||
                workerErr.contains("Too many", true) ||
                workerErr.contains("expired", true)
            )
            if (ok == false && definitive) { onDone(false, workerErr); return@workerCall }
            // Fallback: direct Firestore join (old builds / worker down / not yet deployed)
            try {
                db().collection("sharing-codes").document(codeUp)
                    .get().timeout(T_ACTION)
                    .addOnSuccessListener { snap ->
                        val personId = snap.getString("personId")
                        val ownerOf = snap.getString("owner") ?: ""
                        if (personId == null) { onDone(false, workerErr ?: "Invalid sharing code"); return@addOnSuccessListener }
                        if (snap.getString("owner").orEmpty() == uid()) {
                            onDone(false, ctx.getString(R.string.already_in_group)); return@addOnSuccessListener
                        }
                        if (snap.getBoolean("qr") == true &&
                            System.currentTimeMillis() - (snap.getLong("createdAt") ?: 0L) > QR_TTL_MS
                        ) {
                            runCatching { snap.reference.delete() }
                            onDone(false, "Invalid sharing code"); return@addOnSuccessListener
                        }
                        if (!isSynced(ctx, personId) && syncedCount(ctx) >= MAX_SYNCED_PERSONS) {
                            onDone(false, limitMessage(ctx)); return@addOnSuccessListener
                        }
                        val me = uid()
                        val joinMap = mapOf<String, Any>(
                            fp("members", me) to "read",
                            "grantCode" to codeUp,
                            fp("memberInfo", me) to mapOf(
                                "name" to (fu?.displayName ?: ""),
                                "email" to (fu?.email ?: ""),
                                "photoUrl" to (fu?.photoUrl?.toString() ?: "")
                            )
                        )
                        fun join(attempt: Int) {
                            claimSyncSlot(ctx, personId) { slot ->
                                if (slot == -1) { onDone(false, limitMessage(ctx)); return@claimSyncSlot }
                                if (slot < 0) { onDone(false, null); return@claimSyncSlot }
                                db().collection("people-shared").document(personId)
                                    .update(joinMap).timeout(T_ACTION)
                                    .addOnSuccessListener {
                                        writeMyGrant(ctx, personId, ownerOf, "read")
                                        pullPerson(ctx, personId) { ok2, _, err2 -> onDone(ok2, err2 ?: personId) }
                                    }
                                    .addOnFailureListener { e ->
                                        if (attempt < 2) {
                                            android.os.Handler(Looper.getMainLooper()).postDelayed({ join(attempt + 1) }, 800)
                                        } else {
                                            onDone(false, "Could not accept this code (${e.localizedMessage})")
                                        }
                                    }
                            }
                        }
                        join(0)
                    }
                    .addOnFailureListener { onDone(false, workerErr ?: "Invalid sharing code") }
            } catch (e: Exception) { onDone(false, workerErr ?: "Invalid sharing code") }
        }
    }

    // ---------- Access management (owner only) ----------
    data class SharedMember(
        val uid: String,
        val role: String,
        val name: String,
        val email: String,
        val photo: String
    )

    // Read-only fetch of the current sharing code (never creates one)
    fun getCode(ctx: Context, personId: String, onDone: (String?) -> Unit) {
        if (!isCloudEnabled()) { onDone(null); return }
        try {
            db().collection("people-shared").document(personId).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    val code = snap.getString("code").orEmpty()
                    setLocalShareCode(ctx, personId, code)
                    onDone(code)
                }
                .addOnFailureListener { onDone(null) }
        } catch (e: Exception) { onDone(null) }
    }

    // "txUpdatedAt last loaded" marker (local only, never mirrored).
    // The home red dot compares it against the latest mirrored txUpdatedAt.
    fun setTxLoaded(ctx: Context, personId: String, value: Long) {
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return
            p.put("txUpdatedAtLoaded", value)
            LocalStore.persist(ctx)
        } catch (_: Exception) {}
    }

    // Mark currently-loaded = latest (opening / live-viewing the page clears the dot)
    fun markTxLoadedCurrent(ctx: Context, personId: String) {
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return
            setTxLoaded(ctx, personId, p.optLong("txUpdatedAt", 0L))
        } catch (_: Exception) {}
    }

    // Local mirror of the sharing code (instant UI, no network wait).
    // Written on every code op + realtime update; never mirrored back.
    private fun setLocalShareCode(ctx: Context, personId: String, code: String) {
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return
            p.put("shareCode", code)
            LocalStore.persist(ctx)
        } catch (_: Exception) {}
    }

    fun localShareCode(ctx: Context, personId: String): String {
        return try {
            LocalStore.people(ctx).optJSONObject(personId)?.optString("shareCode", "").orEmpty()
        } catch (_: Exception) { "" }
    }

    // Instant owner row from local data (ownerInfo mirror + self fallback).
    // Used to render members immediately while listAccess refreshes.
    fun localOwnerMember(ctx: Context, personId: String): SharedMember? {
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return null
            var ownerUid = p.optString("dbOwner", "")
            if (ownerUid.isBlank()) {
                val info = LocalStore.getSetting(ctx, "shared_info") as? String
                ownerUid = info?.let {
                    runCatching { JSONObject(it).optJSONObject(personId)?.optString("owner", "") }.getOrNull()
                }.orEmpty()
            }
            val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            if (ownerUid.isBlank()) ownerUid = selfUid
            if (ownerUid.isBlank()) return null
            var name = p.optString("ownerName", "")
            var email = p.optString("ownerEmail", "")
            var photo = p.optString("ownerPhotoUrl", "")
            if (ownerUid == selfUid) {
                val fu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (name.isBlank()) name = fu?.displayName ?: ""
                if (email.isBlank()) email = fu?.email ?: ""
                if (photo.isBlank()) photo = fu?.photoUrl?.toString() ?: ""
            }
            return SharedMember(ownerUid, "owner", name, email, photo)
        } catch (_: Exception) { return null }
    }

    // Last full member list (with roles) - instant render, refreshed by listAccess.
    fun cachedMembers(ctx: Context, personId: String): List<SharedMember>? {
        try {
            val raw = LocalStore.getSetting(ctx, "members_cache") as? String ?: return null
            val arr = JSONObject(raw).optJSONArray(personId) ?: return null
            if (arr.length() == 0) return null
            val out = mutableListOf<SharedMember>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SharedMember(
                        o.optString("uid", ""),
                        o.optString("role", ""),
                        o.optString("name", ""),
                        o.optString("email", ""),
                        o.optString("photo", "")
                    )
                )
            }
            return out.ifEmpty { null }
        } catch (_: Exception) { return null }
    }

    private fun saveMembersCache(ctx: Context, personId: String, list: List<SharedMember>) {
        try {
            val raw = LocalStore.getSetting(ctx, "members_cache") as? String
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            val arr = org.json.JSONArray()
            list.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("uid", m.uid)
                        .put("role", m.role)
                        .put("name", m.name)
                        .put("email", m.email)
                        .put("photo", m.photo)
                )
            }
            obj.put(personId, arr)
            LocalStore.setSetting(ctx, "members_cache", obj.toString())
        } catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    fun listAccess(ctx: Context, personId: String, onDone: (List<SharedMember>) -> Unit) {
        try {
            db().collection("people-shared").document(personId).get().timeout(T_QUICK)
                .addOnSuccessListener { snap ->
                    val members = snap.get("members") as? Map<*, *> ?: emptyMap<String, Any>()
                    val info = snap.get("memberInfo") as? Map<*, *> ?: emptyMap<String, Any>()
                    // Owner never writes a memberInfo entry (only joiners do),
                    // so fall back to ownerInfo for the owner uid.
                    val ownerUid = snap.getString("owner").orEmpty()
                    val ownerInfo = snap.get("ownerInfo") as? Map<*, *>
                    val list = mutableListOf<SharedMember>()
                    members.forEach { (k, v) ->
                        val uidStr = k.toString()
                        val infoEntry = info[uidStr] as? Map<*, *>
                        var name = infoEntry?.get("name") as? String ?: ""
                        var email = infoEntry?.get("email") as? String ?: ""
                        var photo = infoEntry?.get("photoUrl") as? String ?: ""
                        if (uidStr == ownerUid && ownerInfo != null) {
                            if (name.isBlank()) name = ownerInfo["name"] as? String ?: ""
                            if (email.isBlank()) email = ownerInfo["email"] as? String ?: ""
                            if (photo.isBlank()) photo = ownerInfo["photoUrl"] as? String ?: ""
                        }
                        list.add(SharedMember(uidStr, v.toString(), name, email, photo))
                    }
                    saveMembersCache(ctx, personId, list)
                    onDone(list)
                }
                .addOnFailureListener { onDone(emptyList()) }
        } catch (e: Exception) { onDone(emptyList()) }
    }

    // A member leaves a shared person: removes only themselves from
    // members (+ their own memberInfo), then wipes all local traces.
    // Allowed by the self-leave rules branch (owner cannot use this).
    fun leavePerson(ctx: Context, personId: String, onDone: (Boolean, String?) -> Unit) {
        if (!isCloudEnabled()) { onDone(false, null); return }
        try {
            val me = uid()
            db().collection("people-shared").document(personId).get().timeout(T_ACTION)
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) { onDone(false, "Person not found"); return@addOnSuccessListener }
                    val members = (snap.get("members") as? Map<*, *>)
                        ?.mapValues { it.value.toString() }?.toMutableMap() ?: mutableMapOf()
                    if (!members.containsKey(me)) { onDone(false, "Not a member"); return@addOnSuccessListener }
                    members.remove(me)
                    val updates = hashMapOf<String, Any>("members" to members)
                    val info = snap.get("memberInfo") as? Map<*, *>
                    if (info != null && info.containsKey(me)) {
                        val newInfo = HashMap<String, Any?>()
                        info.forEach { (k, v) ->
                            if (k.toString() != me && v != null) newInfo[k.toString()] = v
                        }
                        updates["memberInfo"] = newInfo
                    }
                    db().collection("people-shared").document(personId).update(updates).timeout(T_ACTION)
                        .addOnSuccessListener {
                            removeGrant(ctx, personId, me)
                            clearAccessRole(ctx, personId)
                            setSynced(ctx, personId, false)
                            clearTxDots(ctx, personId)
                            stopRealtime(personId)
                            // Freed slot returns to the pool (best-effort)
                            releaseSyncSlot(ctx, personId)
                            // Server-owned count follows (worker-written)
                            bumpServerCount(ctx, personId, -1)
                            // Keep everything locally: the person becomes a plain
                            // local person (no cloud involvement at all).
                            runCatching {
                                val p = LocalStore.people(ctx).optJSONObject(personId)
                                if (p != null) {
                                    // Remember the owner-saved original name, then keep
                                    // showing the name the user knew (share-as-me display).
                                    p.put("originalName", p.optString("name", ""))
                                    if (p.optBoolean("shareAsMe", false)) {
                                        val ownerName = p.optString("ownerName", "")
                                        if (ownerName.isNotBlank()) p.put("name", ownerName)
                                    }
                                    p.put("leftGroup", true)
                                    p.put("leftAt", System.currentTimeMillis())
                                    p.remove("dbOwner")
                                    LocalStore.persist(ctx)
                                }
                            }
                            onDone(true, null)
                        }
                        .addOnFailureListener { e -> onDone(false, e.localizedMessage) }
                }
                .addOnFailureListener { e -> onDone(false, e.localizedMessage) }
        } catch (e: Exception) { onDone(false, e.localizedMessage) }
    }

    private fun clearAccessRole(ctx: Context, personId: String) {
        try {
            val info = LocalStore.getSetting(ctx, "shared_info") as? String ?: return
            val obj = runCatching { JSONObject(info) }.getOrNull() ?: return
            obj.remove(personId)
            LocalStore.setSetting(ctx, "shared_info", obj.toString())
            val cache = LocalStore.getSetting(ctx, "members_cache") as? String
            cache?.let {
                runCatching {
                    val cObj = JSONObject(it)
                    cObj.remove(personId)
                    LocalStore.setSetting(ctx, "members_cache", cObj.toString())
                }
            }
        } catch (_: Exception) {}
    }

    fun setPermission(ctx: Context, personId: String, targetUid: String, role: String, onDone: (Boolean) -> Unit) {
        if (!isCloudEnabled()) { onDone(false); return }
        try {
            db().collection("people-shared").document(personId)
                .update(fp("members", targetUid), role).timeout(T_QUICK)
                .addOnSuccessListener {
                    // Grant space is self-only: the receiver's own pull
                    // reconciles their role from the person doc.
                    onDone(true)
                }
                .addOnFailureListener { onDone(false) }
        } catch (e: Exception) { onDone(false) }
    }

    fun revokeAccess(ctx: Context, personId: String, targetUid: String, onDone: (Boolean) -> Unit) {
        if (!isCloudEnabled()) { onDone(false); return }
        try {
            val updates = mutableMapOf<String, Any>(fp("members", targetUid) to com.google.firebase.firestore.FieldValue.delete())
            db().collection("people-shared").document(personId)
                .update(updates).timeout(T_QUICK)
                // Grant space is self-only: the revoked receiver drops
                // their own stale grant on the next pull.
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { onDone(false) }
        } catch (e: Exception) { onDone(false) }
    }

    private fun grantRef(uidStr: String, personId: String) =
        db().collection("user-grants").document(uidStr).collection("persons").document(personId)

    private fun removeGrant(ctx: Context, personId: String, targetUid: String) {
        try {
            grantRef(targetUid, personId).delete()
        } catch (_: Exception) {}
    }

    // self-side: write own grant after joining
    private fun writeMyGrant(ctx: Context, personId: String, owner: String, role: String) {
        try {
            grantRef(uid(), personId)
                .set(mapOf("personId" to personId, "owner" to owner, "role" to role))
        } catch (_: Exception) {}
    }

    // restore: pull every person I have a grant for (read/write roles)
    fun pullMyGrants(ctx: Context, onDone: (() -> Unit)? = null) {
        if (!isCloudEnabled()) { onDone?.invoke(); return }
        try {
            val me = uid()
            grantRef(me, "x").parent // parent = collection ref of user-grants/me/persons
            db().collection("user-grants").document(me).collection("persons").get().timeout(T_BULK)
                .addOnSuccessListener { snaps ->
                    if (snaps.documents.isEmpty()) {
                        onDone?.invoke()
                        return@addOnSuccessListener
                    }
                    var remaining = snaps.documents.size
                    snaps.documents.forEach { d ->
                        val pid = d.getString("personId") ?: run {
                            remaining--
                            if (remaining <= 0) onDone?.invoke()
                            return@forEach
                        }
                        pullPerson(ctx, pid) { ok, _, _ ->
                            // pullPerson applies remote person + tx and stores role.
                            // Self-reconciliation: an online pull that can no
                            // longer read the person (revoked / deleted) drops
                            // our own stale grant. Offline failures keep it.
                            if (!ok && NetworkUtils.isOnline(ctx)) {
                                removeGrant(ctx, pid, me)
                            }
                            remaining--
                            if (remaining <= 0) onDone?.invoke()
                        }
                    }
                }
                .addOnFailureListener { onDone?.invoke() }
        } catch (e: Exception) {
            onDone?.invoke()
        }
    }

    // ---------- Write-through (only when synced) ----------
    fun writePerson(ctx: Context, personId: String) {
        if (!isCloudEnabled()) return
        if (!isSynced(ctx, personId)) return
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return
            // merge a.k.a. update-only: members/code stay untouched
            db().collection("people-shared").document(personId).set(
                mapOf(
                    "owner" to (p.optString("dbOwner", uid())),
                    "name" to p.optString("name", ""),
                    "mobile" to p.optString("mobile", ""),
                    "netAmount" to p.optLong("netAmount", 0L),
                    "archived" to p.optBoolean("archived", false),
                    "createdAt" to p.optLong("createdAt", 0L),
                    "updatedAt" to p.optLong("updatedAt", 0L),
                    "currency" to p.optString("currency", ""),
                    "txUpdatedAt" to p.optLong("txUpdatedAt", 0L),
                    "units" to "minor"
                    // NOTE: the 5 money aggregates are NEVER sent here. Rules
                    // deny owner/writer updates that change them (only the
                    // batched tx legs with verified _tx math, or the worker
                    // /recalc, may move them) - so tampered clients fail shut.
                ) + (
                    // Re-send our bound slot: the owner quota pin denies
                    // owner updates that drop/change it once set. Legacy
                    // slot-less persons omit it (still allowed).
                    localSyncSlot(ctx, personId)?.let { mapOf("syncSlot" to it) }
                        ?: emptyMap()
                    ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            // Keep the join preview fresh while a share code is active.
            // Owner-only: rules forbid non-owners from touching code docs.
            val shareCode = p.optString("shareCode", "")
            if (shareCode.isNotBlank() && accessRole(ctx, personId) == "owner") {
                runCatching {
                    db().collection("sharing-codes").document(shareCode).update(
                        mapOf(
                            "personName" to p.optString("name", ""),
                            "netAmount" to p.optLong("netAmount", 0L),
                            "currency" to p.optString("currency", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    fun writeTx(ctx: Context, personId: String, txId: String) {
        if (!isCloudEnabled()) return
        if (!isSynced(ctx, personId)) return
        try {
            val t = LocalStore.transactions(ctx).optJSONObject(txId) ?: return
            // Stamp minor units on the cloud copy only (local model untouched).
            // The local-only unseen dot never travels to the cloud.
            val m = jsonToMap(t).toMutableMap()
            m["units"] = "minor"
            m.remove("unseen")
            db().collection("people-shared").document(personId)
                .collection("transactions").document(txId)
                .set(m)
        } catch (_: Exception) {}
    }

    // DB-first save for synced persons: one atomic batch (tx doc + person
    // net), local storage is written only after this succeeds. Off-thread
    // callbacks come back on the main thread (Firestore default).
    private fun logTxSave(msg: String) {
        try { android.util.Log.d("TxSave", msg) } catch (_: Exception) {}
    }

    // Synced entry save: the worker owns ALL money math. The app sends only
    // raw tx fields; the worker verifies write access, applies the delta to
    // the stored aggregates, and commits tx + person atomically. onDone
    // returns the worker-computed aggregates for local stamping.
    fun saveTxRemote(
        ctx: Context,
        personId: String,
        txId: String,
        txData: Map<String, Any?>,
        onDone: (Boolean, JSONObject?) -> Unit
    ) {
        try {
            if (!isCloudEnabled() || !isSynced(ctx, personId)) {
                onDone(false, null)
                return
            }
            val payload = JSONObject()
                .put("personId", personId)
                .put("txId", txId)
                .put(
                    "tx", JSONObject(
                        mapOf(
                            "category" to (txData["category"] as? String ?: "General"),
                            "amount" to ((txData["amount"] as? Number)?.toLong() ?: 0L),
                            "type" to (txData["type"] as? String ?: "gave"),
                            "note" to (txData["note"] as? String ?: ""),
                            "createdAt" to ((txData["createdAt"] as? Number)?.toLong()
                                ?: System.currentTimeMillis())
                        ) as Map<*, *>
                    )
                )
            logTxSave("save pid=$personId tx=$txId type=${txData["type"]} amount=${txData["amount"]}")
            // TEST PROOF: this log line is the ENTIRE outbound body. Verify it
            // carries only raw tx fields (no netAmount/received/gave/counts).
            logTxSave("outbound /txSave payload=$payload")
            workerPost("/txSave", payload, T_SAVE.toInt(), onDone)
        } catch (e: Exception) {
            onDone(false, null)
        }
    }

    fun deleteTxRemote(
        ctx: Context,
        personId: String,
        txId: String,
        onDone: (Boolean, JSONObject?) -> Unit
    ) {
        try {
            if (!isCloudEnabled() || !isSynced(ctx, personId)) {
                onDone(false, null)
                return
            }
            val payload = JSONObject().put("personId", personId).put("txId", txId)
            logTxSave("delete pid=$personId tx=$txId")
            workerPost("/txDelete", payload, T_SAVE.toInt(), onDone)
        } catch (e: Exception) {
            onDone(false, null)
        }
    }

    // Stamp worker-computed aggregates onto the local person record. This
    // REPLACES local recompute for synced persons: server truth is display
    // truth (own edits count as seen, so the loaded marker moves with it).
    fun applyServerAggregates(ctx: Context, personId: String, agg: JSONObject?) {
        if (agg == null) return
        try {
            val p = LocalStore.people(ctx).optJSONObject(personId) ?: return
            if (agg.has("netAmount")) p.put("netAmount", agg.optLong("netAmount"))
            if (agg.has("received")) p.put("received", agg.optLong("received"))
            if (agg.has("gave")) p.put("gave", agg.optLong("gave"))
            if (agg.has("receivedCount")) p.put("receivedCount", agg.optInt("receivedCount"))
            if (agg.has("gaveCount")) p.put("gaveCount", agg.optInt("gaveCount"))
            val txU = agg.optLong("txUpdatedAt", 0L)
            if (txU > 0) {
                p.put("txUpdatedAt", txU)
                p.put("updatedAt", txU)
                p.put("txUpdatedAtLoaded", txU)
            }
            LocalStore.persist(ctx)
        } catch (_: Exception) {}
    }

    fun deleteTx(ctx: Context, personId: String, txId: String) {
        if (!isCloudEnabled()) return
        if (!isSynced(ctx, personId)) return
        try {
            db().collection("people-shared").document(personId)
                .collection("transactions").document(txId)
                .delete()
        } catch (_: Exception) {}
    }

    private fun pushTransactions(ctx: Context, personId: String) {
        val txs = LocalStore.transactions(ctx)
        val keys = txs.keys()
        while (keys.hasNext()) {
            val txId = keys.next()
            val t = txs.optJSONObject(txId) ?: continue
            if (t.optString("personId", "") != personId) continue
            val m = jsonToMap(t).toMutableMap()
            m["units"] = "minor"
            m.remove("unseen")
            db().collection("people-shared").document(personId)
                .collection("transactions").document(txId)
                .set(m)
        }
    }

    // Restore ALL persons I own & synced (after fresh install / re-login).
    // Queries people-shared where owner == me, pulls person + transactions into local.
    // Persons-only pull (names, aggregates, roles, latestTx previews).
    // Entries are NEVER bulk-fetched: they arrive via background pushes,
    // per-person lazy 25-chunks on open, and latestTx previews for logs.
    fun pullAllMine(ctx: Context, onDone: ((Int, String?) -> Unit)? = null) {
        if (!isCloudEnabled()) { onDone?.invoke(0, null); return }
        try {
            val uidSelf = uid()
            db().collection("people-shared")
                .whereEqualTo("owner", uidSelf)
                .get().timeout(T_BULK)
                .addOnSuccessListener { snap ->
                    snap.documents.forEach { d ->
                        applyRemotePerson(ctx, d)
                        setSynced(ctx, d.id, true)
                        setAccessRole(ctx, d.id, d.getString("owner") ?: uidSelf, "owner")
                    }
                    onDone?.invoke(snap.documents.size, null)
                }
                .addOnFailureListener { e ->
                    onDone?.invoke(-1, e.localizedMessage)
                }
        } catch (e: Exception) {
            onDone?.invoke(-1, e.localizedMessage)
        }
    }

    // ---------- Pull remote person into local (for receivers & listener updates) ----------
    // Persons-only by default: entries arrive via background pushes, lazy
    // 25-chunks on open, and latestTx previews. withTxs=true keeps the old
    // full backfill for flows that explicitly need it (currently none -
    // retained for repair paths).
    fun pullPerson(
        ctx: Context, personId: String,
        withTxs: Boolean = false,
        onDone: (Boolean, String?, String?) -> Unit
    ) {
        if (!isCloudEnabled()) { onDone(false, null, null); return }
        try {
            db().collection("people-shared").document(personId).get().timeout(T_BULK)
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) { onDone(false, null, "Person not found"); return@addOnSuccessListener }
                    applyRemotePerson(ctx, snap)
                    // Anything pulled from people-shared is a cloud person:
                    // mark synced so local edits write through to the database.
                    setSynced(ctx, personId, true)
                    // Absent-window self-heal: cloud aggregates missing (fresh
                    // sync whose /recalc hasn't landed, or legacy tampering)
                    // -> ask the worker to recompute from real transactions.
                    // Fire-and-forget + server rate-limited; the next pull
                    // picks up the truth.
                    if (snap.get("netAmount") == null) triggerRecalc(ctx, personId)
                    if (!withTxs) { onDone(true, snap.id, null); return@addOnSuccessListener }
                    db().collection("people-shared").document(personId)
                        .collection("transactions").get().timeout(T_BULK)
                        .addOnSuccessListener { txSnap ->
                            // Newcomers since the last load mark dots. When
                            // nothing was marked, stamp loaded so the marker
                            // self-establishes (legacy/restored data heals).
                            var marked = false
                            txSnap.documents.forEach {
                                applyRemoteTx(ctx, it, markNewcomers = true) {
                                    marked = true
                                }
                            }
                            if (!marked) {
                                setTxLoaded(
                                    ctx, personId,
                                    (snap.get("txUpdatedAt") as? Number)?.toLong() ?: 0L
                                )
                            }
                            onDone(true, snap.id, null)
                        }
                        .addOnFailureListener { onDone(false, null, "Could not load transactions") }
                }
                .addOnFailureListener { onDone(false, null, "Could not load person") }
        } catch (e: Exception) { onDone(false, null, e.message) }
    }

    // Lazy 25-chunk entry feed for one person (newest first). Backfill
    // chunks land seen and dot-free (pushes own the dots); the caller
    // repaints. Cursors live here per person; reset=true restarts the feed.
    private val feedCursors = mutableMapOf<String, com.google.firebase.firestore.DocumentSnapshot?>()
    private val feedExhausted = mutableSetOf<String>()
    private val feedLoading = mutableSetOf<String>()

    fun feedHasMore(personId: String): Boolean = !feedExhausted.contains(personId)

    fun resetFeed(personId: String) {
        feedCursors.remove(personId)
        feedExhausted.remove(personId)
    }

    fun fetchTxChunk(ctx: Context, personId: String, reset: Boolean, onDone: (Int) -> Unit) {
        if (!isCloudEnabled()) { onDone(0); return }
        try {
            if (reset) resetFeed(personId)
            if (feedExhausted.contains(personId)) { onDone(0); return }
            if (!feedLoading.add(personId)) return // one flight per person
            var q = db().collection("people-shared").document(personId)
                .collection("transactions")
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(25)
            feedCursors[personId]?.let { q = q.startAfter(it) }
            q.get().timeout(T_BULK)
                .addOnSuccessListener { snap ->
                    try {
                        var added = 0
                        snap.documents.forEach {
                            if (applyRemoteTx(ctx, it)) added++
                        }
                        if (snap.documents.isNotEmpty()) {
                            feedCursors[personId] = snap.documents.last()
                        }
                        if (snap.documents.size < 25) feedExhausted.add(personId)
                        onDone(added)
                    } catch (_: Exception) {
                        onDone(0)
                    } finally {
                        feedLoading.remove(personId)
                    }
                }
                .addOnFailureListener {
                    feedLoading.remove(personId)
                    onDone(0)
                }
        } catch (_: Exception) { onDone(0) }
    }

    // Real-time listeners: person docs ONLY. Entry flow moved to background
    // pushes (full tx + aggregates in the payload, zero reads) + lazy
    // 25-chunks on open, so the chatty per-tx snapshots are gone. Person
    // docs still stream (names, roles, totals, latestTx previews are tiny
    // and change rarely).
    private val personListeners = mutableMapOf<String, com.google.firebase.firestore.ListenerRegistration>()

    fun startRealtime(ctx: Context, personId: String) {
        if (!isCloudEnabled()) return
        if (personListeners.containsKey(personId)) return
        try {
            personListeners[personId] = db().collection("people-shared").document(personId)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        // Lockdown signal: reads start denying the moment a
                        // lowered cap bites (recovery popup, once per session)
                        try {
                            val code = (err as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                            if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                (ctx as? MainActivity)?.maybeShowSyncLock()
                            }
                        } catch (_: Exception) {}
                        return@addSnapshotListener
                    }
                    if (snap == null || !snap.exists()) return@addSnapshotListener
                    // Stale events after an unsync (leave/disable/delete) must
                    // never touch local data (e.g. our own cloud purge)
                    if (!isSynced(ctx, personId)) return@addSnapshotListener
                    applyRemotePerson(ctx, snap)
                    (ctx as? MainActivity)?.refreshLocalData()
                }
        } catch (_: Exception) {}
    }

    fun stopRealtime(personId: String) {
        personListeners.remove(personId)?.remove()
    }

    // ---------- local apply helpers (no mirror to avoid loops) ----------
    @Suppress("UNCHECKED_CAST")
    private fun applyRemotePerson(ctx: Context, snap: DocumentSnapshot) {
        try {
            val pid = snap.id
            val owner = snap.getString("owner") ?: uid()
            val existing = LocalStore.people(ctx).optJSONObject(pid)
            val ownerInfo = snap.get("ownerInfo") as? Map<*, *>
            // Decimal migration, convert-once: legacy whole-rupee cloud docs
            // are converted only when neither side is stamped minor yet.
            val cloudMinor = snap.getString("units") == "minor"
            val localMinor = existing?.optString("units", "") == "minor"
            var net = (snap.get("netAmount") as? Number)?.toLong()
                ?: existing?.optLong("netAmount") ?: 0L
            if (!cloudMinor && !localMinor) net *= 100L
            val data = mapOf<String, Any?>(
                "name" to (snap.getString("name") ?: existing?.optString("name") ?: ""),
                "mobile" to (snap.getString("mobile") ?: existing?.optString("mobile") ?: ""),
                "netAmount" to net,
                "units" to "minor",
                "archived" to (snap.getBoolean("archived") ?: existing?.optBoolean("archived") ?: false),
                "createdAt" to (snap.getLong("createdAt") ?: existing?.optLong("createdAt") ?: System.currentTimeMillis()),
                "updatedAt" to (snap.getLong("updatedAt") ?: existing?.optLong("updatedAt") ?: System.currentTimeMillis()),
                "dbOwner" to owner,
                "ownerName" to (ownerInfo?.get("name") as? String ?: existing?.optString("ownerName") ?: ""),
                "ownerEmail" to (ownerInfo?.get("email") as? String ?: existing?.optString("ownerEmail") ?: ""),
                "ownerPhotoUrl" to (ownerInfo?.get("photoUrl") as? String ?: existing?.optString("ownerPhotoUrl") ?: ""),
                "shareAsMe" to (snap.getBoolean("shareAsMe") ?: existing?.optBoolean("shareAsMe") ?: false),
                // Blank cloud currency counts as missing: a pinned local
                // explicit value must never be clobbered back to default
                "currency" to (snap.getString("currency")?.ifBlank { null }
                    ?: existing?.optString("currency")?.ifBlank { null }
                    ?: ""),
                "txUpdatedAt" to ((snap.get("txUpdatedAt") as? Number)?.toLong()
                    ?: existing?.optLong("txUpdatedAt") ?: 0L),
                "received" to ((snap.get("received") as? Number)?.toLong()
                    ?: existing?.optLong("received") ?: 0L),
                "gave" to ((snap.get("gave") as? Number)?.toLong()
                    ?: existing?.optLong("gave") ?: 0L),
                "receivedCount" to ((snap.get("receivedCount") as? Number)?.toInt()
                    ?: existing?.optInt("receivedCount") ?: 0),
                "gaveCount" to ((snap.get("gaveCount") as? Number)?.toInt()
                    ?: existing?.optInt("gaveCount") ?: 0),
                // Newest-entry preview for zero-read logs (worker-maintained).
                // Explicit cloud null clears (all entries deleted); absent
                // key keeps the local copy (legacy docs predate previews).
                "latestTx" to (if (snap.contains("latestTx")) (snap.get("latestTx") as? Map<*, *>)?.let { org.json.JSONObject(it) }
                    else existing?.optJSONObject("latestTx")),
                // Preserve the loaded marker across remote replaces (else the dot sticks)
                "txUpdatedAtLoaded" to (existing?.optLong("txUpdatedAtLoaded") ?: 0L),
                "shareCode" to (snap.getString("code") ?: existing?.optString("shareCode") ?: "")
            )
            LocalStore.applyRemotePerson(ctx, pid, data)
            // store members + member labels locally for role checks/UI
            val members = snap.get("members") as? Map<*, *>
            if (members != null) {
                val role = members[uid()] as? String
                setAccessRole(ctx, pid, owner, role ?: "owner")
                val info = snap.get("memberInfo") as? Map<*, *>
                if (info != null) {
                    val infoObj = JSONObject()
                    info.forEach { (k, v) ->
                        val vv = v as? Map<*, *>
                        if (vv != null) {
                            infoObj.put(
                                k.toString(),
                                JSONObject()
                                    .put("name", vv["name"] ?: "")
                                    .put("email", vv["email"] ?: "")
                                    .put("photoUrl", vv["photoUrl"] ?: "")
                            )
                        }
                    }
                    setMembersInfo(ctx, pid, infoObj)
                }
            }
        } catch (_: Exception) {}
    }

    // Person ids present locally before the first pull cycle of this process.
    // First-launch/join suppression keys off THIS (not marker existence),
    // so restored-but-unmarked persons still dot on genuine changes.
    @Volatile
    private var knownAtStart: Set<String>? = null

    fun noteKnownPersons(ids: Set<String>) {
        if (knownAtStart == null) knownAtStart = ids
    }

    fun wasKnownAtStart(personId: String): Boolean = knownAtStart?.contains(personId) == true

    private fun applyRemoteTx(
        ctx: Context,
        snap: DocumentSnapshot,
        markUnseen: Boolean = false,
        markNewcomers: Boolean = false,
        onMarked: (() -> Unit)? = null
    ): Boolean {
        try {
            val txId = snap.id
            newValueMapper(ctx, snap)?.let { data ->
                // NOTE: never touch the person's txUpdatedAt here - the person
                // snapshot is the single source of latest. Stamping now on
                // every re-apply (e.g. every-start pulls) fakes a mismatch.
                val before = LocalStore.transactions(ctx).optJSONObject(txId)
                val wasUnseen = before?.optBoolean("unseen", false) == true
                LocalStore.applyRemoteTx(ctx, txId, data)
                // Preserve an existing dot: mapper output carries no unseen
                // flag, so a replace would silently clear unviewed entries
                if (wasUnseen) {
                    LocalStore.transactions(ctx).optJSONObject(txId)?.put("unseen", true)
                }
                // Incremental remote adds/edits by someone else = unseen dot.
                // Own actor txs (incl. echoes) and bulk pulls stay seen.
                if (markUnseen) {
                    if (markUnseenIfForeign(ctx, txId, data)) onMarked?.invoke()
                } else if (markNewcomers) {
                    // Pulls: only txs that are new (or changed) since the last
                    // load. Suppressed only for persons unknown before the
                    // first pull (fresh installs/joins never dot).
                    try {
                        val pid = snap.getString("personId").orEmpty()
                        val known = wasKnownAtStart(pid) ||
                            (LocalStore.people(ctx).optJSONObject(pid)
                                ?.optLong("txUpdatedAtLoaded", 0L) ?: 0L) != 0L
                        if (known) {
                            val isNew = before == null
                            val changed = !isNew && txContentChanged(before, data)
                            if ((isNew || changed) &&
                                markUnseenIfForeign(ctx, txId, data)
                            ) {
                                onMarked?.invoke()
                            }
                        }
                    } catch (_: Exception) {}
                }
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun txContentChanged(before: JSONObject, data: Map<String, Any?>): Boolean {
        return try {
            before.optLong("amount", Long.MIN_VALUE) != (data["amount"] as? Number)?.toLong() ||
                before.optString("type", "") != (data["type"] as? String ?: "") ||
                before.optString("note", "") != (data["note"] as? String ?: "") ||
                before.optString("category", "") != (data["category"] as? String ?: "") ||
                before.optLong("createdAt", Long.MIN_VALUE) != (data["createdAt"] as? Number)?.toLong() ||
                before.optLong("savedAt", Long.MIN_VALUE) != (data["savedAt"] as? Number)?.toLong()
        } catch (_: Exception) {
            false
        }
    }

    private fun markUnseenIfForeign(ctx: Context, txId: String, data: Map<String, Any?>): Boolean {
        try {
            // Dots belong to synced persons only - never mark local ones
            val pid = (data["personId"] as? String).orEmpty()
            if (!isSynced(ctx, pid)) return false
            val me = UserDb.uid()
            // Last touch wins: an edit by someone else dots even my own txs,
            // while my own adds/edits (incl. echoes) stay seen
            val editedBy = data["editedByUid"] as? String
            val lastTouch = if (!editedBy.isNullOrBlank()) editedBy else data["createdByUid"] as? String
            if (lastTouch != me) {
                LocalStore.transactions(ctx).optJSONObject(txId)?.put("unseen", true)
                LocalStore.persist(ctx)
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun newValueMapper(ctx: Context, snap: DocumentSnapshot): Map<String, Any?>? {
        val personId = snap.getString("personId") ?: return null
        // Decimal migration, convert-once per tx doc (see applyRemotePerson).
        val cloudMinorTx = snap.getString("units") == "minor"
        val localTxUnits = try {
            LocalStore.transactions(ctx).optJSONObject(snap.id)?.optString("units", "")
        } catch (_: Exception) { "" }
        var amount = (snap.get("amount") as? Number)?.toLong() ?: 0L
        if (!cloudMinorTx && localTxUnits != "minor") amount *= 100L
        return mapOf(
            "personId" to personId,
            "category" to (snap.getString("category") ?: "General"),
            "amount" to amount,
            "units" to "minor",
            "type" to (snap.getString("type") ?: "gave"),
            "note" to (snap.getString("note") ?: ""),
            "createdAt" to (snap.getLong("createdAt") ?: 0L),
            "savedAt" to (snap.getLong("savedAt") ?: 0L),
            // actor info must travel with the entry (so everyone sees who created it)
            "createdByUid" to (snap.getString("createdByUid") ?: ""),
            "editedByUid" to (snap.getString("editedByUid") ?: "")
        )
    }

    private fun ensure(ctx: Context) = LocalStore.ensure(ctx)

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.opt(k)
        }
        return map
    }
}

package com.abk.brodue

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Local-first persistence: all app data lives in the app's private storage,
// namespaced per signed-in account. Firebase Realtime Database is no longer used.
object LocalStore {

    private val executor = Executors.newSingleThreadExecutor()
    private var root: JSONObject? = null
    private var curUid: String? = null

    private fun fileFor(context: Context): File {
        val uid = try { UserDb.uid() } catch (_: Exception) { null }
        val name = if (uid != null) "local_data_$uid.json" else "local_data.json"
        return File(context.filesDir, name)
    }

    @Synchronized
    fun ensure(context: Context): JSONObject {
        val uid = try { UserDb.uid() } catch (_: Exception) { null }
        if (root == null || curUid != uid) {
            curUid = uid
            root = try {
                val f = fileFor(context)
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
        }
        return root!!
    }

    // Full-DB snapshots: only the latest one matters, so bursts (e.g. a
    // 500-tx pull) collapse into ~1 write. Serialization + I/O both happen
    // off the calling thread - persist() itself never blocks the UI.
    private val persistVersion = AtomicLong(0)
    private val writtenVersion = AtomicLong(0)

    fun persist(context: Context) {
        persistVersion.incrementAndGet()
        val appCtx = context.applicationContext
        val file = try { fileFor(appCtx) } catch (_: Exception) { return }
        executor.execute {
            try {
                while (writtenVersion.get() < persistVersion.get()) {
                    val snapshot: String = synchronized(this@LocalStore) {
                        root?.toString()
                    } ?: break
                    try { file.writeText(snapshot) } catch (_: Exception) {}
                    writtenVersion.set(persistVersion.get())
                }
            } catch (_: Exception) {}
        }
    }

    // Fresh install = no people and no transactions stored locally
    fun hasLocalData(context: Context): Boolean {
        return try {
            val r = ensure(context)
            (r.optJSONObject("people")?.length() ?: 0) > 0 ||
                (r.optJSONObject("transactions")?.length() ?: 0) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun people(context: Context): JSONObject =
        ensure(context).optJSONObject("people") ?: JSONObject()

    fun transactions(context: Context): JSONObject =
        ensure(context).optJSONObject("transactions") ?: JSONObject()

    fun settings(context: Context): JSONObject =
        ensure(context).optJSONObject("settings") ?: JSONObject()

    fun upsertPerson(context: Context, id: String, data: Map<String, Any?>) {
        val r = ensure(context)
        val people = people(context)
        people.put(id, JSONObject(data as Map<*, *>))
        r.put("people", people)
        persist(context)
        // Only synced persons are pushed to Firestore (per-person sync)
        mirror { if (ShareSync.isCloudPerson(context, id)) ShareSync.writePerson(context, id) }
    }

    fun updatePersonFields(
        context: Context,
        id: String,
        fields: Map<String, Any?>,
        mirror: Boolean = true
    ) {
        val r = ensure(context)
        val people = people(context)
        val p = people.optJSONObject(id) ?: JSONObject()
        fields.forEach { (k, v) -> p.put(k, v) }
        people.put(id, p)
        r.put("people", people)
        persist(context)
        // mirror=false: DB-first flows that already wrote the cloud copy
        if (mirror) mirror { if (ShareSync.isCloudPerson(context, id)) ShareSync.writePerson(context, id) }
    }

    fun removePerson(context: Context, id: String) {
        val r = ensure(context)
        val people = people(context)
        people.remove(id)
        r.put("people", people)
        persist(context)
        mirror {
            if (ShareSync.isCloudPerson(context, id)) {
                ShareSync.deleteSharedPerson(id)
                // Cloud doc gone: free the sync slot too
                ShareSync.releaseSyncSlot(context, id)
            }
        }
    }

    fun putTransaction(context: Context, txId: String, data: Map<String, Any?>, mirror: Boolean = true) {
        val r = ensure(context)
        val txs = transactions(context)
        txs.put(txId, JSONObject(data as Map<*, *>))
        r.put("transactions", txs)
        persist(context)
        if (mirror) mirror {
            val pid = data["personId"] as? String ?: return@mirror
            if (ShareSync.isCloudPerson(context, pid)) ShareSync.writeTx(context, pid, txId)
        }
    }

    fun removeTransaction(context: Context, txId: String, mirror: Boolean = true) {
        val r = ensure(context)
        val txs = transactions(context)
        val pid = txs.optJSONObject(txId)?.optString("personId", "")
        txs.remove(txId)
        r.put("transactions", txs)
        persist(context)
        if (mirror) mirror {
            if (pid != null && ShareSync.isCloudPerson(context, pid)) ShareSync.deleteTx(context, pid, txId)
        }
    }

    // Remote apply - writes local WITHOUT mirroring (prevents echo loops)
    fun applyRemotePerson(context: Context, id: String, data: Map<String, Any?>) {
        val r = ensure(context)
        val people = people(context)
        people.put(id, JSONObject(data as Map<*, *>))
        r.put("people", people)
        persist(context)
    }

    fun applyRemoteTx(context: Context, txId: String, data: Map<String, Any?>) {
        val r = ensure(context)
        val txs = transactions(context)
        txs.put(txId, JSONObject(data as Map<*, *>))
        r.put("transactions", txs)
        persist(context)
    }

    fun removeRemoteTx(context: Context, txId: String) {
        val r = ensure(context)
        val txs = transactions(context)
        txs.remove(txId)
        r.put("transactions", txs)
        persist(context)
    }

    @Synchronized
    fun setSetting(context: Context, key: String, value: Any?) {
        val r = ensure(context)
        val s = r.optJSONObject("settings") ?: JSONObject()
        s.put(key, value)
        r.put("settings", s)
        persist(context)
    }

    fun getSetting(context: Context, key: String): Any? =
        settings(context).opt(key)

    @Synchronized
    fun setStats(context: Context, stats: Map<String, Any?>) {
        val r = ensure(context)
        r.put("stats", JSONObject(stats as Map<*, *>))
        persist(context)
    }

    fun setPeopleCount(context: Context, count: Int) {
        val r = ensure(context)
        r.put("peopleCount", count)
        persist(context)
    }

    // Best-effort async mirror to Firestore (never blocks the UI)
    private fun mirror(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {}
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.opt(key)
        }
        return map
    }

    fun getPeopleCount(context: Context): Int = ensure(context).optInt("peopleCount", 0)

    // Full replace (restore) + persist
    @Synchronized
    fun loadFromJson(context: Context, json: JSONObject) {
        root = json
        curUid = try { UserDb.uid() } catch (_: Exception) { null }
        persist(context)
    }

    // Whole root as JSON string (for backup)
    fun rootJson(context: Context): String = ensure(context).toString()

    // Clear current user's data (account switch / reset)
    fun clear(context: Context) {
        ensure(context).keys().forEach { ensure(context).remove(it) }
        persist(context)
    }
}

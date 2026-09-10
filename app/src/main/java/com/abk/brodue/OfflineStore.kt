package com.abk.brodue

import android.content.Context
import org.json.JSONObject

// Legacy offline-snapshot helpers - now backed by LocalStore.
// Kept so older call sites keep compiling; the app itself is fully local-first.
object OfflineStore {

    fun save(context: Context, peopleJson: JSONObject, transactionsJson: JSONObject) {
        try {
            LocalStore.setSetting(context, "snap_people", peopleJson.toString())
            LocalStore.setSetting(context, "snap_transactions", transactionsJson.toString())
        } catch (_: Exception) {}
    }

    fun load(context: Context): JSONObject? {
        return try {
            val root = JSONObject()
            val p = LocalStore.getSetting(context, "snap_people") as? String
            val t = LocalStore.getSetting(context, "snap_transactions") as? String
            if (p != null) root.put("people", JSONObject(p))
            if (t != null) root.put("transactions", JSONObject(t))
            if (root.keys().hasNext()) root else null
        } catch (_: Exception) { null }
    }

    fun peopleJson(root: JSONObject?): JSONObject = root?.optJSONObject("people") ?: JSONObject()

    fun transactionsJson(root: JSONObject?): JSONObject = root?.optJSONObject("transactions") ?: JSONObject()

    fun hasData(context: Context): Boolean = load(context) != null
}

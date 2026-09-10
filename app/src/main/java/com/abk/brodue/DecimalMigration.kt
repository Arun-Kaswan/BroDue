package com.abk.brodue

import android.content.Context

// One-time migration: whole-rupee amounts (Long) -> minor units / paise.
// Runs before any data is read. Local data is converted in place; synced
// persons are pushed to the cloud so all sides converge. Joined persons
// converge on the owner's migration + the next pull.
object DecimalMigration {

    private const val KEY = "minor_units_v1"

    fun run(ctx: Context) {
        try {
            if ((LocalStore.getSetting(ctx, KEY) as? Boolean) == true) return
            // Local people + transactions (direct JSON edit: no cloud mirror storm)
            val people = LocalStore.people(ctx)
            val pk = people.keys()
            while (pk.hasNext()) {
                val pid = pk.next()
                val o = people.optJSONObject(pid) ?: continue
                o.put("netAmount", o.optLong("netAmount", 0L) * 100L)
                o.put("units", "minor")
            }
            val txs = LocalStore.transactions(ctx)
            val tk = txs.keys()
            while (tk.hasNext()) {
                val tid = tk.next()
                val t = txs.optJSONObject(tid) ?: continue
                t.put("amount", t.optLong("amount", 0L) * 100L)
                t.put("units", "minor")
            }
            LocalStore.persist(ctx)
            LocalStore.setSetting(ctx, KEY, true)
            // Push synced persons so the cloud converges to paise too
            try {
                ShareSync.syncedIds(ctx).forEach { pid ->
                    ShareSync.writePerson(ctx, pid)
                    val keys = LocalStore.transactions(ctx).keys()
                    while (keys.hasNext()) {
                        val tid = keys.next()
                        val t = LocalStore.transactions(ctx).optJSONObject(tid) ?: continue
                        if (t.optString("personId", "") == pid) {
                            ShareSync.writeTx(ctx, pid, tid)
                        }
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }
}

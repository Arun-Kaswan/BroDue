package com.abk.brodue

import android.content.Context
import org.json.JSONObject

// Per-person money aggregates live ON the person record
// (netAmount, received, gave, receivedCount, gaveCount). Every totals UI
// (logs page, global stats, cloud sync) sums those stored fields instead
// of re-scanning all transactions.
object PersonTotals {

    // Single pass over one person's transactions:
    // [net, received, gave, receivedCount, gaveCount]
    fun accumulate(txs: JSONObject, personId: String): LongArray {
        var net = 0L
        var received = 0L
        var gave = 0L
        var rCount = 0L
        var gCount = 0L
        val keys = txs.keys()
        while (keys.hasNext()) {
            val t = txs.optJSONObject(keys.next()) ?: continue
            if (t.optString("personId", "") != personId) continue
            val amount = t.optLong("amount", 0L)
            if (t.optString("type", "gave") == "received") {
                net += amount
                received += amount
                rCount++
            } else {
                net -= amount
                gave += amount
                gCount++
            }
        }
        return longArrayOf(net, received, gave, rCount, gCount)
    }

    fun hasTotals(person: JSONObject): Boolean =
        person.has("receivedCount") && person.has("gaveCount") &&
            person.has("received") && person.has("gave")

    fun fieldMap(totals: LongArray): Map<String, Any?> = mapOf(
        "netAmount" to totals[0],
        "received" to totals[1],
        "gave" to totals[2],
        "receivedCount" to totals[3].toInt(),
        "gaveCount" to totals[4].toInt()
    )

    // Self-healing: fill any person missing totals (fresh restores, legacy
    // data), then persist once. Afterwards this is just has() checks -
    // no transaction scans.
    fun ensureAll(ctx: Context) {
        try {
            val people = LocalStore.people(ctx)
            val pk = people.keys()
            val missing = mutableListOf<String>()
            while (pk.hasNext()) {
                val pid = pk.next()
                val o = people.optJSONObject(pid) ?: continue
                if (!hasTotals(o)) missing.add(pid)
            }
            if (missing.isEmpty()) return
            val txs = LocalStore.transactions(ctx)
            var changed = false
            missing.forEach { pid ->
                val o = people.optJSONObject(pid) ?: return@forEach
                fieldMap(accumulate(txs, pid)).forEach { (k, v) -> o.put(k, v) }
                changed = true
            }
            if (changed) LocalStore.persist(ctx)
        } catch (_: Exception) {}
    }
}

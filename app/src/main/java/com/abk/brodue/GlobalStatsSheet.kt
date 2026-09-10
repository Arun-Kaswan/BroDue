package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject

class GlobalStatsSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_global_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val snapshotJson = requireArguments().getString(ARG_SNAPSHOT)
        if (snapshotJson != null) {
            computeFromSnapshot(view, JSONObject(snapshotJson))
        } else {
            computeFromFirebase(view)
        }
    }

    private fun computeFromSnapshot(view: View, snap: JSONObject) {
        var overall = 0L
        var pos = 0L
        var neg = 0L
        var receivedCount = 0
        var gaveCount = 0
        var receivedAmount = 0L
        var gaveAmount = 0L
        // Flat layout: { "people": {...}, "transactions": {...} }
        val peopleJson = if (snap.has("people")) snap.optJSONObject("people") ?: JSONObject() else snap
        val txsJson = snap.optJSONObject("transactions") ?: JSONObject()
        // Stored per-person fields; legacy snapshots missing them fall back
        // to a transaction scan for those persons only.
        val legacyIds = mutableSetOf<String>()
        val peopleKeys = peopleJson.keys()
        while (peopleKeys.hasNext()) {
            val pid = peopleKeys.next()
            val personObj = peopleJson.optJSONObject(pid) ?: continue
            if (personObj.optBoolean("archived", false)) continue
            val net = personObj.optLong("netAmount", 0L)
            overall += net
            if (net > 0) pos += net
            if (net < 0) neg += net
            if (PersonTotals.hasTotals(personObj)) {
                receivedCount += personObj.optInt("receivedCount", 0)
                receivedAmount += personObj.optLong("received", 0L)
                gaveCount += personObj.optInt("gaveCount", 0)
                gaveAmount += personObj.optLong("gave", 0L)
            } else {
                legacyIds.add(pid)
            }
        }
        if (legacyIds.isNotEmpty()) {
            val txKeys = txsJson.keys()
            while (txKeys.hasNext()) {
                val t = txsJson.optJSONObject(txKeys.next()) ?: continue
                if (t.optString("personId", "") !in legacyIds) continue
                val amount = t.optLong("amount", 0L)
                if (t.optString("type", "gave") == "received") {
                    receivedCount++
                    receivedAmount += amount
                } else {
                    gaveCount++
                    gaveAmount += amount
                }
            }
        }
        applyStats(view, overall, pos, neg, receivedCount, gaveCount, receivedAmount, gaveAmount)
    }

    private fun computeFromFirebase(view: View) {
        // Local-first: stored per-person fields, no transaction scan
        PersonTotals.ensureAll(requireContext())
        val peopleJson = LocalStore.people(requireContext())
        var overall = 0L
        var pos = 0L
        var neg = 0L
        var receivedCount = 0
        var gaveCount = 0
        var receivedAmount = 0L
        var gaveAmount = 0L
        val pKeys = peopleJson.keys()
        while (pKeys.hasNext()) {
            val po = peopleJson.optJSONObject(pKeys.next()) ?: continue
            if (po.optBoolean("archived", false)) continue
            val net = po.optLong("netAmount", 0L)
            overall += net
            if (net > 0) pos += net
            if (net < 0) neg += net
            receivedCount += po.optInt("receivedCount", 0)
            receivedAmount += po.optLong("received", 0L)
            gaveCount += po.optInt("gaveCount", 0)
            gaveAmount += po.optLong("gave", 0L)
        }
        applyStats(view, overall, pos, neg, receivedCount, gaveCount, receivedAmount, gaveAmount)
    }

    private fun applyStats(
        view: View,
        overall: Long,
        pos: Long,
        neg: Long,
        receivedCount: Int,
        gaveCount: Int,
        receivedAmount: Long,
        gaveAmount: Long
    ) {
        val tvNet = view.findViewById<TextView>(R.id.tvGlobalNet)
        tvNet.text = if (overall > 0) {
            android.text.TextUtils.concat("+", Formatters.amount(overall))
        } else {
            Formatters.amount(overall)
        }
        tvNet.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    overall > 0 -> R.color.positive
                    overall < 0 -> R.color.negative
                    else -> R.color.navy_text
                }
            )
        )
        view.findViewById<TextView>(R.id.tvPosTotal).text =
            android.text.TextUtils.concat("+", Formatters.amount(pos))
        view.findViewById<TextView>(R.id.tvNegTotal).text = Formatters.amount(neg)
        view.findViewById<TextView>(R.id.tvGlobalReceivedLabel).text = getString(R.string.receive_count, receivedCount)
        view.findViewById<TextView>(R.id.tvGlobalReceivedAmount).text = Formatters.amount(receivedAmount)
        view.findViewById<TextView>(R.id.tvGlobalSendLabel).text = getString(R.string.send_count, gaveCount)
        view.findViewById<TextView>(R.id.tvGlobalSendAmount).text = Formatters.amount(gaveAmount)
    }

    companion object {
        private const val ARG_SNAPSHOT = "snapshot"
        const val TAG = "GlobalStatsSheet"

        fun newInstance(snapshot: JSONObject? = null): GlobalStatsSheet = GlobalStatsSheet().apply {
            arguments = Bundle().apply {
                if (snapshot != null) putString(ARG_SNAPSHOT, snapshot.toString())
            }
        }
    }
}
package com.abk.brodue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// Receives worker-sent pushes (tx adds/edits/deletes, joins) and shows them.
// Tapping opens the person detail when possible, else the app home.
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            ShareSync.registerPushToken(applicationContext, token)
        } catch (_: Exception) {}
    }

    // Avatar for the notification: account picture when "share as me" shows
    // the owner, otherwise the app's local initial-avatar, drawn to bitmap.
    private fun personAvatar(personId: String, displayName: String): android.graphics.Bitmap? {
        try {
            val o = LocalStore.people(this).optJSONObject(personId)
            val shareAsMeActive = o != null &&
                ShareSync.isCloudPerson(this, personId) &&
                o.optBoolean("shareAsMe", false) &&
                ShareSync.accessRole(this, personId) != "owner"
            if (shareAsMeActive) {
                val url = o?.optString("ownerPhotoUrl", "").orEmpty()
                if (url.isNotBlank()) {
                    circle(loadUrlBitmap(url))?.let { return it }
                }
                return initialAvatar(o?.optString("ownerName", "").orEmpty().ifBlank { displayName })
            }
            return initialAvatar(displayName)
        } catch (_: Exception) {
            return null
        }
    }

    private fun loadUrlBitmap(url: String): android.graphics.Bitmap? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun circle(src: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (src == null) return null
        return try {
            val size = minOf(src.width, src.height)
            val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            val dx = (size - src.width) / 2f
            val dy = (size - src.height) / 2f
            canvas.drawBitmap(src, dx, dy, paint)
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun initialAvatar(name: String): android.graphics.Bitmap? {
        return try {
            val res = resources
            val letters = res.getIntArray(R.array.avatar_letter_colors)
            val bgs = res.getIntArray(R.array.avatar_bg_colors)
            val idx = (name.hashCode() and Int.MAX_VALUE) % letters.size
            val size = (72 * res.displayMetrics.density).toInt()
            val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            bg.color = bgs[idx]
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
            val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fg.color = letters[idx]
            fg.textSize = size * 0.42f
            fg.textAlign = android.graphics.Paint.Align.CENTER
            fg.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val letter = name.trim().take(1).uppercase().ifEmpty { "•" }
            val y = size / 2f - (fg.descent() + fg.ascent()) / 2f
            canvas.drawText(letter, size / 2f, y, fg)
            out
        } catch (_: Exception) {
            null
        }
    }

    // WhatsApp-style background sync: the push already carries the full tx
    // + fresh aggregates, so we write straight to disk - no Firestore reads,
    // no opening the app. Returns false when the mail isn't for the current
    // account (another account's push arriving on a shared token).
    private fun applyPushData(data: Map<String, String>): Boolean {
        try {
            if (data["kind"] != "tx") return true
            val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (me == null) return true // unverifiable: notify only, write nothing
            if (data["forUid"] != me) return false
            val pid = data["personId"].orEmpty()
            if (pid.isBlank()) return true
            val people = LocalStore.people(this)
            val p = people.optJSONObject(pid) ?: return true // unknown: next pull heals
            val txs = LocalStore.transactions(this)
            if (data["sub"] == "delete") {
                val goneId = data["txId"].orEmpty()
                txs.remove(goneId)
                // Keep the newest-entry preview exact: if the doomed tx was
                // the preview, re-point at the remaining max (zero reads).
                val lt = p.optJSONObject("latestTx")
                if (lt != null && lt.optString("txId", "") == goneId) {
                    var bestKey = ""
                    var bestSaved = -1L
                    val k2 = txs.keys()
                    while (k2.hasNext()) {
                        val key = k2.next()
                        val t = txs.optJSONObject(key) ?: continue
                        if (t.optString("personId", "") != pid) continue
                        val s = t.optLong("savedAt", 0L)
                        if (s >= bestSaved) {
                            bestSaved = s
                            bestKey = key
                        }
                    }
                    if (bestKey.isNotBlank()) {
                        val bt = txs.optJSONObject(bestKey)!!
                        p.put(
                            "latestTx", org.json.JSONObject()
                                .put("txId", bestKey)
                                .put("amount", bt.optLong("amount", 0L))
                                .put("type", bt.optString("type", "gave"))
                                .put("category", bt.optString("category", "General"))
                                .put("savedAt", bt.optLong("savedAt", 0L))
                        )
                    } else {
                        p.remove("latestTx")
                    }
                }
            } else {
                val txId = data["txId"].orEmpty()
                val amount = data["txAmount"]?.toLongOrNull() ?: return true
                val type = data["txType"].orEmpty().ifBlank { return true }
                val savedAt = data["txSavedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                val o = txs.optJSONObject(txId) ?: org.json.JSONObject()
                o.put("personId", pid)
                o.put("category", data["txCategory"].orEmpty().ifBlank { "General" })
                o.put("amount", amount)
                o.put("type", type)
                o.put("note", data["txNote"].orEmpty())
                o.put("createdAt", data["txCreatedAt"]?.toLongOrNull() ?: System.currentTimeMillis())
                o.put("savedAt", savedAt)
                o.put("units", "minor")
                // Someone else's change is unseen by definition (own change
                // echoing onto another of my devices is already seen). The
                // loaded marker stays put for foreign changes so the home
                // red dot appears; own changes move it with the stamp.
                val mine = data["own"] == "1"
                o.put("unseen", !mine)
                txs.put(txId, o)
                if (mine) {
                    data["txUpdatedAt"]?.toLongOrNull()?.let {
                        p.put("txUpdatedAtLoaded", it)
                    }
                }
                // A fresh save is the newest touch by construction.
                p.put(
                    "latestTx", org.json.JSONObject()
                        .put("txId", txId)
                        .put("amount", amount)
                        .put("type", type)
                        .put("category", data["txCategory"].orEmpty().ifBlank { "General" })
                        .put("savedAt", savedAt)
                )
            }
            // Stamp the server aggregates (not the loaded marker).
            data["netAmount"]?.toLongOrNull()?.let { p.put("netAmount", it) }
            data["received"]?.toLongOrNull()?.let { p.put("received", it) }
            data["gave"]?.toLongOrNull()?.let { p.put("gave", it) }
            data["receivedCount"]?.toIntOrNull()?.let { p.put("receivedCount", it) }
            data["gaveCount"]?.toIntOrNull()?.let { p.put("gaveCount", it) }
            data["txUpdatedAt"]?.toLongOrNull()?.let {
                p.put("txUpdatedAt", it)
                p.put("updatedAt", it)
            }
            LocalStore.persist(this)
            return true
        } catch (_: Exception) {
            return true
        }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        try {
            val data = msg.data
            if (!applyPushData(data)) return // not this account's mail
            // Live UI: the tx realtime listener is gone (push covers entry
            // flow), so wake a foreground app to repaint from disk. The app
            // may be dead - then this is a no-op and startup renders cache.
            if (data["kind"] == "tx") {
                try {
                    sendBroadcast(
                        android.content.Intent(ACTION_TX_PUSH).setPackage(packageName)
                    )
                } catch (_: Exception) {}
            }
            val personId = data["personId"].orEmpty()

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (personId.isNotBlank()) putExtra(EXTRA_OPEN_PERSON, personId)
            }
            val pi = PendingIntent.getActivity(
                this, personId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            ensureChannel()
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                // Small app logo stays in the system header (top-left, with
                // the app name); the person's avatar takes the large slot.
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            if (data["kind"] == "tx") {
                // Custom entry layout: name + small grey category on the
                // left, very large signed amount (app red/green) on right.
                val views = android.widget.RemoteViews(packageName, R.layout.notification_entry)
                // Avatar lives at the very left of the content row (not as
                // a large icon, which would duplicate it on the right). The
                // app logo stays in the system header, before the app name.
                personAvatar(personId, data["displayName"].orEmpty())?.let {
                    views.setImageViewBitmap(R.id.ntvAvatar, it)
                }
                views.setTextViewText(R.id.ntvName, data["displayName"].orEmpty())
                views.setTextViewText(R.id.ntvCategory, data["category"].orEmpty())
                views.setTextViewText(R.id.ntvAmount, data["amountText"].orEmpty())
                val received = data["isReceived"] == "1"
                views.setTextColor(
                    R.id.ntvAmount,
                    androidx.core.content.ContextCompat.getColor(
                        this, if (received) R.color.save_green else R.color.save_red
                    )
                )
                builder.setCustomContentView(views)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setContentTitle(data["displayName"].orEmpty())
                    .setContentText(data["amountText"].orEmpty())
            } else {
                val title = msg.notification?.title
                    ?: data["title"] ?: getString(R.string.app_name)
                val body = msg.notification?.body ?: data["body"] ?: return
                builder.setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
            // Stack per person: every post joins the person's group; a
            // summary collapses them ("3 new entries" + recent lines).
            val group = "person_" + personId.hashCode()
            builder.setGroup(group)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Unique id per post: reusing personId.hashCode() made each new
            // notification replace the previous one for the same person.
            val notifId = (personId + "|" + System.currentTimeMillis() + "|" + Math.random()).hashCode()
            nm.notify(notifId, builder.build())
            updateSummary(nm, group, personId, pi)
        } catch (_: Exception) {}
    }

    // Group summary for one person's stack: rebuilt from the live stack on
    // every post, removed when 0-1 children remain (a lone child needs no
    // summary). Stable id per person so it updates instead of duplicating.
    private fun updateSummary(
        nm: NotificationManager, group: String, personId: String, pi: PendingIntent
    ) {
        try {
            val kids = nm.activeNotifications.filter {
                group == it.notification.group &&
                    it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
            }
            val summaryId = ("summary_" + personId).hashCode()
            if (kids.size <= 1) {
                try {
                    nm.cancel(summaryId)
                } catch (_: Exception) {}
                return
            }
            val inbox = NotificationCompat.InboxStyle()
            kids.takeLast(5).forEach { sbn ->
                val line = sbn.notification.extras.getCharSequence(
                    android.app.Notification.EXTRA_TEXT
                )?.toString().orEmpty()
                val head = sbn.notification.extras.getCharSequence(
                    android.app.Notification.EXTRA_TITLE
                )?.toString().orEmpty()
                val row = if (head.isNotBlank() && line.isNotBlank() && head != line) "$head $line" else (line.ifBlank { head })
                if (row.isNotBlank()) inbox.addLine(row)
            }
            val title = kids.lastOrNull()?.notification?.extras?.getCharSequence(
                android.app.Notification.EXTRA_TITLE
            )?.toString().orEmpty().ifBlank { getString(R.string.app_name) }
            val summary = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(
                    resources.getQuantityString(R.plurals.push_summary_entries, kids.size, kids.size)
                )
                .setStyle(inbox)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(summaryId, summary)
        } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.push_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "brodue_updates"
        const val EXTRA_OPEN_PERSON = "open_person_id"
        const val ACTION_TX_PUSH = "com.abk.brodue.action.TX_PUSH"
    }
}

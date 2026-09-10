package com.abk.brodue

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateManager {

    private const val RELEASES_URL = "https://api.github.com/repos/Arun-Kaswan/BroDue/releases/latest"
    private const val PREFS = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_UPDATE_FILE = "update_file"
    private const val KEY_UPDATE_VER = "update_ver"
    private const val KEY_UPDATE_TIME = "update_time"
    private const val KEY_PENDING_TAG = "pending_tag"
    private const val KEY_PENDING_NAME = "pending_name"
    private const val KEY_PENDING_BODY = "pending_body"
    private const val KEY_PENDING_APK_URL = "pending_apk_url"
    private const val KEY_PENDING_APK_NAME = "pending_apk_name"
    private const val KEY_PENDING_HTML_URL = "pending_html_url"
    private const val STALE_MS = 24 * 60 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor()

    fun fileNameFor(tag: String): String {
        val safe = tag.trim().removePrefix("v").replace(Regex("[^A-Za-z0-9]+"), "_")
        return "brodue-update-$safe.apk"
    }

    private fun updatePrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveDownloaded(context: Context, tag: String, file: java.io.File) {
        try {
            // Sweep the previous file (and the legacy external-files copy)
            // so stale APKs never accumulate
            val oldPath = updatePrefs(context).getString(KEY_UPDATE_FILE, null)
            if (oldPath != null && oldPath != file.absolutePath) {
                runCatching { java.io.File(oldPath).takeIf { it.exists() }?.delete() }
            }
            runCatching {
                java.io.File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "brodue-update.apk"
                ).takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) {}
        updatePrefs(context).edit()
            .putString(KEY_UPDATE_FILE, file.absolutePath)
            .putString(KEY_UPDATE_VER, tag)
            .putLong(KEY_UPDATE_TIME, System.currentTimeMillis())
            .apply()
    }

    // Previously downloaded update (tag + file), or null
    fun getDownloaded(context: Context): Pair<String, java.io.File>? {
        return try {
            val p = updatePrefs(context)
            val tag = p.getString(KEY_UPDATE_VER, null) ?: return null
            val path = p.getString(KEY_UPDATE_FILE, null) ?: return null
            val file = java.io.File(path)
            if (!file.exists()) {
                clearDownloaded(context)
                return null
            }
            tag to file
        } catch (_: Exception) {
            null
        }
    }

    fun clearDownloaded(context: Context) {
        try {
            updatePrefs(context).edit()
                .remove(KEY_UPDATE_FILE)
                .remove(KEY_UPDATE_VER)
                .remove(KEY_UPDATE_TIME)
                .apply()
        } catch (_: Exception) {}
    }

    // Auto-remove: older than a day, or already installed (current >= file)
    fun cleanupStaleUpdate(context: Context) {
        try {
            val (tag, file) = getDownloaded(context) ?: return
            val age = System.currentTimeMillis() - updatePrefs(context).getLong(KEY_UPDATE_TIME, 0L)
            if (age > STALE_MS || isCurrentOrNewer(context, tag)) {
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
                clearDownloaded(context)
            }
        } catch (_: Exception) {}
    }

    fun isCurrentOrNewer(context: Context, tag: String): Boolean {
        return try {
            compareVersions(currentVersion(context), normalizeVersion(tag)) >= 0
        } catch (_: Exception) {
            false
        }
    }

    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val apkUrl: String,
        val apkName: String,
        val htmlUrl: String
    )

    fun checkForUpdates(context: Context, silent: Boolean = true) {
        // Offline builds ship no update manager at all
        if (BuildConfig.OFFLINE_MODE) return
        // Throttle silent auto-check to once per 6 hours
        if (silent) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000) return
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        }

        executor.execute {
            try {
                val json = fetchJson(RELEASES_URL) ?: run {
                    if (!silent) showToast(context, "Failed to check updates")
                    return@execute
                }
                val info = parseRelease(json) ?: run {
                    if (!silent) showToast(context, "No release found")
                    return@execute
                }
                val current = currentVersion(context)
                val latest = normalizeVersion(info.tagName)
                val cmp = compareVersions(latest, current)
                if (cmp > 0) {
                    // New version available
                    (context as? MainActivity)?.runOnUiThread {
                        showUpdateDialog(context, info, current, latest)
                    } ?: run {
                        // Fallback if context not MainActivity
                        android.os.Handler(context.mainLooper).post {
                            showUpdateDialog(context, info, current, latest)
                        }
                    }
                } else {
                    if (!silent) showToast(context, "You're up to date (v$current)")
                }
            } catch (e: Exception) {
                if (!silent) showToast(context, "Update check failed: ${e.message}")
            }
        }
    }

    // Cached pending update: survives restarts so the app instantly knows
    // a new version is available without waiting on the network.
    fun savePendingUpdate(context: Context, info: ReleaseInfo) {
        try {
            updatePrefs(context).edit()
                .putString(KEY_PENDING_TAG, info.tagName)
                .putString(KEY_PENDING_NAME, info.name)
                .putString(KEY_PENDING_BODY, info.body.take(4000))
                .putString(KEY_PENDING_APK_URL, info.apkUrl)
                .putString(KEY_PENDING_APK_NAME, info.apkName)
                .putString(KEY_PENDING_HTML_URL, info.htmlUrl)
                .apply()
        } catch (_: Exception) {}
    }

    fun getPendingUpdate(context: Context): ReleaseInfo? {
        return try {
            val p = updatePrefs(context)
            val tag = p.getString(KEY_PENDING_TAG, null) ?: return null
            if (isCurrentOrNewer(context, tag)) {
                clearPendingUpdate(context)
                return null
            }
            ReleaseInfo(
                tag,
                p.getString(KEY_PENDING_NAME, tag).orEmpty(),
                p.getString(KEY_PENDING_BODY, "").orEmpty(),
                p.getString(KEY_PENDING_APK_URL, "").orEmpty(),
                p.getString(KEY_PENDING_APK_NAME, "app-release.apk").orEmpty(),
                p.getString(KEY_PENDING_HTML_URL, "https://github.com/Arun-Kaswan/BroDue/releases/latest").orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearPendingUpdate(context: Context) {
        try {
            updatePrefs(context).edit()
                .remove(KEY_PENDING_TAG)
                .remove(KEY_PENDING_NAME)
                .remove(KEY_PENDING_BODY)
                .remove(KEY_PENDING_APK_URL)
                .remove(KEY_PENDING_APK_NAME)
                .remove(KEY_PENDING_HTML_URL)
                .apply()
        } catch (_: Exception) {}
    }

    // For System Update page — callback based, no dialog
    fun checkForUpdateInPage(context: Context, callback: (Result<ReleaseInfo?>) -> Unit) {
        // Offline builds ship no update manager at all
        if (BuildConfig.OFFLINE_MODE) {
            callback(Result.success(null))
            return
        }
        executor.execute {
            try {
                val json = fetchJson(RELEASES_URL)
                if (json == null) {
                    callback(Result.failure(Exception("Failed to fetch release")))
                    return@execute
                }
                val info = parseRelease(json)
                if (info == null) {
                    callback(Result.failure(Exception("No release found")))
                    return@execute
                }
                val current = currentVersion(context)
                val latest = normalizeVersion(info.tagName)
                val cmp = compareVersions(latest, current)
                if (cmp > 0) {
                    savePendingUpdate(context, info)
                    callback(Result.success(info))
                } else {
                    clearPendingUpdate(context)
                    callback(Result.success(null))
                }
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun currentVersionName(context: Context): String = currentVersion(context)

    private fun fetchJson(urlStr: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "BroDue-App")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        return try {
            val code = conn.responseCode
            if (code != 200) return null
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) sb.append(line)
            reader.close()
            sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(jsonStr: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(jsonStr)
            val tag = obj.optString("tag_name", "")
            if (tag.isEmpty()) return null
            val name = obj.optString("name", tag)
            val body = obj.optString("body", "")
            val htmlUrl = obj.optString("html_url", "https://github.com/Arun-Kaswan/BroDue/releases/latest")
            var apkUrl = ""
            var apkName = "app-release.apk"
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val n = a.optString("name", "")
                    if (n.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkName = n
                        break
                    }
                }
                // Fallback to first asset if no apk found
                if (apkUrl.isEmpty() && assets.length() > 0) {
                    val a = assets.getJSONObject(0)
                    apkUrl = a.optString("browser_download_url", "")
                    apkName = a.optString("name", "app-release.apk")
                }
            }
            if (apkUrl.isEmpty()) {
                // No apk asset, use htmlUrl
                apkUrl = htmlUrl
            }
            ReleaseInfo(tag, name, body, apkUrl, apkName, htmlUrl)
        } catch (_: Exception) { null }
    }

    private fun currentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
    }

    private fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    // Returns >0 if a > b, 0 if equal, <0 if a < b
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-")
        val pb = b.split(".", "-")
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val ai = pa.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val bi = pb.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    private fun showToast(context: Context, msg: String) {
        try {
            android.os.Handler(context.mainLooper).post {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun showUpdateDialog(context: Context, info: ReleaseInfo, current: String, latest: String) {
        try {
            val cleanBody = info.body.take(400).trim().ifEmpty { "A new version is available." }
            AlertDialog.Builder(context)
                .setTitle("Update available — v$latest")
                .setMessage("You're on v$current.\n\n${info.name}\n\n$cleanBody\n")
                .setPositiveButton("Download & Install") { _, _ ->
                    if (info.apkUrl.endsWith(".apk")) {
                        downloadAndInstall(context, info.apkUrl, info.apkName)
                    } else {
                        // No apk, open releases page
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                        } catch (_: Exception) {}
                    }
                }
                .setNegativeButton("Later", null)
                .setNeutralButton("View on GitHub") { _, _ ->
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))) } catch (_: Exception) {}
                }
                .show()
        } catch (_: Exception) {}
    }

    fun downloadAndInstall(context: Context, apkUrl: String, apkName: String) {
        Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
        try {
            val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("BroDue v${apkName}")
                setDescription("Downloading update")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
                // Use external files dir so no extra permission needed
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "brodue-update.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)

            // Listen for completion
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        val q = DownloadManager.Query().setFilterById(id)
                        val cur = dm.query(q)
                        if (cur != null && cur.moveToFirst()) {
                            val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                val uriStr = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                                cur.close()
                                val file = try {
                                    val uri = Uri.parse(uriStr)
                                    // For file:// uri, get path; for content://, copy?
                                    if (uri.scheme == "file") java.io.File(uri.path!!)
                                    else java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "brodue-update.apk")
                                } catch (_: Exception) {
                                    java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "brodue-update.apk")
                                }
                                installApk(context, file)
                            } else {
                                cur.close()
                                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            cur?.close()
                        }
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) {
            // Fallback: open in browser
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) } catch (_: Exception) {
                Toast.makeText(context, "Cannot download: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun installApk(context: Context, file: java.io.File) {
        // Update protection first: safety backup on a background thread,
        // then the existing install flow back on the main thread. Install
        // proceeds even if the backup fails (never brick updates).
        try {
            val appCtx = context.applicationContext
            Thread {
                // Block until the protection backup finishes (bounded wait),
                // then install on the main thread either way.
                val latch = java.util.concurrent.CountDownLatch(1)
                var backedUp = false
                try {
                    AutoBackup.createUpdateProtection(appCtx) { ok ->
                        backedUp = ok
                        latch.countDown()
                    }
                    latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {
                    latch.countDown()
                }
                try {
                    if (backedUp) {
                        android.os.Handler(appCtx.mainLooper).post {
                            try {
                                Toast.makeText(context, "Safety backup saved", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        }
                    }
                    android.os.Handler(appCtx.mainLooper).post { installApkInner(context, file) }
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {
            installApkInner(context, file)
        }
    }

    private fun installApkInner(context: Context, file: java.io.File) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
                return
            }
            // Check install permission on Android O+ (app-themed drawer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val act = context as? androidx.fragment.app.FragmentActivity
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        try {
                            AllowInstallSheet.newInstance()
                                .show(act.supportFragmentManager, AllowInstallSheet.TAG)
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.installs_hint, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, R.string.installs_hint, Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // In-app direct download with progress (no browser). Writes into the
    // app's own files dir (app data, never a shared/root directory) and
    // survives the update page being closed (executor + main-looper posts).
    fun downloadApkDirect(
        context: Context,
        apkUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit,
        onComplete: (java.io.File) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(apkUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "BroDue-App")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    onError("Server returned ${conn.responseCode}")
                    return@execute
                }
                val total = conn.contentLength
                val input = conn.inputStream
                // App cache (never shared storage): cache/updates/
                val dir = java.io.File(context.cacheDir, "updates")
                dir.mkdirs()
                val file = java.io.File(dir, fileName)
                if (file.exists()) file.delete()
                val output = java.io.FileOutputStream(file)
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var lastProgress = -1
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    if (total > 0) {
                        val prog = ((downloaded * 100) / total).toInt()
                        if (prog != lastProgress) {
                            lastProgress = prog
                            android.os.Handler(context.mainLooper).post { onProgress(prog) }
                        }
                    }
                }
                output.flush()
                output.close()
                input.close()
                android.os.Handler(context.mainLooper).post { onComplete(file) }
            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post { onError(e.message ?: "Download failed") }
            } finally {
                conn?.disconnect()
            }
        }
    }
}

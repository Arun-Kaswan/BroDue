package com.abk.brodue

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Automatic backups: interval-gated, change-gated, retention-pruned.
// Manual backups (brodue-backup-*) are never listed, counted, or deleted.
object AutoBackup {

    data class Settings(val enabled: Boolean, val intervalH: Long, val keep: Int)

    val intervalOptions = listOf(
        1L to "1 hour",
        3L to "3 hours",
        6L to "6 hours",
        12L to "12 hours",
        24L to "1 day",
        48L to "2 days",
        168L to "7 days"
    )
    const val DEFAULT_INTERVAL_H = 12L
    const val DEFAULT_KEEP = 6

    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_INTERVAL = "auto_backup_interval_h"
    private const val KEY_KEEP = "auto_backup_keep"

    private val lock = Any()
    @Volatile
    private var running = false

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // Pure sanitizers (unit-tested).
    fun sanitizeInterval(hours: Long): Long =
        intervalOptions.firstOrNull { it.first == hours }?.first ?: DEFAULT_INTERVAL_H

    fun sanitizeKeep(keep: Int): Int = keep.coerceIn(1, 10)

    // Nothing worth saving yet (fresh install / mid-restore window).
    // Backing up emptiness would stamp a useless baseline, so skip silently.
    fun hasBackupableData(peopleCount: Int, txCount: Int): Boolean =
        peopleCount > 0 || txCount > 0

    private fun backupableCounts(ctx: Context): Pair<Int, Int> {
        return try {
            val root = LocalStore.ensure(ctx)
            (root.optJSONObject("people")?.length() ?: 0) to
                (root.optJSONObject("transactions")?.length() ?: 0)
        } catch (_: Exception) {
            0 to 0
        }
    }

    // Retention eligibility (unit-tested, delegates to BackupManager).
    fun isEligibleAutoFile(relativePath: String?, account: String): Boolean =
        BackupManager.isEligibleAutoPath(relativePath, account)

    fun settings(ctx: Context): Settings {
        return try {
            val p = prefs(ctx)
            Settings(
                // ON by default, including for existing users (missing = true)
                enabled = if (p.contains(KEY_ENABLED)) p.getBoolean(KEY_ENABLED, true) else true,
                intervalH = sanitizeInterval(p.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_H)),
                keep = sanitizeKeep(p.getInt(KEY_KEEP, DEFAULT_KEEP))
            )
        } catch (_: Exception) {
            Settings(true, DEFAULT_INTERVAL_H, DEFAULT_KEEP)
        }
    }

    fun intervalLabel(hours: Long): String =
        intervalOptions.firstOrNull { it.first == hours }?.second ?: "$hours hours"

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun setInterval(ctx: Context, hours: Long) {
        prefs(ctx).edit().putLong(KEY_INTERVAL, hours).apply()
    }

    fun setKeep(ctx: Context, keep: Int) {
        prefs(ctx).edit().putInt(KEY_KEEP, keep.coerceIn(1, 10)).apply()
    }

    private fun lastKey(uid: String) = "auto_backup_last_at_$uid"
    private fun hashKey(uid: String) = "auto_backup_last_hash_$uid"
    private fun sizeKey(uid: String) = "auto_backup_last_size_$uid"

    fun lastBackupSize(ctx: Context): Long {
        return try {
            val uid = UserDb.uid() ?: return 0L
            prefs(ctx).getLong(sizeKey(uid), 0L)
        } catch (_: Exception) {
            0L
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    // Last successful automatic backup (0 = never). Shown on the backup page.
    fun lastBackupAt(ctx: Context): Long {
        return try {
            val uid = UserDb.uid() ?: return 0L
            prefs(ctx).getLong(lastKey(uid), 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun dataHash(ctx: Context): String {
        return try {
            val bytes = LocalStore.rootJson(ctx).toByteArray(Charsets.UTF_8)
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun currentFlagsPin(ctx: Context): Triple<Int, String?, Boolean> {
        return try {
            val p = prefs(ctx)
            val accountEnc = p.getBoolean("backup_account_enc", true)
            val pinEnc = p.getBoolean("backup_pin_enc", false)
            val pin = if (pinEnc) p.getString("backup_pin", null) else null
            // A required-but-missing PIN must never silently downgrade encryption
            if (pinEnc && pin.isNullOrBlank()) return Triple(0, null, false)
            val flags = (if (accountEnc) BackupManager.FLAG_ACCOUNT else 0) or
                (if (pinEnc) BackupManager.FLAG_PIN else 0)
            Triple(flags, pin, true)
        } catch (_: Exception) {
            Triple(0, null, false)
        }
    }

    // App start: (re)schedule only. The due-check runs separately AFTER
    // pulls complete (runAfterPulls), so it never snapshots mid-restore.
    fun onAppStart(ctx: Context) {
        try {
            AutoBackupScheduler.schedule(ctx.applicationContext)
        } catch (_: Exception) {}
    }

    // Background due-check for post-pull / settings-toggle opportunities.
    // Offline builds ship no backup system at all.
    fun runInBackground(ctx: Context) {
        if (BuildConfig.OFFLINE_MODE) return
        Thread {
            try {
                // Strict folder hygiene first: no account's autobackup folder
                // may hold more than `keep` files, regardless of when the
                // last successful backup ran (covers reinstall + restore).
                enforceNow(ctx.applicationContext)
                runIfDueSync(ctx.applicationContext)
            } catch (_: Exception) {}
        }.start()
    }

    // Enforce the keep-limit right now (no gates, no new backup).
    // Safe from any thread; returns deleted count.
    fun enforceNow(ctx: Context): Int {
        return try {
            BackupManager.enforceAutoRetention(ctx, settings(ctx).keep)
        } catch (_: Exception) {
            0
        }
    }

    // Update-protection backup: unconditional safety copy before an app
    // update installs (no interval/hash gates - protection is never skipped).
    // Empty datasets are still skipped (nothing to protect). Blocking:
    // callers must stay off the main thread.
    fun createUpdateProtection(ctx: Context, onDone: (Boolean) -> Unit) {
        try {
            val (peopleCount, txCount) = backupableCounts(ctx)
            if (!hasBackupableData(peopleCount, txCount)) {
                onDone(true)
                return
            }
            val (flags, pin, okEnc) = currentFlagsPin(ctx)
            if (!okEnc) {
                onDone(false)
                return
            }
            // Prune protection overflow right away (same strict caps as
            // everywhere else); a failed create simply reports false.
            BackupManager.createUpdateProtectionBackup(ctx, flags, pin) { ok, _ ->
                try {
                    if (ok) {
                        BackupManager.enforceAutoRetention(ctx, settings(ctx).keep)
                    }
                } catch (_: Exception) {}
                onDone(ok)
            }
        } catch (_: Exception) {
            onDone(false)
        }
    }

    // Idempotent: a process-wide lock + last-run re-check inside it mean
    // overlapping triggers can't create two backups for one interval.
    // Blocking (Worker + background callers only, never the main thread).
    fun runIfDueSync(ctx: Context): Boolean {
        // Offline builds ship no backup system at all
        if (BuildConfig.OFFLINE_MODE) return false
        synchronized(lock) {
            if (running) return false
            running = true
        }
        try {
            return doRun(ctx)
        } finally {
            synchronized(lock) { running = false }
        }
    }

    private fun doRun(ctx: Context): Boolean {
        val uid = try {
            UserDb.uid()
        } catch (_: Exception) {
            null
        } ?: return false
        val s = settings(ctx)
        if (!s.enabled) return false
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong(lastKey(uid), 0L) < s.intervalH * 3600_000L) return false
        // Fresh install / mid-restore window: nothing worth saving yet.
        // Skip WITHOUT stamping so the first real data still triggers.
        val (peopleCount, txCount) = backupableCounts(ctx)
        if (!hasBackupableData(peopleCount, txCount)) return false
        // Change gate: hash computed only now (never on every user action)
        val hash = dataHash(ctx)
        if (hash.isBlank()) return false
        if (p.getLong(lastKey(uid), 0L) > 0 && hash == p.getString(hashKey(uid), null)) {
            return false
        }
        val (flags, pin, okEnc) = currentFlagsPin(ctx)
        if (!okEnc) return false
        // Create (already verified in-memory by BackupManager) BEFORE
        // touching anything old
        val latch = CountDownLatch(1)
        var createOk = false
        try {
            BackupManager.createAutoBackup(ctx, flags, pin) { ok, _ ->
                createOk = ok
                latch.countDown()
            }
            latch.await(90, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return false
        }
        // Failed backups never count: no timestamp/hash update, no pruning
        if (!createOk) return false
        // Record the fresh file's size (proves healthy, non-empty backups)
        val freshSize = try {
            BackupManager.listAutoBackups(ctx)
                .filter { it.modified >= now - 120_000L }
                .maxOfOrNull { it.size } ?: 0L
        } catch (_: Exception) {
            0L
        }
        p.edit()
            .putLong(lastKey(uid), now)
            .putString(hashKey(uid), hash)
            .putLong(sizeKey(uid), freshSize)
            .apply()
        // Retention: newest N kept, oldest auto-only files deleted
        try {
            BackupManager.listAutoBackups(ctx)
                .sortedWith(compareByDescending<BackupManager.AutoFile> { it.modified }.thenByDescending { it.name })
                .drop(s.keep)
                .forEach { BackupManager.deleteAutoBackup(ctx, it) }
        } catch (_: Exception) {}
        return true
    }
}

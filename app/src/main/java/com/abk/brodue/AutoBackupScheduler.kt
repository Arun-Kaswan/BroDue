package com.abk.brodue

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AutoBackupScheduler {
    const val WORK_NAME = "auto_backup"
    private const val KEY_SCHEDULED = "auto_backup_scheduled_h"

    fun schedule(ctx: Context) {
        // Offline builds ship no backup system at all
        if (BuildConfig.OFFLINE_MODE) return
        try {
            val appCtx = ctx.applicationContext
            val wm = WorkManager.getInstance(appCtx)
            val s = AutoBackup.settings(ctx)
            val prefs = appCtx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (!s.enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                prefs.edit().remove(KEY_SCHEDULED).apply()
                return
            }
            // Don't reset the worker timer when nothing changed (UPDATE
            // cancels + re-enqueues, restarting the interval on every launch)
            if (prefs.getLong(KEY_SCHEDULED, -1L) == s.intervalH) return
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(s.intervalH, TimeUnit.HOURS)
                .addTag(WORK_NAME)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
            prefs.edit().putLong(KEY_SCHEDULED, s.intervalH).apply()
        } catch (_: Exception) {}
    }
}

package com.abk.brodue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Runs even when the app is closed (WorkManager persists across reboots).
class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (UserDb.uid() == null) return@withContext Result.success()
            AutoBackup.runIfDueSync(applicationContext)
            // No retry storm: a miss/skip simply waits for the next window
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}

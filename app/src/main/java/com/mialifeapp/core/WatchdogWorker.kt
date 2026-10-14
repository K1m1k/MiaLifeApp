package com.mialifeapp.core

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Controllo ogni 15 minuti: se la protezione dovrebbe essere attiva ma il servizio è stato chiuso,
 * prova a farlo ripartire (modalità ridotta) e avvisa l'utente di riaprire l'app.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!SecurePrefs.isAvailable(c) || !SecurePrefs.isOnboarded(c)) return Result.success()
        val shouldRun = SecurePrefs.isArmed(c) || SecurePrefs.isEmergencyActive(c)
        if (!shouldRun) return Result.success()
        if (!ProtectionService.running) {
            ProtectionService.trySend(c, ProtectionService.ACTION_RESTORE)
            Notifications.restoreReminder(c)
        } else if (ProtectionService.degraded) {
            Notifications.restoreReminder(c)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "watchdog"
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

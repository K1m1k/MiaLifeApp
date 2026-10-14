package com.mialifeapp.core

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Riprova ogni 15 minuti (minimo consentito da Android) gli SMS rimasti in coda. */
class SmsRetryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        SmsHelper.flushQueue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "sms_retry"
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<SmsRetryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

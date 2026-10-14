package com.mialifeapp.core.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mialifeapp.core.Notifications
import java.util.concurrent.TimeUnit

/** Caricamento di riserva: parte appena torna internet, anche se il servizio è fermo. */
class DriveUploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = when (DriveUploader.uploadPending(applicationContext)) {
        DriveUploader.Result.OK, DriveUploader.Result.NOTHING -> Result.success()
        DriveUploader.Result.NEEDS_AUTH -> { Notifications.driveReminder(applicationContext); Result.success() }
        DriveUploader.Result.FAILED -> Result.retry()
    }

    companion object {
        private const val NAME = "drive_upload"

        /** [chain] = true accoda un ulteriore giro dopo quello in corso (usato a fine emergenza). */
        fun enqueue(c: Context, chain: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(
                NAME,
                if (chain) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                req
            )
        }
    }
}

package com.mialifeapp.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mialifeapp.R
import com.mialifeapp.ui.WelcomeActivity

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_ALERTS = "alerts"
    const val ID_SERVICE = 1001
    const val ID_RESTORE = 2001
    const val ID_DRIVE = 2002

    fun ensureChannels(c: Context) {
        val mgr = c.getSystemService(NotificationManager::class.java)
        val service = NotificationChannel(CH_SERVICE, c.getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val alerts = NotificationChannel(CH_ALERTS, c.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(true)
        }
        mgr.createNotificationChannel(service)
        mgr.createNotificationChannel(alerts)
    }

    private fun openApp(c: Context): PendingIntent {
        val i = Intent(c, WelcomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Notifica obbligatoria del servizio: volutamente neutra e IDENTICA
     * in stato "pronto" e in emergenza, così non rivela nulla.
     */
    fun service(c: Context): Notification =
        NotificationCompat.Builder(c, CH_SERVICE)
            .setContentTitle(c.getString(R.string.notif_service_title))
            .setContentText(c.getString(R.string.notif_service_text))
            .setSmallIcon(R.drawable.ic_stat_mialife)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(c))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

    /** Avvisi per l'utente. Mai durante un'emergenza: non devono attirare l'attenzione. */
    private fun alert(c: Context, id: Int, title: Int, text: Int) {
        if (SecurePrefs.isEmergencyActive(c)) return
        if (!Perms.hasNotifications(c)) return
        val n = NotificationCompat.Builder(c, CH_ALERTS)
            .setContentTitle(c.getString(title))
            .setContentText(c.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.getString(text)))
            .setSmallIcon(R.drawable.ic_stat_mialife)
            .setContentIntent(openApp(c))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) {}
    }

    fun restoreReminder(c: Context) = alert(c, ID_RESTORE, R.string.notif_restore_title, R.string.notif_restore_text)
    fun driveReminder(c: Context) = alert(c, ID_DRIVE, R.string.notif_drive_title, R.string.notif_drive_text)

    fun cancel(c: Context, id: Int) = NotificationManagerCompat.from(c).cancel(id)
}

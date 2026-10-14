package com.mialifeapp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Dopo il riavvio (o un aggiornamento dell'app) Android non permette di riavviare da soli il
 * microfono. Si riparte quindi in modalità ridotta (colpetti + SMS + posizione) e si mostra
 * la notifica "tocca per riattivare completamente".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WatchdogWorker.schedule(context)
        if (!SecurePrefs.isAvailable(context) || !SecurePrefs.isOnboarded(context)) return
        if (SecurePrefs.isArmed(context) || SecurePrefs.isEmergencyActive(context)) {
            ProtectionService.trySend(context, ProtectionService.ACTION_RESTORE)
            Notifications.restoreReminder(context)
        }
    }
}

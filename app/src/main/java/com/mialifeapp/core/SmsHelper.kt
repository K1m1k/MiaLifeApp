package com.mialifeapp.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager

/**
 * Invio SMS. Se l'invio fallisce (manca il campo) il messaggio va in coda
 * e viene rinviato appena possibile (a ogni aggiornamento e dal lavoro periodico).
 */
object SmsHelper {

    @Suppress("DEPRECATION")
    private fun manager(c: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            c.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        else SmsManager.getDefault()

    fun sendToAll(c: Context, text: String) {
        for (number in SecurePrefs.getContacts(c)) sendOne(c, number, text)
    }

    fun sendOne(c: Context, number: String, text: String) {
        if (!Perms.hasSms(c)) { queue(c, number, text); return }
        try {
            val sm = manager(c)
            val parts = sm.divideMessage(text)
            val result = Intent(c, SmsResultReceiver::class.java).apply {
                putExtra(SmsResultReceiver.EXTRA_NUMBER, number)
                putExtra(SmsResultReceiver.EXTRA_TEXT, text)
            }
            val pi = PendingIntent.getBroadcast(
                c, (number + text).hashCode(), result,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val sent = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(pi) } }
            sm.sendMultipartTextMessage(number, null, parts, sent, null)
        } catch (e: Exception) {
            queue(c, number, text)
        }
    }

    @Synchronized
    fun queue(c: Context, number: String, text: String) {
        val q = SecurePrefs.getSmsQueue(c)
        val item = SecurePrefs.QueuedSms(number, text)
        if (!q.contains(item)) q.add(item)
        SecurePrefs.setSmsQueue(c, q)
        SmsRetryWorker.schedule(c)
    }

    @Synchronized
    fun unqueue(c: Context, number: String, text: String) {
        val q = SecurePrefs.getSmsQueue(c)
        if (q.remove(SecurePrefs.QueuedSms(number, text))) SecurePrefs.setSmsQueue(c, q)
    }

    fun queueSize(c: Context) = SecurePrefs.getSmsQueue(c).size

    /** Riprova a inviare tutto quello che è in coda. */
    fun flushQueue(c: Context) {
        for (item in SecurePrefs.getSmsQueue(c)) sendOne(c, item.number, item.text)
    }
}

package com.mialifeapp.core

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Esito dell'invio SMS: se fallito resta/entra in coda, se riuscito esce dalla coda. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        if (resultCode == Activity.RESULT_OK) SmsHelper.unqueue(context, number, text)
        else SmsHelper.queue(context, number, text)
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_TEXT = "text"
    }
}

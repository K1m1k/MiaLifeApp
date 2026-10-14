package com.mialifeapp.core

import android.content.Context
import android.location.Location
import com.mialifeapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Testi degli SMS inviati ai contatti. */
object Messages {
    enum class Kind { HELP, UPDATE }

    fun time(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))

    fun location(c: Context, kind: Kind, loc: Location?): String {
        val t = time()
        val body = when (kind) {
            Kind.HELP -> if (loc != null) c.getString(R.string.sms_help, t, LocationHelper.describe(loc))
                         else c.getString(R.string.sms_help_noloc, t)
            Kind.UPDATE -> if (loc != null) c.getString(R.string.sms_update, t, LocationHelper.describe(loc))
                           else c.getString(R.string.sms_update_noloc, t)
        }
        return if (kind == Kind.HELP) body + " " + c.getString(R.string.sms_signature) else body
    }

    fun coerced(c: Context) = c.getString(R.string.sms_coerced)
    fun resumed(c: Context) = c.getString(R.string.sms_resumed)
    fun safe(c: Context) = c.getString(R.string.sms_safe, time())
    fun test(c: Context, loc: Location?) =
        if (loc != null) c.getString(R.string.sms_test, time(), LocationHelper.describe(loc))
        else c.getString(R.string.sms_test_noloc, time())

    /** SMS di prova identico a quello vero: cerca la posizione e poi invia a tutti i contatti. */
    fun sendTest(c: Context, done: (Boolean) -> Unit) {
        val ctx = c.applicationContext
        LocationHelper.getCurrent(ctx) { loc ->
            SmsHelper.sendToAll(ctx, test(ctx, loc))
            done(loc != null)
        }
    }
}

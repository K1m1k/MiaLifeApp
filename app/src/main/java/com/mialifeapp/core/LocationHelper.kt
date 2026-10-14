package com.mialifeapp.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale

object LocationHelper {

    /** Chiede la posizione attuale; richiama [cb] (sul thread principale) con la posizione o null. */
    @SuppressLint("MissingPermission")
    fun getCurrent(c: Context, cb: (Location?) -> Unit) {
        var done = false
        val once: (Location?) -> Unit = { if (!done) { done = true; cb(it) } }
        try {
            val client = LocationServices.getFusedLocationProviderClient(c.applicationContext)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) once(loc)
                    else client.lastLocation.addOnSuccessListener { once(it) }.addOnFailureListener { once(null) }
                }
                .addOnFailureListener {
                    client.lastLocation.addOnSuccessListener { once(it) }.addOnFailureListener { once(null) }
                }
        } catch (e: Exception) {
            once(null)
        }
    }

    fun mapsLink(loc: Location): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)

    /** Link + precisione, es. "https://maps.google.com/?q=… (precisione ~15 m)". */
    fun describe(loc: Location): String =
        if (loc.hasAccuracy()) "${mapsLink(loc)} (precisione ~${loc.accuracy.toInt()} m)" else mapsLink(loc)
}

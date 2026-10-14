package com.mialifeapp.core

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Perms {
    fun has(c: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED

    /** Permessi da chiedere nella guida. */
    fun toRequest(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    fun hasSms(c: Context) = has(c, Manifest.permission.SEND_SMS)
    fun hasLocation(c: Context) = has(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasMic(c: Context) = has(c, Manifest.permission.RECORD_AUDIO)
    fun hasNotifications(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(c, Manifest.permission.POST_NOTIFICATIONS)

    fun hasEssentials(c: Context) = hasSms(c) && hasLocation(c) && hasMic(c) && hasNotifications(c)

    fun hasBackgroundLocation(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasScreenLock(c: Context): Boolean =
        (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
}

package com.mialifeapp.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Esclusione dal risparmio energetico + impostazioni specifiche dei produttori. */
object BatteryHelper {

    fun isIgnoring(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    @SuppressLint("BatteryLife")
    fun requestIgnore(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        } catch (e: Exception) {
            try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { openAppDetails(a) }
        }
    }

    fun openAppDetails(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        } catch (_: Exception) {}
    }

    private val brand: String get() = Build.MANUFACTURER.lowercase()

    /** Istruzioni per i produttori che chiudono le app in background, o null se non servono. */
    fun oemInstructions(): String? = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
            "Xiaomi / Redmi / POCO: attiva \"Avvio automatico\" per MiaLifeApp. Poi in Impostazioni › App › MiaLifeApp › Risparmio batteria scegli \"Nessuna restrizione\". Infine, nelle app recenti, tieni premuta MiaLifeApp e tocca il lucchetto."
        brand.contains("huawei") || brand.contains("honor") ->
            "Huawei / Honor: in Batteria › Avvio app trova MiaLifeApp, disattiva \"Gestisci automaticamente\" e attiva tutte e tre le opzioni (avvio automatico, avvio secondario, esecuzione in background)."
        brand.contains("samsung") ->
            "Samsung: in Impostazioni › Batteria › Limiti utilizzo in background aggiungi MiaLifeApp alle \"App mai in sospensione\" e verifica che non sia tra quelle \"in sospensione\"."
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
            "OPPO / realme / OnePlus: in Impostazioni › Batteria › MiaLifeApp consenti \"Attività in background\" e \"Avvio automatico\". Nelle app recenti blocca MiaLifeApp con il lucchetto."
        brand.contains("vivo") || brand.contains("iqoo") ->
            "vivo / iQOO: in Impostazioni › Batteria › Consumo in background elevato attiva MiaLifeApp, e consenti l'avvio automatico."
        else -> null
    }

    private fun oemIntents(): List<Intent> = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
    ).map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Prova ad aprire la pagina del produttore; se non esiste apre le info dell'app. */
    fun openOemSettings(a: Activity) {
        for (i in oemIntents()) {
            try { a.startActivity(i); return } catch (_: Exception) {}
        }
        openAppDetails(a)
    }
}

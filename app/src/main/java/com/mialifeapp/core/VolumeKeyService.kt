package com.mialifeapp.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servizio di accessibilità: l'UNICA parte sempre attiva in stato di riposo.
 * Non usa microfono né posizione: ascolta solo le sequenze del tasto volume, anche a schermo spento.
 *
 *  - sequenza "Allerta"  -> Fase 1: il telefono si mette in guardia (nessun invio).
 *  - sequenza "Aiuto"    -> Fase 2: parte la richiesta di aiuto.
 *
 * Le pressioni del volume NON vengono consumate: il volume del telefono cambia come sempre,
 * così il gesto non attira attenzione.
 */
class VolumeKeyService : AccessibilityService() {

    private val presses = ArrayList<Pair<Char, Long>>()  // ('u'/'d', istante)
    private val windowMs = 4000L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val c = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 'd'
            KeyEvent.KEYCODE_VOLUME_UP -> 'u'
            else -> return false
        }
        val now = System.currentTimeMillis()
        presses.removeAll { now - it.second > windowMs }
        if (presses.isNotEmpty() && now - presses.last().second < 150) return false // doppioni ravvicinati
        presses.add(Pair(c, now))

        val tail = presses.map { it.first }.joinToString("")
        val help = SecurePrefs.getVolHelpSeq(this)
        val alert = SecurePrefs.getVolAlertSeq(this)
        // Prima l'Aiuto (ha priorità), poi l'Allerta.
        if (tail.endsWith(help)) {
            presses.clear()
            ProtectionService.trySend(this, ProtectionService.ACTION_HELP)
        } else if (tail.endsWith(alert)) {
            presses.clear()
            ProtectionService.trySend(this, ProtectionService.ACTION_ALERT)
        }
        return false // non consumiamo il tasto: il volume cambia normalmente
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        /** Il servizio di accessibilità è acceso dall'utente? */
        fun isEnabled(c: Context): Boolean {
            val expected = c.packageName + "/" + VolumeKeyService::class.java.name
            val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) if (splitter.next().equals(expected, ignoreCase = true)) return true
            return false
        }
    }
}

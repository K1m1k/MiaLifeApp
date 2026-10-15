package com.mialifeapp.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mialifeapp.ui.MainActivity

/**
 * Servizio di accessibilità: l'UNICA parte sempre attiva a riposo.
 * Non usa microfono né posizione: ascolta solo i tasti, anche a schermo spento.
 *
 *  - sequenza "Allerta" del volume  -> Fase 1: il telefono si mette in guardia (nessun invio).
 *    Ripetuta durante l'allerta      -> si apre la richiesta del PIN per terminarla.
 *  - sequenza "Aiuto" del volume    -> Fase 2: parte la richiesta di aiuto.
 *  - pulsante Bluetooth registrato premuto [Config.BT_HELP_PRESSES] volte -> Fase 2.
 *
 * Le pressioni del volume NON vengono consumate (il volume cambia come sempre e il gesto non
 * attira attenzione). Le pressioni del pulsante Bluetooth registrato invece sì, così il telecomando
 * non scatta foto e non cambia il volume.
 */
class VolumeKeyService : AccessibilityService() {

    private val presses = ArrayList<Pair<Char, Long>>()  // ('u'/'d', istante)
    private val btPresses = ArrayList<Long>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        val external = dev != null && isExternal(dev)

        // Modalità prova della configurazione: si mostra cosa arriva, senza far partire nulla.
        monitor?.let { m ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                m(KeyInfo(event.keyCode, dev?.descriptor, dev?.name ?: "", external))
            }
            return external
        }

        // Pulsante Bluetooth registrato
        val btDesc = SecurePrefs.getBtDescriptor(this)
        if (btDesc != null && dev != null && dev.descriptor == btDesc) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onBluetoothPress()
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val c = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 'd'
            KeyEvent.KEYCODE_VOLUME_UP -> 'u'
            else -> return false
        }
        val now = System.currentTimeMillis()
        presses.removeAll { now - it.second > Config.VOL_WINDOW_MS }
        if (presses.isNotEmpty() && now - presses.last().second < 150) return false // doppioni ravvicinati
        presses.add(Pair(c, now))

        val tail = presses.map { it.first }.joinToString("")
        val help = SecurePrefs.getVolHelpSeq(this)
        val alert = SecurePrefs.getVolAlertSeq(this)
        // Prima l'Aiuto (ha priorità), poi l'Allerta.
        if (tail.endsWith(help)) {
            presses.clear()
            requestHelp()
        } else if (tail.endsWith(alert)) {
            presses.clear()
            onAlertSequence()
        }
        return false // non consumiamo il tasto: il volume cambia normalmente
    }

    private fun onBluetoothPress() {
        val now = System.currentTimeMillis()
        btPresses.removeAll { now - it > Config.BT_WINDOW_MS }
        btPresses.add(now)
        if (btPresses.size >= Config.BT_HELP_PRESSES) {
            btPresses.clear()
            requestHelp()
        }
    }

    private fun requestHelp() {
        if (!SecurePrefs.isOnboarded(this) || SecurePrefs.isEmergencyActive(this)) return
        ProtectionService.trySend(this, ProtectionService.ACTION_HELP)
    }

    private fun onAlertSequence() {
        if (!SecurePrefs.isOnboarded(this) || SecurePrefs.isEmergencyActive(this)) return
        val now = System.currentTimeMillis()
        val alertOn = SecurePrefs.isArmed(this) && !SecurePrefs.isFakeDisarmed(this) &&
            (SecurePrefs.getAlertUntil(this).let { it == 0L || it > now })
        if (alertOn) {
            // Stesso tasto durante l'allerta: si apre l'app sulla richiesta del PIN per terminarla.
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_END_ALERT, true))
            } catch (_: Exception) {}
        } else {
            ProtectionService.trySend(this, ProtectionService.ACTION_ALERT)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        monitor = null
        super.onDestroy()
    }

    /** Tasto ricevuto, per la prova nella configurazione. */
    class KeyInfo(val keyCode: Int, val descriptor: String?, val deviceName: String, val external: Boolean)

    companion object {
        /** Se impostato (dalla configurazione), i tasti vengono solo mostrati e non fanno partire nulla. */
        @Volatile var monitor: ((KeyInfo) -> Unit)? = null

        /** Il servizio di accessibilità è acceso dall'utente? */
        fun isEnabled(c: Context): Boolean {
            val expected = c.packageName + "/" + VolumeKeyService::class.java.name
            val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) if (splitter.next().equals(expected, ignoreCase = true)) return true
            return false
        }

        /** Dispositivo esterno (Bluetooth/USB) e non i tasti del telefono. */
        fun isExternal(d: InputDevice): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) d.isExternal
            else !d.isVirtual && !d.name.lowercase().let { it.contains("gpio") || it.contains("keypad") || it.contains("qpnp") || it.contains("pmic") }

        fun keyName(code: Int): String = when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume su"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume giù"
            KeyEvent.KEYCODE_ENTER -> "Invio"
            KeyEvent.KEYCODE_CAMERA -> "Fotocamera"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pausa"
            KeyEvent.KEYCODE_HEADSETHOOK -> "Tasto cuffie"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        }
    }
}

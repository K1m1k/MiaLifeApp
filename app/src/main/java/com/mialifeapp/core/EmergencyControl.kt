package com.mialifeapp.core

import android.content.Context

/** Azioni dell'utente sull'emergenza, eseguite subito (lo stato cambia prima di tornare all'interfaccia). */
object EmergencyControl {

    /** Mostrare la schermata nera? Solo in emergenza vera, non quando deve sembrare tutto fermo. */
    fun showDarkScreen(c: Context) =
        SecurePrefs.isEmergencyActive(c) && !SecurePrefs.isCoerced(c) && !SecurePrefs.isFakeDisarmed(c)

    /** PIN vero: emergenza finita, "Sono al sicuro" ai contatti, l'app torna pronta. */
    fun stopWithRealPin(c: Context) {
        if (SecurePrefs.isEmergencyActive(c)) {
            SecurePrefs.setEmergencyActive(c, false)
            SecurePrefs.setEmergencyStart(c, 0)
            SecurePrefs.setCoerced(c, false)
            SmsHelper.sendToAll(c, Messages.safe(c))
        }
        SecurePrefs.setFakeDisarmed(c, false)
        ProtectionService.trySend(c, ProtectionService.ACTION_STOP_EMERGENCY)
    }

    /** PIN di coercizione: sullo schermo sembra tutto fermo, ma l'emergenza parte/continua in segreto. */
    fun coerce(c: Context, showAsDisarmed: Boolean) {
        if (showAsDisarmed) SecurePrefs.setFakeDisarmed(c, true)
        ProtectionService.trySend(c, ProtectionService.ACTION_COERCED)
    }
}

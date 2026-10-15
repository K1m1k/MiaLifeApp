package com.mialifeapp.ui

import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mialifeapp.R
import com.mialifeapp.core.AudioChunkRecorder
import com.mialifeapp.core.Config
import com.mialifeapp.core.Evidence
import com.mialifeapp.core.LocationHelper
import com.mialifeapp.core.Messages
import com.mialifeapp.core.Perms
import com.mialifeapp.core.SecurePrefs
import com.mialifeapp.core.SmsHelper
import com.mialifeapp.core.mail.EmailUploader
import java.util.concurrent.Executors

/**
 * "Prova completa": posizione, registrazione di pochi secondi, invio per email.
 * Gli SMS di prova partono solo se l'utente lo conferma alla fine. Nessuna emergenza viene avviata.
 */
class FullTest(private val a: AppCompatActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progress: AlertDialog
    private var loc: Location? = null
    private var micOk = false
    private var micError: String? = null

    fun run() {
        if (SecurePrefs.isEmergencyActive(a)) { a.toast("Non disponibile durante un'emergenza."); return }
        if (!Perms.hasEssentials(a)) { a.toast("Mancano dei permessi: completa la configurazione."); return }
        progress = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.main_test_full)
            .setMessage("1/3  Cerco la posizione…")
            .setCancelable(false)
            .show()
        LocationHelper.getCurrent(a) { l ->
            loc = l
            Evidence.writeLocation(a, l, "prova_posizione")
            recordStep()
        }
    }

    private fun recordStep() {
        progress.setMessage("2/3  Registro ${Config.TEST_RECORD_MS / 1000} secondi di audio…")
        val rec = AudioChunkRecorder(a, 60_000, "prova_audio")
        rec.start()
        handler.postDelayed({
            micOk = rec.isRecording()
            micError = rec.lastError
            rec.stop()
            uploadStep()
        }, Config.TEST_RECORD_MS)
    }

    private fun uploadStep() {
        progress.setMessage("3/3  Invio per email…")
        val ctx = a.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val r = EmailUploader.uploadPending(ctx)
            handler.post { finish(r) }
        }
    }

    private fun finish(r: EmailUploader.Result) {
        if (a.isFinishing) return
        progress.dismiss()
        val sb = StringBuilder()
        sb.append(if (loc != null) "✓  Posizione trovata" + (if (loc!!.hasAccuracy()) " (~${loc!!.accuracy.toInt()} m)" else "") else "✗  Posizione non trovata: attiva la localizzazione e riprova all'aperto")
        sb.append("\n\n")
        sb.append(if (micOk) "✓  Microfono e registrazione funzionano" else "✗  Registrazione non riuscita" + (micError?.let { " ($it)" } ?: ""))
        sb.append("\n\n")
        sb.append(when (r) {
            EmailUploader.Result.OK, EmailUploader.Result.NOTHING -> "✓  Email con le prove inviata: controlla la tua casella"
            EmailUploader.Result.NEEDS_AUTH -> "✗  Password dell'email rifiutata (Modifica configurazione › Email delle prove)"
            EmailUploader.Result.NOT_CONFIGURED -> "✗  Email delle prove non configurata (Modifica configurazione)"
            EmailUploader.Result.FAILED -> "!  Invio non riuscito ora" + (EmailUploader.lastError?.let { " ($it)" } ?: "") +
                ". I file restano sul telefono e partono appena possibile."
        })
        val allOk = loc != null && micOk && (r == EmailUploader.Result.OK || r == EmailUploader.Result.NOTHING)
        sb.append("\n\n")
        sb.append(if (allOk) "Su questo telefono funziona tutto." else "Sistema i punti segnati e ripeti la prova.")
        MaterialAlertDialogBuilder(a)
            .setTitle(if (allOk) "Prova riuscita" else "Prova da ripetere")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.done_sms_test) { _, _ -> confirmSms() }
            .show()
    }

    private fun confirmSms() {
        val n = SecurePrefs.getContacts(a).size
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.done_sms_test)
            .setMessage("Invio un SMS con scritto [PROVA] ai tuoi $n contatti. Nessuna emergenza.")
            .setPositiveButton(R.string.ok) { _, _ ->
                a.toast("Cerco la posizione e invio gli SMS di prova…")
                Messages.sendTest(a) { withLoc ->
                    a.toast(if (withLoc) "SMS di prova inviati con la posizione." else "SMS di prova inviati, ma posizione non trovata.")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

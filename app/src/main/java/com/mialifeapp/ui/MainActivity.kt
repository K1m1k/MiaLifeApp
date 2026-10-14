package com.mialifeapp.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mialifeapp.BuildConfig
import com.mialifeapp.R
import com.mialifeapp.core.BatteryHelper
import com.mialifeapp.core.EmergencyControl
import com.mialifeapp.core.Perms
import com.mialifeapp.core.Pin
import com.mialifeapp.core.PinResult
import com.mialifeapp.core.ProtectionService
import com.mialifeapp.core.SecurePrefs
import com.mialifeapp.core.Config
import com.mialifeapp.core.SmsHelper
import com.mialifeapp.core.voice.VoiceModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mialifeapp.core.TapListener
import com.mialifeapp.core.TapMatcher
import com.mialifeapp.databinding.ActivityMainBinding

/** Schermata principale: stato, attiva/disattiva, controlli, prove. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var tapListener: TapListener? = null
    private val refreshLater = Runnable { refresh() }
    private val testTimeout = Runnable {
        if (tapListener != null) { toast("Sequenza non riconosciuta. Riprova con un ritmo regolare."); stopListening() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.armButton.setOnClickListener { onMainButton() }
        b.testSeqButton.setOnClickListener { testSequence() }
        b.testFullButton.setOnClickListener { FullTest(this).run() }
        b.appointmentButton.setOnClickListener { onAppointment() }
        b.editButton.setOnClickListener { onEdit() }
        b.howtoButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_howto)
                .setMessage(R.string.main_howto_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!SecurePrefs.isOnboarded(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java)); finish(); return
        }
        if (EmergencyControl.showDarkScreen(this)) {
            startActivity(Intent(this, EmergencyActivity::class.java)); finish(); return
        }
        // App aperta sullo schermo = momento in cui Android concede microfono e posizione:
        // se la protezione è attiva ma ridotta o spenta, la riattiviamo completamente.
        if (SecurePrefs.isArmed(this) && (!ProtectionService.running || ProtectionService.degraded) &&
            Perms.hasEssentials(this)) {
            ProtectionService.trySend(this, ProtectionService.ACTION_ARM)
        }
        refresh()
        handler.postDelayed(refreshLater, 800)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLater)
        stopListening()
    }

    // ------------------------------------------------------------------ stato

    private enum class Shown { OFF, ON, REDUCED }

    private fun shownState(): Shown {
        val armed = SecurePrefs.isArmed(this)
        if (!armed || SecurePrefs.isFakeDisarmed(this)) return Shown.OFF
        if (SecurePrefs.isEmergencyActive(this)) return Shown.ON   // coercizione: deve sembrare normale
        return if (ProtectionService.running && !ProtectionService.degraded) Shown.ON else Shown.REDUCED
    }

    private fun refresh() {
        when (shownState()) {
            Shown.OFF -> {
                b.stateTitle.text = getString(R.string.main_off)
                b.stateSub.text = getString(R.string.main_off_sub)
                b.armButton.text = getString(R.string.main_arm)
            }
            Shown.ON -> {
                b.stateTitle.text = "●  " + getString(R.string.main_armed)
                b.stateSub.text = getString(R.string.main_armed_sub)
                b.armButton.text = getString(R.string.main_disarm)
            }
            Shown.REDUCED -> {
                b.stateTitle.text = getString(R.string.main_degraded)
                b.stateSub.text = getString(R.string.main_degraded_sub)
                b.armButton.text = getString(R.string.main_rearm)
            }
        }
        renderChecks()
        renderAppointment()
    }

    private fun renderAppointment() {
        val ready = SecurePrefs.isVoiceEnabled(this) && VoiceModel.isReady(this) &&
            SecurePrefs.getVoicePhrase(this).isNotEmpty() && SecurePrefs.getVoiceTemplate(this) != null
        b.appointmentButton.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE
        if (!ready) return
        val until = SecurePrefs.getAppointmentUntil(this)
        if (until > System.currentTimeMillis()) {
            val h = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(until))
            b.appointmentButton.text = getString(R.string.appointment_active, h)
        } else {
            b.appointmentButton.text = getString(R.string.main_appointment)
        }
    }

    private fun onAppointment() {
        val until = SecurePrefs.getAppointmentUntil(this)
        if (until > System.currentTimeMillis()) {
            SecurePrefs.setAppointmentUntil(this, 0)
            ProtectionService.trySend(this, ProtectionService.ACTION_APPOINTMENT)
            toast("Modalità appuntamento fermata.")
            refresh()
            return
        }
        if (!SecurePrefs.isArmed(this)) { toast("Attiva prima la protezione."); return }
        val labels = arrayOf(getString(R.string.appointment_1h), getString(R.string.appointment_2h), getString(R.string.appointment_4h))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appointment_pick)
            .setItems(labels) { _, which ->
                val minutes = Config.APPOINTMENT_DURATIONS_MIN[which]
                SecurePrefs.setAppointmentUntil(this, System.currentTimeMillis() + minutes * 60_000L)
                ProtectionService.trySend(this, ProtectionService.ACTION_APPOINTMENT)
                toast("Modalità appuntamento attiva per ${if (minutes < 120) "1 ora" else "${minutes/60} ore"}.")
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderChecks() {
        val box = b.checks
        box.removeAllViews()
        fun row(level: Level, text: String) {
            val tv = TextView(this)
            tv.textSize = 15f
            tv.setPadding(0, dp(7), 0, dp(7))
            tv.status(level, text)
            box.addView(tv)
        }
        val n = SecurePrefs.getContacts(this).size
        row(if (n > 0) Level.OK else Level.BAD, "Contatti di fiducia: $n")
        row(if (Pin.hasPin(this)) Level.OK else Level.BAD, "PIN impostato")
        row(if (Pin.hasCoercion(this)) Level.OK else Level.WARN,
            if (Pin.hasCoercion(this)) "PIN di coercizione impostato" else "PIN di coercizione non impostato (consigliato)")
        row(if (Perms.hasEssentials(this)) Level.OK else Level.BAD, "Permessi: SMS, posizione, microfono, notifiche")
        row(if (Perms.hasBackgroundLocation(this)) Level.OK else Level.WARN, "Posizione \"Consenti sempre\"")
        row(if (BatteryHelper.isIgnoring(this)) Level.OK else Level.BAD, "Escluso dal risparmio energetico")
        val acc = SecurePrefs.getDriveAccount(this)
        row(if (SecurePrefs.isDriveOk(this)) Level.OK else Level.BAD,
            if (SecurePrefs.isDriveOk(this)) "Google Drive collegato" + (acc?.let { " ($it)" } ?: "") else "Google Drive non collegato")
        row(if (Perms.hasScreenLock(this)) Level.OK else Level.WARN, "Blocco schermo del telefono")
        val q = SmsHelper.queueSize(this)
        if (q > 0) row(Level.WARN, "SMS in attesa di linea: $q")
    }

    // ------------------------------------------------------------------ azioni

    private fun onMainButton() {
        when (shownState()) {
            Shown.OFF -> arm()
            Shown.REDUCED -> {
                if (!ProtectionService.trySend(this, ProtectionService.ACTION_ARM)) toast("Impossibile avviare: riprova.")
                handler.postDelayed(refreshLater, 800)
            }
            Shown.ON -> disarm()
        }
    }

    private fun missingForArm(): List<String> {
        val m = mutableListOf<String>()
        if (SecurePrefs.getContacts(this).isEmpty()) m += "contatti di fiducia"
        if (!Pin.hasPin(this)) m += "PIN"
        if (!Perms.hasEssentials(this)) m += "permessi"
        if (!BatteryHelper.isIgnoring(this)) m += "esclusione dal risparmio energetico"
        if (!SecurePrefs.isDriveOk(this) && !BuildConfig.DEBUG) m += "Google Drive"
        return m
    }

    private fun arm() {
        if (SecurePrefs.isFakeDisarmed(this)) {
            // Era "spenta" solo per finta: il servizio è già attivo.
            SecurePrefs.setFakeDisarmed(this, false)
            refresh()
            return
        }
        val missing = missingForArm()
        if (missing.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Configurazione da completare")
                .setMessage("Manca: " + missing.joinToString(", ") + ".\nApri la configurazione per sistemare.")
                .setPositiveButton(R.string.main_edit) { _, _ -> onEdit() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        SecurePrefs.setArmed(this, true)
        if (!ProtectionService.trySend(this, ProtectionService.ACTION_ARM)) toast("Impossibile avviare la protezione: riprova.")
        refresh()
        handler.postDelayed(refreshLater, 800)
    }

    private fun disarm() {
        PinDialog.show(this) { r ->
            when (r) {
                PinResult.REAL -> {
                    EmergencyControl.stopWithRealPin(this)
                    SecurePrefs.setArmed(this, false)
                    ProtectionService.trySend(this, ProtectionService.ACTION_DISARM)
                    refresh()
                    handler.postDelayed(refreshLater, 800)
                }
                PinResult.COERCION -> {
                    EmergencyControl.coerce(this, showAsDisarmed = true)
                    refresh()
                }
                PinResult.WRONG -> toast(getString(R.string.pin_wrong))
                PinResult.LOCKED -> toast(getString(R.string.pin_locked, Pin.lockMinutesLeft(this)))
            }
        }
    }

    private fun onEdit() {
        if (!Pin.hasPin(this)) { openSetup(fake = false); return }
        PinDialog.show(this) { r ->
            when (r) {
                PinResult.REAL -> {
                    if (SecurePrefs.isEmergencyActive(this)) EmergencyControl.stopWithRealPin(this)
                    openSetup(fake = false)
                }
                PinResult.COERCION -> {
                    EmergencyControl.coerce(this, showAsDisarmed = false)
                    openSetup(fake = true)
                }
                PinResult.WRONG -> toast(getString(R.string.pin_wrong))
                PinResult.LOCKED -> toast(getString(R.string.pin_locked, Pin.lockMinutesLeft(this)))
            }
        }
    }

    private fun openSetup(fake: Boolean) {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FAKE, fake))
    }

    // ------------------------------------------------------------------ prova sequenza

    private fun testSequence() {
        stopListening()
        toast("Prova: batti la sequenza entro 10 secondi. Non verrà inviato nulla.")
        ProtectionService.pauseDetection = true
        val matcher = TapMatcher(SecurePrefs.getPattern(this))
        tapListener = TapListener(this, { now ->
            if (matcher.onTap(now)) {
                toast(getString(R.string.toast_test_ok))
                stopListening()
            }
        }).also { it.start() }
        handler.postDelayed(testTimeout, 10_000)
    }

    private fun stopListening() {
        handler.removeCallbacks(testTimeout)
        tapListener?.stop()
        tapListener = null
        ProtectionService.pauseDetection = false
    }

    override fun onDestroy() {
        stopListening()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

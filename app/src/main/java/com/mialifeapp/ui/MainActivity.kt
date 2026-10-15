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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mialifeapp.core.TapListener
import com.mialifeapp.core.TapMatcher
import com.mialifeapp.core.VolumeKeyService
import com.mialifeapp.databinding.ActivityMainBinding

/** Schermata principale: fase attuale (riposo / allerta / aiuto), controlli, prove. */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Aperta dalla sequenza Allerta ripetuta: chiede subito il PIN per terminare l'allerta. */
        const val EXTRA_END_ALERT = "end_alert"
    }

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
        b.editButton.setOnClickListener { onEdit() }
        b.howtoButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_howto)
                .setMessage(R.string.main_howto_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        val until = SecurePrefs.getAlertUntil(this)
        val expired = until in 1..System.currentTimeMillis()
        if (SecurePrefs.isArmed(this) && expired && !SecurePrefs.isEmergencyActive(this)) {
            // Allerta scaduta mentre il servizio era fermo: si torna a riposo.
            SecurePrefs.setArmed(this, false)
            SecurePrefs.setAlertUntil(this, 0)
        } else if (SecurePrefs.isArmed(this) && (!ProtectionService.running || ProtectionService.degraded) &&
            Perms.hasEssentials(this)) {
            ProtectionService.trySend(this, ProtectionService.ACTION_ALERT)
        }
        refresh()
        handler.postDelayed(refreshLater, 800)
        if (intent?.getBooleanExtra(EXTRA_END_ALERT, false) == true) {
            intent.removeExtra(EXTRA_END_ALERT)
            if (shownState() != Shown.OFF) disarm()
        }
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
                b.stateTitle.text = "●  " + getString(R.string.phase_alert)
                val until = SecurePrefs.getAlertUntil(this)
                b.stateSub.text = if (until > 0)
                    getString(R.string.main_armed_until, SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(until)))
                else getString(R.string.main_armed_sub)
                b.armButton.text = getString(R.string.main_disarm)
            }
            Shown.REDUCED -> {
                b.stateTitle.text = getString(R.string.main_degraded)
                b.stateSub.text = getString(R.string.main_degraded_sub)
                b.armButton.text = getString(R.string.main_rearm)
            }
        }
        renderChecks()
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
        val mail = SecurePrefs.getMailAddress(this)
        row(if (SecurePrefs.isMailOk(this)) Level.OK else Level.BAD,
            if (SecurePrefs.isMailOk(this)) "Prove per email a $mail" else "Email delle prove non configurata")
        row(if (Perms.hasScreenLock(this)) Level.OK else Level.WARN, "Blocco schermo del telefono")
        val accOk = VolumeKeyService.isEnabled(this)
        run {
            val tv = TextView(this)
            tv.textSize = 15f; tv.setPadding(0, dp(7), 0, dp(7))
            tv.status(if (accOk) Level.OK else Level.BAD, if (accOk) "Tasti volume attivi (anche a schermo spento)" else "Tasti volume spenti: tocca per attivarli (Accessibilità)")
            if (!accOk) tv.setOnClickListener {
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
            box.addView(tv)
        }
        val bt = SecurePrefs.getBtName(this)
        if (bt != null) row(if (accOk) Level.OK else Level.WARN, "Pulsante Bluetooth: $bt")
        if (SecurePrefs.isVoiceEnabled(this) && SecurePrefs.hasVoiceTemplates(this))
            row(Level.OK, "Frase vocale pronta (ascoltata solo durante l'allerta)")
        val q = SmsHelper.queueSize(this)
        if (q > 0) row(Level.WARN, "SMS in attesa di linea: $q")
    }

    // ------------------------------------------------------------------ azioni

    private fun onMainButton() {
        when (shownState()) {
            Shown.OFF -> arm()
            Shown.REDUCED -> {
                if (!ProtectionService.trySend(this, ProtectionService.ACTION_ALERT)) toast("Impossibile avviare: riprova.")
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
        if (!SecurePrefs.isMailOk(this) && !BuildConfig.DEBUG) m += "email delle prove"
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
        val choices = Config.ALERT_DURATIONS_MIN
        val labels = choices.map { durationLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alert_pick)
            .setItems(labels) { _, which ->
                if (!ProtectionService.trySend(this, ProtectionService.ACTION_ALERT, choices[which])) toast("Impossibile avviare l'allerta: riprova.")
                handler.postDelayed(refreshLater, 800)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun durationLabel(min: Int) = if (min < 60) "$min minuti" else if (min == 60) "1 ora" else "${min / 60} ore"

    private fun disarm() {
        PinDialog.show(this) { r ->
            when (r) {
                PinResult.REAL -> {
                    EmergencyControl.stopWithRealPin(this)
                    SecurePrefs.setArmed(this, false)
                    SecurePrefs.setAlertUntil(this, 0)
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

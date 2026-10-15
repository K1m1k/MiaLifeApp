package com.mialifeapp.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mialifeapp.BuildConfig
import com.mialifeapp.R
import com.mialifeapp.core.BatteryHelper
import com.mialifeapp.core.Config
import com.mialifeapp.core.Evidence
import com.mialifeapp.core.Messages
import com.mialifeapp.core.Perms
import com.mialifeapp.core.Pin
import com.mialifeapp.core.PinResult
import com.mialifeapp.core.ProtectionService
import com.mialifeapp.core.SecurePrefs
import com.mialifeapp.core.SmsHelper
import com.mialifeapp.core.TapListener
import com.mialifeapp.core.TapMatcher
import com.mialifeapp.core.TapRecorder
import com.mialifeapp.core.VolumeKeyService
import com.mialifeapp.core.mail.EmailUploader
import com.mialifeapp.core.mail.MailSettings
import com.mialifeapp.databinding.ActivitySetupBinding
import com.mialifeapp.core.voice.VoiceEngine
import com.mialifeapp.core.voice.VoiceEnroll
import java.util.concurrent.Executors

/**
 * Configurazione guidata, un passo alla volta. Non si va avanti finché il passo non è completo.
 *
 * Modalità "finta" ([EXTRA_FAKE]): aperta con il PIN di coercizione. Si vede tutto uguale ma
 * nulla viene salvato e i contatti veri non vengono mostrati.
 */
class SetupActivity : AppCompatActivity() {

    companion object { const val EXTRA_FAKE = "fake" }

    private lateinit var b: ActivitySetupBinding
    private lateinit var steps: List<View>
    private var step = 0
    private var fake = false
    private var lockWarned = false
    private var mailSkipped = false
    private var hostEdited = false
    private var keysTesting = false
    private val keyPresses = ArrayList<Pair<Char, Long>>()
    private var pickingFor = 0
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var tapListener: TapListener? = null
    private val voiceTemplates = ArrayList<List<FloatArray>>()
    private var voiceRecorded = 0
    private var voiceTester: VoiceEngine? = null

    private val contactFields: List<EditText> by lazy { listOf(b.contact1, b.contact2, b.contact3) }

    // ---- risultati di attività esterne ----
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) contactFields.getOrNull(pickingFor - 1)?.setText(c.getString(0))
            }
        } catch (_: Exception) {
            toast("Non riesco a leggere il contatto: scrivi il numero a mano.")
        }
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updateStatuses()
        if (!Perms.hasEssentials(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permessi mancanti")
                .setMessage("Alcuni permessi non sono stati concessi. Senza di questi l'app non può aiutarti. Apri le impostazioni dell'app e concedili a mano.")
                .setPositiveButton("Apri impostazioni") { _, _ -> BatteryHelper.openAppDetails(this) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private val askBgLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateStatuses() }


    // ---- ciclo di vita ----
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        fake = intent.getBooleanExtra(EXTRA_FAKE, false)

        steps = listOf(b.stepContacts, b.stepPin, b.stepSeq, b.stepPerm, b.stepBattery, b.stepMail, b.stepKeys, b.stepLock, b.stepVoice, b.stepDone)
        b.progress.max = steps.size

        if (!fake) b.coercionCode.setText(SecurePrefs.getCoercionCode(this))
        contactFields.forEachIndexed { i, f ->
            f.hint = getString(R.string.contact_hint, i + 1)
            if (!fake) f.setText(SecurePrefs.getContactRaw(this, i + 1))
        }
        listOf(b.pick1, b.pick2, b.pick3).forEachIndexed { i, btn ->
            btn.setOnClickListener {
                pickingFor = i + 1
                try { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                catch (_: Exception) { toast("Rubrica non disponibile.") }
            }
        }
        if (Pin.hasPin(this)) b.pinKeepHint.text = getString(R.string.pin_keep_hint) else b.pinKeepHint.visibility = View.GONE

        b.seqRecord.setOnClickListener { recordSequence() }
        b.seqDefault.setOnClickListener {
            if (!fake) SecurePrefs.setPattern(this, Config.DEFAULT_PATTERN)
            b.seqResult.status(Level.OK, "Sequenza predefinita impostata")
            updateStatuses()
        }
        b.seqTest.setOnClickListener { testSequence() }
        b.seqSensitivity.setOnClickListener {
            val next = when (SecurePrefs.getTapSensitivity(this)) { 1 -> 2; 2 -> 0; else -> 1 }
            if (!fake) SecurePrefs.setTapSensitivity(this, next)
            showSensitivity()
        }
        showSensitivity()

        b.permGrant.setOnClickListener { askPerms.launch(Perms.toRequest()) }
        b.permBgGrant.setOnClickListener { askBackgroundLocation() }

        b.batteryGrant.setOnClickListener { BatteryHelper.requestIgnore(this) }
        BatteryHelper.oemInstructions()?.let {
            b.oemBox.visibility = View.VISIBLE
            b.oemText.text = it
            b.oemDone.isChecked = SecurePrefs.isOemDone(this)
        }
        b.oemOpen.setOnClickListener { BatteryHelper.openOemSettings(this) }
        b.oemDone.setOnCheckedChangeListener { _, checked -> if (!fake) SecurePrefs.setOemDone(this, checked) }

        setupMail()
        setupKeys()
        b.lockOpen.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) { toast("Apri a mano: Impostazioni › Sicurezza") }
        }
        b.doneSmsTest.setOnClickListener { sendTestSms() }

        b.voiceEnable.setOnClickListener {
            val on = !SecurePrefs.isVoiceEnabled(this)
            if (!fake) SecurePrefs.setVoiceEnabled(this, on)
            updateStatuses()
        }
        b.voiceRecord.setOnClickListener { recordVoiceTake() }
        b.voiceTest.setOnClickListener { toggleVoiceTest() }
        b.voiceThreshold.setOnClickListener {
            val next = when (SecurePrefs.getVoiceThreshold(this)) { in 0..44 -> 55; in 45..64 -> 70; else -> 40 }
            if (!fake) SecurePrefs.setVoiceThreshold(this, next)
            updateStatuses()
        }

        b.backButton.setOnClickListener { if (step == 0) finish() else showStep(step - 1) }
        b.nextButton.setOnClickListener { next() }
        b.skipButton.setOnClickListener { mailSkipped = true; showStep(step + 1) }

        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onDestroy() {
        stopListening()
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    // ---- navigazione ----
    private fun showStep(i: Int) {
        step = i.coerceIn(0, steps.size - 1)
        steps.forEachIndexed { idx, v -> v.visibility = if (idx == step) View.VISIBLE else View.GONE }
        b.stepCounter.text = getString(R.string.setup_step_of, step + 1, steps.size)
        b.progress.setProgressCompat(step + 1, true)
        b.nextButton.text = getString(if (step == steps.size - 1) R.string.setup_complete else R.string.setup_next)
        b.skipButton.visibility = if (BuildConfig.DEBUG && steps[step] == b.stepMail) View.VISIBLE else View.GONE
        if (steps[step] != b.stepKeys) stopKeysTest()
        b.scroll.scrollTo(0, 0)
        updateStatuses()
    }

    private fun next() {
        val err = validate(step)
        if (err != null) {
            MaterialAlertDialogBuilder(this).setMessage(err).setPositiveButton(R.string.ok, null).show()
            return
        }
        if (steps[step] == b.stepLock && !Perms.hasScreenLock(this) && !lockWarned) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Nessun blocco schermo")
                .setMessage("Senza blocco schermo chiunque prenda il telefono può fermare o disinstallare l'app. Ti consigliamo di impostarlo.")
                .setPositiveButton(R.string.lock_open) { _, _ -> b.lockOpen.performClick() }
                .setNegativeButton("Continua comunque") { _, _ -> lockWarned = true; showStep(step + 1) }
                .show()
            return
        }
        if (step == steps.size - 1) complete() else showStep(step + 1)
    }

    /** Ritorna un messaggio d'errore se il passo non è completo; salva i dati del passo se è tutto ok. */
    private fun validate(i: Int): String? = when (steps[i]) {
        b.stepContacts -> validateContacts()
        b.stepPin -> validatePins()
        b.stepPerm -> if (Perms.hasEssentials(this)) null else "Concedi tutti i permessi richiesti per continuare."
        b.stepBattery -> when {
            !BatteryHelper.isIgnoring(this) -> "Tocca \"${getString(R.string.battery_grant)}\" e scegli \"Consenti\"."
            BatteryHelper.oemInstructions() != null && !b.oemDone.isChecked ->
                "Segui le istruzioni del produttore e poi spunta \"${getString(R.string.battery_oem_done)}\"."
            else -> null
        }
        b.stepMail -> if (fake || mailSkipped || SecurePrefs.isMailOk(this)) null
                      else "Scrivi email e password e tocca \"${getString(R.string.mail_test)}\": serve che l'email di prova parta."
        b.stepKeys -> if (fake || VolumeKeyService.isEnabled(this)) null
                      else "Attiva i tasti in Accessibilità (cerca \"${getString(R.string.launcher_name)}\") per continuare."
        else -> null
    }

    private fun normalize(n: String) = n.replace(Regex("[\\s\\-().]"), "")

    private fun validateContacts(): String? {
        val nums = contactFields.map { normalize(it.text.toString()) }
        nums.forEachIndexed { idx, n ->
            if (n.isNotEmpty() && !Regex("^\\+?[0-9]{6,15}$").matches(n)) return "Il numero del contatto ${idx + 1} non sembra valido."
        }
        val list = nums.filter { it.isNotEmpty() }
        if (list.isEmpty()) return getString(R.string.toast_need_contact)
        if (!fake && !SecurePrefs.setContacts(this, list)) return "Salvataggio non riuscito."
        return null
    }

    private fun validatePins() : String? {
        val p1 = b.pin1.text.toString(); val p2 = b.pin2.text.toString()
        val c1 = b.coercion1.text.toString(); val c2 = b.coercion2.text.toString()
        val keepPin = p1.isEmpty() && p2.isEmpty() && Pin.hasPin(this) && !fake
        if (!keepPin) {
            if (!Pin.isValidFormat(p1)) return "Il PIN deve avere da 4 a 8 cifre."
            if (p1 != p2) return "I due PIN non coincidono."
        }
        val setCoercion = c1.isNotEmpty() || c2.isNotEmpty()
        if (setCoercion) {
            if (!Pin.isValidFormat(c1)) return "Il PIN di coercizione deve avere da 4 a 8 cifre."
            if (c1 != c2) return "I due PIN di coercizione non coincidono."
            val sameAsReal = if (keepPin) Pin.peek(this, c1) == PinResult.REAL else c1 == p1
            if (sameAsReal) return "Il PIN di coercizione deve essere diverso dal PIN."
        } else if (!keepPin && !fake && Pin.hasCoercion(this) && Pin.peek(this, p1) == PinResult.COERCION) {
            return "Il nuovo PIN è uguale al PIN di coercizione: scegline un altro."
        }
        if (fake) return null
        if (!keepPin) SecurePrefs.setPinRecord(this, Pin.makeRecord(p1))
        if (setCoercion) SecurePrefs.setCoercionRecord(this, Pin.makeRecord(c1))
        SecurePrefs.setCoercionCode(this, b.coercionCode.text.toString())
        listOf(b.pin1, b.pin2, b.coercion1, b.coercion2).forEach { it.setText("") }
        b.pinKeepHint.visibility = View.VISIBLE
        b.pinKeepHint.text = getString(R.string.pin_keep_hint)
        return null
    }

    private val consentVersion = 1

    private fun complete() {
        if (fake || SecurePrefs.getConsentVersion(this) >= consentVersion) { finishSetup(); return }
        showConsent()
    }

    /** Pagina finale obbligatoria: avvisi e consigli, con spunta. */
    private fun showConsent() {
        val msg = """Prima di usare l'app, leggi e accetta:

• MiaLifeApp PUÒ NON FUNZIONARE: per guasti, limiti del telefono, batteria scarica, mancanza di rete o impostazioni del produttore. Non è infallibile.

• NON sostituisce il 112 né le forze dell'ordine. Se sei in pericolo chiama il 112. Per la violenza contro le donne c'è il 1522, gratuito e attivo 24 ore su 24.

• Prima di un incontro, avvisa una persona di fiducia: dove vai, con chi e a che ora pensi di rientrare.

• Scegli come contatti solo persone affidabili e avvisale che le hai scelte.

• Rifai la prova completa ogni tanto e dopo ogni aggiornamento del telefono.

• Usa le registrazioni nel rispetto della legge e dei diritti delle altre persone.

Toccando "Accetto" dichiari di aver letto e compreso questi avvisi."""
        val box = android.widget.ScrollView(this)
        val inner = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        val tv = android.widget.TextView(this).apply { text = msg; textSize = 15f; setTextColor(0xFF211E3B.toInt()) }
        val cb = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = "Ho letto e accetto gli avvisi e i consigli"; setPadding(0, dp(12), 0, 0) }
        inner.addView(tv); inner.addView(cb); box.addView(inner)
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Avvisi e consigli importanti")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Accetto", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!cb.isChecked) { toast("Spunta la casella per accettare."); return@setOnClickListener }
                SecurePrefs.setConsentVersion(this, consentVersion)
                d.dismiss(); finishSetup()
            }
        }
        d.show()
    }

    private fun finishSetup() {
        if (!fake) {
            SecurePrefs.setOnboarded(this, true)
            if (SecurePrefs.isArmed(this)) ProtectionService.trySend(this, ProtectionService.ACTION_RELOAD)
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ---- stati dei passi ----
    private fun updateStatuses() {
        val pattern = SecurePrefs.getPattern(this).joinToString(" · ") { "$it colpetti" }
        b.seqStatus.status(Level.OK, getString(R.string.seq_current, pattern))

        val missing = mutableListOf<String>()
        if (!Perms.hasSms(this)) missing += "SMS"
        if (!Perms.hasLocation(this)) missing += "posizione"
        if (!Perms.hasMic(this)) missing += "microfono"
        if (!Perms.hasNotifications(this)) missing += "notifiche"
        if (missing.isEmpty()) b.permStatus.status(Level.OK, "Permessi principali concessi")
        else b.permStatus.status(Level.BAD, "Mancano: " + missing.joinToString(", "))
        if (Perms.hasBackgroundLocation(this)) b.permBgStatus.status(Level.OK, "Posizione \"Consenti sempre\" attiva")
        else b.permBgStatus.status(Level.WARN, "Non ancora impostata (consigliata)")

        if (BatteryHelper.isIgnoring(this)) b.batteryStatus.status(Level.OK, "Esclusa dal risparmio energetico")
        else b.batteryStatus.status(Level.BAD, "Ancora soggetta al risparmio energetico")

        if (SecurePrefs.isMailOk(this) && !fake) {
            b.mailStatus.status(Level.OK, "Funziona: le prove arrivano a ${SecurePrefs.getMailAddress(this)}")
        } else if (mailSkipped) {
            b.mailStatus.status(Level.WARN, "Saltato (solo versione di prova): le prove restano sul telefono")
        } else if (b.mailTest.isEnabled) {
            b.mailStatus.status(Level.BAD, "Non ancora provata")
        }
        renderKeys()

        if (Perms.hasScreenLock(this)) b.lockStatus.status(Level.OK, "Blocco schermo attivo")
        else b.lockStatus.status(Level.WARN, "Blocco schermo non impostato")

        renderVoiceStatus()
        b.doneSummary.text = summary()
    }

    private fun summary(): String {
        fun line(ok: Boolean, t: String) = (if (ok) "✓  " else "✗  ") + t
        val n = if (fake) contactFields.count { it.text.isNotBlank() } else SecurePrefs.getContacts(this).size
        return listOf(
            line(n > 0, "Contatti di fiducia: $n"),
            line(fake || Pin.hasPin(this), "PIN"),
            line(fake || Pin.hasCoercion(this), "PIN di coercizione (consigliato)"),
            line(Perms.hasEssentials(this), "Permessi"),
            line(BatteryHelper.isIgnoring(this), "Risparmio energetico"),
            line(fake || SecurePrefs.isMailOk(this), "Email delle prove"),
            line(fake || VolumeKeyService.isEnabled(this), "Tasti volume (Accessibilità)"),
            line(Perms.hasScreenLock(this), "Blocco schermo (consigliato)")
        ).joinToString("\n")
    }

    // ---- permessi ----
    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { updateStatuses(); return }
        if (!Perms.hasLocation(this)) { toast("Prima concedi la posizione con \"${getString(R.string.perm_grant)}\"."); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Da Android 11 si imposta dalla pagina dell'app: Autorizzazioni › Posizione › Consenti sempre
            toast("Scegli Autorizzazioni › Posizione › \"Consenti sempre\".")
        }
        try { askBgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        catch (_: Exception) { BatteryHelper.openAppDetails(this) }
    }

    // ---- sequenza ----
    private fun showSensitivity() {
        val name = when (SecurePrefs.getTapSensitivity(this)) { 0 -> "bassa"; 2 -> "alta"; else -> "media" }
        b.seqSensitivity.text = "Sensibilità colpetti: $name (tocca per cambiare)"
    }
    private fun recordSequence() {
        stopListening()
        b.seqResult.status(Level.WARN, "Batti ora la tua sequenza sul retro… (8 secondi)")
        ProtectionService.pauseDetection = true
        val rec = TapRecorder()
        val startAt = System.currentTimeMillis()
        tapListener = TapListener(this, cb@{ now ->
            if (now - startAt < 400) return@cb // tocco del pulsante sullo schermo
            rec.onTap(now)
            b.seqResult.status(Level.WARN, "Registro… colpetti sentiti: ${rec.totalTaps()}  (gruppi: ${rec.result().joinToString(" · ")})")
        }).also { it.start() }
        handler.postDelayed({
            stopListening()
            val groups = rec.result()
            if (rec.totalTaps() >= 5 && groups.size >= 2 && groups.all { it <= 6 }) {
                if (!fake) SecurePrefs.setPattern(this, groups)
                b.seqResult.status(Level.OK, "Sequenza registrata: " + groups.joinToString(" · ") + ". Ora provala.")
                updateStatuses()
            } else {
                b.seqResult.status(Level.BAD, "Troppo semplice o non rilevata. Servono almeno 5 colpetti in 2 o più gruppi, separati da pause.")
            }
        }, 8_000)
    }

    private fun testSequence() {
        stopListening()
        b.seqResult.status(Level.WARN, "Prova: batti la sequenza… (10 secondi, non invia nulla)")
        ProtectionService.pauseDetection = true
        val matcher = TapMatcher(SecurePrefs.getPattern(this))
        val seen = TapRecorder()
        tapListener = TapListener(this, { now ->
            seen.onTap(now)
            if (matcher.onTap(now)) {
                b.seqResult.status(Level.OK, getString(R.string.toast_test_ok))
                stopListening()
            } else {
                b.seqResult.status(Level.WARN, "Prova… colpetti sentiti: ${seen.totalTaps()}  (gruppi: ${seen.result().joinToString(" · ")})")
            }
        }).also { it.start() }
        handler.postDelayed({
            if (tapListener != null) {
                stopListening()
                b.seqResult.status(Level.BAD, "Non riconosciuta. Riprova con un ritmo regolare e pause chiare.")
            }
        }, 10_000)
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        tapListener?.stop()
        tapListener = null
        voiceTester?.stop()
        voiceTester = null
        stopKeysTest()
        ProtectionService.pauseDetection = false
    }

    // ---- Email delle prove ----
    private fun setupMail() {
        if (!fake) {
            b.mailAddress.setText(SecurePrefs.getMailAddress(this) ?: "")
            SecurePrefs.getMailHost(this)?.let { b.mailHost.setText(it); hostEdited = true }
            b.mailPort.setText(SecurePrefs.getMailPort(this).toString())
            b.mailTrusted.setText(SecurePrefs.getMailTrusted(this).joinToString(", "))
            if (SecurePrefs.getMailPassword(this) != null) b.mailPassword.hint = getString(R.string.mail_password_keep)
        }
        b.mailHost.setOnFocusChangeListener { _, has -> if (has) hostEdited = true }
        b.mailAddress.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { onMailAddressChanged() }
        })
        onMailAddressChanged()
        b.mailHelp.setOnClickListener { showMailHelp() }
        b.mailTest.setOnClickListener { testMail() }
    }

    private fun onMailAddressChanged() {
        val addr = b.mailAddress.text.toString().trim()
        if (!addr.contains('@') || MailSettings.domainOf(addr).length < 3) { b.mailNote.text = ""; return }
        val p = MailSettings.guess(addr)
        b.mailNote.text = p.note
        if (!hostEdited) {
            b.mailHost.setText(p.host)
            b.mailPort.setText(p.port.toString())
        }
    }

    private fun showMailHelp() {
        val msg = """Gmail
1. Apri myaccount.google.com › Sicurezza.
2. Attiva la "Verifica in due passaggi".
3. Cerca "Password per le app", scrivi come nome MiaLifeApp e tocca Crea.
4. Copia le 16 lettere che compaiono e incollale qui come password.

Yahoo: Sicurezza dell'account › Genera password per le app.
iCloud: appleid.apple.com › Password specifiche per le app.
Libero, Virgilio, Aruba, Tiscali: va bene la password normale della casella.
Outlook/Hotmail: non è possibile, usa un'altra casella (per esempio una Gmail creata apposta).

La password resta solo su questo telefono, cifrata."""
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mail_help)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("Apri pagina Google") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
                catch (_: Exception) { toast("Apri a mano: myaccount.google.com") }
            }
            .show()
    }

    private fun testMail() {
        val addr = b.mailAddress.text.toString().trim()
        val pwTyped = b.mailPassword.text.toString()
        val host = b.mailHost.text.toString().trim()
        val port = b.mailPort.text.toString().toIntOrNull() ?: 0
        val trusted = b.mailTrusted.text.toString().split(",", ";", " ").map { it.trim() }.filter { it.isNotEmpty() }
        val err = when {
            !MailSettings.isValidAddress(addr) -> "Scrivi un indirizzo email valido."
            !MailSettings.guess(addr).supported -> MailSettings.guess(addr).note
            pwTyped.isEmpty() && (fake || SecurePrefs.getMailPassword(this) == null) -> "Scrivi la password."
            host.length < 4 -> "Scrivi il server di posta."
            port !in listOf(465, 587, 25) -> "La porta deve essere 465 o 587."
            trusted.size > 2 -> "Al massimo 2 email di fiducia."
            trusted.any { !MailSettings.isValidAddress(it) } -> "Un'email di fiducia non è valida."
            else -> null
        }
        if (err != null) { b.mailStatus.status(Level.BAD, err); return }
        if (fake) { b.mailStatus.status(Level.OK, "Email di prova inviata."); return }
        SecurePrefs.setMailAddress(this, addr)
        if (pwTyped.isNotEmpty()) SecurePrefs.setMailPassword(this, pwTyped)
        SecurePrefs.setMailHost(this, host)
        SecurePrefs.setMailPort(this, port)
        SecurePrefs.setMailTrusted(this, trusted)
        SecurePrefs.setMailOk(this, false)
        b.mailPassword.setText("")
        b.mailPassword.hint = getString(R.string.mail_password_keep)
        b.mailTest.isEnabled = false
        b.mailStatus.status(Level.WARN, getString(R.string.mail_working))
        val ctx = applicationContext
        io.execute {
            Evidence.writeText(ctx, "prova_collegamento_${Evidence.stamp()}.txt",
                "MiaLifeApp: email delle prove configurata. Qui arriveranno registrazioni e posizioni in caso di richiesta di aiuto.\n")
            val r = EmailUploader.uploadPending(ctx)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.mailTest.isEnabled = true
                when (r) {
                    EmailUploader.Result.OK, EmailUploader.Result.NOTHING -> {
                        SecurePrefs.setMailOk(this, true)
                        updateStatuses()
                        toast("Email di prova inviata: controlla la casella (anche Spam).")
                    }
                    EmailUploader.Result.NEEDS_AUTH -> b.mailStatus.status(Level.BAD,
                        "Password rifiutata. Per Gmail/Yahoo/iCloud serve la password per le app. " + (EmailUploader.lastError ?: ""))
                    else -> b.mailStatus.status(Level.BAD, "Invio non riuscito: " + (EmailUploader.lastError ?: "controlla la connessione."))
                }
            }
        }
    }

    // ---- Tasti volume e pulsante Bluetooth ----
    private fun seqLabel(seq: String, choices: Map<String, String>) =
        choices[seq] ?: seq.map { if (it == 'u') "Su" else "Giù" }.joinToString(", ")

    private fun durationLabel(min: Int) = if (min < 60) "$min minuti" else if (min == 60) "1 ora" else "${min / 60} ore"

    private fun <T> nextOf(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1) % list.size]

    private fun setupKeys() {
        b.keysAccOpen.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { toast("Apri a mano: Impostazioni › Accessibilità") }
        }
        b.keysAlertSeq.setOnClickListener {
            val next = nextOf(Config.VOL_ALERT_CHOICES.keys.toList(), SecurePrefs.getVolAlertSeq(this))
            if (!fake) SecurePrefs.setVolAlertSeq(this, next)
            renderKeys()
        }
        b.keysHelpSeq.setOnClickListener {
            val next = nextOf(Config.VOL_HELP_CHOICES.keys.toList(), SecurePrefs.getVolHelpSeq(this))
            if (!fake) SecurePrefs.setVolHelpSeq(this, next)
            renderKeys()
        }
        b.keysDuration.setOnClickListener {
            val next = nextOf(Config.ALERT_DURATIONS_MIN, SecurePrefs.getAlertMinutes(this))
            if (!fake) SecurePrefs.setAlertMinutes(this, next)
            renderKeys()
        }
        b.keysTest.setOnClickListener { if (keysTesting) stopKeysTest() else startKeysTest() }
        b.btLearn.setOnClickListener { learnBluetooth() }
        b.btRemove.setOnClickListener {
            if (!fake) SecurePrefs.setBtButton(this, null, null, 0)
            renderKeys()
        }
    }

    private fun renderKeys() {
        val on = VolumeKeyService.isEnabled(this)
        if (on) b.keysAccStatus.status(Level.OK, "Tasti attivi")
        else b.keysAccStatus.status(Level.BAD, "Non ancora attivati")
        b.keysAlertSeq.text = getString(R.string.keys_alert_seq, seqLabel(SecurePrefs.getVolAlertSeq(this), Config.VOL_ALERT_CHOICES))
        b.keysHelpSeq.text = getString(R.string.keys_help_seq, seqLabel(SecurePrefs.getVolHelpSeq(this), Config.VOL_HELP_CHOICES))
        b.keysDuration.text = getString(R.string.keys_duration, durationLabel(SecurePrefs.getAlertMinutes(this)))
        val bt = if (fake) null else SecurePrefs.getBtName(this)
        if (bt != null) b.btStatus.status(Level.OK, "Registrato: $bt (${VolumeKeyService.keyName(SecurePrefs.getBtKey(this))})")
        else b.btStatus.status(Level.WARN, "Nessun pulsante registrato (facoltativo)")
        b.btRemove.visibility = if (bt != null) View.VISIBLE else View.GONE
        b.keysTest.text = getString(if (keysTesting) R.string.keys_test_stop else R.string.keys_test)
    }

    private fun startKeysTest() {
        if (!VolumeKeyService.isEnabled(this)) { b.keysResult.status(Level.BAD, "Prima attiva i tasti in Accessibilità."); return }
        stopKeysTest()
        keysTesting = true
        keyPresses.clear()
        val btDesc = SecurePrefs.getBtDescriptor(this)
        var btCount = 0
        var btLast = 0L
        b.keysResult.status(Level.WARN, "Fai la sequenza Allerta o Aiuto, oppure premi 3 volte il pulsante Bluetooth. Non parte nulla.")
        VolumeKeyService.monitor = { k ->
            runOnUiThread {
                if (!keysTesting) return@runOnUiThread
                val now = System.currentTimeMillis()
                if (btDesc != null && k.descriptor == btDesc) {
                    btCount = if (now - btLast > Config.BT_WINDOW_MS) 1 else btCount + 1
                    btLast = now
                    if (btCount >= Config.BT_HELP_PRESSES) { btCount = 0; b.keysResult.status(Level.OK, "Pulsante Bluetooth: AIUTO riconosciuto ✓ (prova)") }
                    else b.keysResult.status(Level.WARN, "Pulsante Bluetooth premuto ($btCount di ${Config.BT_HELP_PRESSES})")
                    return@runOnUiThread
                }
                val c = when (k.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> 'd'
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> 'u'
                    else -> { b.keysResult.status(Level.WARN, "Tasto ricevuto: ${VolumeKeyService.keyName(k.keyCode)} da ${k.deviceName}"); return@runOnUiThread }
                }
                keyPresses.removeAll { now - it.second > Config.VOL_WINDOW_MS }
                keyPresses.add(Pair(c, now))
                val tail = keyPresses.map { it.first }.joinToString("")
                when {
                    tail.endsWith(SecurePrefs.getVolHelpSeq(this)) -> { keyPresses.clear(); b.keysResult.status(Level.OK, "AIUTO riconosciuto ✓ (prova: non è partito nulla)") }
                    tail.endsWith(SecurePrefs.getVolAlertSeq(this)) -> { keyPresses.clear(); b.keysResult.status(Level.OK, "ALLERTA riconosciuta ✓ (prova: non è partito nulla)") }
                    else -> b.keysResult.status(Level.WARN, "Tasti: " + tail.map { if (it == 'u') "Su" else "Giù" }.joinToString(" · "))
                }
            }
        }
        renderKeys()
        handler.postDelayed({ if (keysTesting) stopKeysTest() }, 60_000)
    }

    private fun stopKeysTest() {
        if (!keysTesting && VolumeKeyService.monitor == null) return
        keysTesting = false
        VolumeKeyService.monitor = null
        if (::b.isInitialized) renderKeys()
    }

    private fun learnBluetooth() {
        if (fake) { b.btStatus.status(Level.OK, "Registrato"); return }
        if (!VolumeKeyService.isEnabled(this)) { b.btStatus.status(Level.BAD, "Prima attiva i tasti in Accessibilità."); return }
        stopKeysTest()
        keysTesting = true
        b.btStatus.status(Level.WARN, getString(R.string.bt_learning))
        VolumeKeyService.monitor = { k ->
            runOnUiThread {
                if (!keysTesting) return@runOnUiThread
                if (!k.external || k.descriptor == null) {
                    b.btStatus.status(Level.WARN, "Questo è un tasto del telefono: premi il pulsante del telecomando Bluetooth.")
                } else {
                    SecurePrefs.setBtButton(this, k.descriptor, k.deviceName.ifBlank { "telecomando" }, k.keyCode)
                    stopKeysTest()
                    toast("Pulsante registrato. Ora provalo con \"${getString(R.string.keys_test)}\".")
                }
            }
        }
        renderKeys()
        handler.postDelayed({
            if (keysTesting && SecurePrefs.getBtDescriptor(this) == null) {
                stopKeysTest()
                b.btStatus.status(Level.BAD, "Nessun pulsante ricevuto. Il telecomando è collegato nelle impostazioni Bluetooth?")
            } else if (keysTesting) stopKeysTest()
        }, 15_000)
    }

    // ---- Attivazione vocale ----
    private fun renderVoiceStatus() {
        val on = SecurePrefs.isVoiceEnabled(this)
        val enrolled = SecurePrefs.hasVoiceTemplates(this)
        b.voiceEnable.text = getString(if (on) R.string.voice_disable else R.string.voice_enable)
        b.voiceDownload.visibility = View.GONE
        b.voiceRecord.text = getString(R.string.voice_record, (voiceRecorded + 1).coerceAtMost(Config.VOICE_ENROLL_COUNT), Config.VOICE_ENROLL_COUNT)
        b.voiceThreshold.text = getString(R.string.voice_threshold, SecurePrefs.getVoiceThreshold(this))
        listOf(b.voicePhrase, b.voiceRecord, b.voiceThreshold).forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
        b.voiceTest.isEnabled = on && enrolled
        b.voiceTest.alpha = if (b.voiceTest.isEnabled) 1f else 0.4f
        when {
            !on -> b.voiceStatus.status(Level.WARN, "Attivazione vocale spenta (facoltativa)")
            !enrolled -> b.voiceStatus.status(Level.WARN, "Registra la tua frase ${Config.VOICE_ENROLL_COUNT} volte")
            else -> b.voiceStatus.status(Level.OK, "Pronta: frase registrata")
        }
    }

    private fun recordVoiceTake() {
        if (fake) { toast("Registrato."); return }
        if (!Perms.hasMic(this)) { toast("Serve il permesso microfono (passo Permessi)."); return }
        if (voiceRecorded == 0) voiceTemplates.clear()
        b.voiceResult.status(Level.WARN, getString(R.string.voice_recording))
        b.voiceRecord.isEnabled = false
        io.execute {
            val samples = VoiceEnroll.record(Config.VOICE_ENROLL_MAX_MS)
            val seq = samples?.let { VoiceEnroll.sequenceOf(it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                b.voiceRecord.isEnabled = true
                if (seq == null) { b.voiceResult.status(Level.BAD, "Non ho sentito la frase: riprova a voce chiara."); return@runOnUiThread }
                voiceTemplates.add(seq)
                voiceRecorded++
                if (voiceRecorded >= Config.VOICE_ENROLL_COUNT) {
                    SecurePrefs.setVoiceTemplates(this, ArrayList(voiceTemplates))
                    voiceRecorded = 0
                    b.voiceResult.status(Level.OK, getString(R.string.voice_enrolled))
                } else {
                    b.voiceResult.status(Level.WARN, "Registrazione ${voiceRecorded} di ${Config.VOICE_ENROLL_COUNT} fatta. Ripeti la frase.")
                }
                renderVoiceStatus()
            }
        }
    }

    private fun toggleVoiceTest() {
        if (voiceTester != null) { voiceTester?.stop(); voiceTester = null; b.voiceTest.text = getString(R.string.voice_test); return }
        if (!SecurePrefs.hasVoiceTemplates(this)) { toast("Registra prima la frase ${Config.VOICE_ENROLL_COUNT} volte."); return }
        if (!Perms.hasMic(this)) { toast("Serve il permesso microfono."); return }
        b.voiceTest.text = "Sto ascoltando… tocca per fermare"
        val engine = VoiceEngine(
            ctx = this,
            testMode = true,
            onScore = { score ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val ok = score >= SecurePrefs.getVoiceThreshold(this)
                    b.voiceResult.status(if (ok) Level.OK else Level.WARN,
                        "somiglianza alla tua frase: $score%  (soglia ${SecurePrefs.getVoiceThreshold(this)}%) " + if (ok) "→ SCATTEREBBE" else "→ non scatta")
                }
            },
            onTrigger = {}
        )
        if (engine.start()) {
            voiceTester = engine
        } else {
            b.voiceTest.text = getString(R.string.voice_test)
            toast("Riconoscitore non avviato: scarica prima il modello.")
        }
    }

    // ---- SMS di prova ----
    private fun sendTestSms() {
        if (fake) { toast("SMS di prova inviati."); return }
        val n = SecurePrefs.getContacts(this).size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.done_sms_test)
            .setMessage("Invio un SMS con scritto [PROVA] ai tuoi $n contatti. Nessuna emergenza.")
            .setPositiveButton(R.string.ok) { _, _ ->
                toast("Cerco la posizione e invio gli SMS di prova…")
                Messages.sendTest(this) { withLoc ->
                    toast(if (withLoc) "SMS di prova inviati con la posizione." else "SMS di prova inviati, ma posizione non trovata.")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

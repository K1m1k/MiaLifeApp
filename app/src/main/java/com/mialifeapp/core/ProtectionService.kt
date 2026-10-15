package com.mialifeapp.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mialifeapp.core.drive.DriveUploadWorker
import com.mialifeapp.core.voice.VoiceEngine
import com.mialifeapp.core.drive.DriveUploader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * UNICO servizio di protezione.
 *
 * Si avvia quando l'utente tocca "Attiva protezione" (app aperta sullo schermo): in quel momento
 * Android concede microfono e posizione e li mantiene finché il servizio resta vivo.
 *
 * Stati:
 *  - PRONTO: ascolta solo i colpetti (accelerometro). Non registra, nessun pallino verde.
 *  - EMERGENZA: SMS + posizione, registrazione a pezzi, caricamento su Drive ogni X minuti,
 *    posizione + SMS ogni Y minuti. Tutto dentro questo servizio, senza avviarne altri.
 *
 * Modalità RIDOTTA: se il servizio viene fatto ripartire senza l'app aperta (riavvio del telefono,
 * chiusura forzata dal sistema) Android non concede il microfono: l'ascolto dei colpetti, gli SMS
 * e la posizione funzionano lo stesso; l'utente viene avvisato di riaprire l'app.
 */
class ProtectionService : Service() {

    companion object {
        const val ACTION_ARM = "com.mialifeapp.ARM"
        const val ACTION_RESTORE = "com.mialifeapp.RESTORE"
        const val ACTION_DISARM = "com.mialifeapp.DISARM"
        const val ACTION_STOP_EMERGENCY = "com.mialifeapp.STOP_EMERGENCY"
        const val ACTION_COERCED = "com.mialifeapp.COERCED"
        const val ACTION_RELOAD = "com.mialifeapp.RELOAD"
        const val ACTION_APPOINTMENT = "com.mialifeapp.APPOINTMENT"
        const val ACTION_ALERT = "com.mialifeapp.ALERT"   // Fase 1: allerta
        const val ACTION_HELP = "com.mialifeapp.HELP"     // Fase 2: aiuto

        @Volatile var running = false
            private set
        @Volatile var degraded = false
            private set

        /** Messo a true dall'app durante le prove della sequenza, per non far partire un'emergenza vera. */
        @Volatile var pauseDetection = false

        fun send(c: Context, action: String) {
            ContextCompat.startForegroundService(c, Intent(c, ProtectionService::class.java).setAction(action))
        }

        fun trySend(c: Context, action: String): Boolean = try {
            send(c, action); true
        } catch (e: Exception) {
            false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var listener: TapListener? = null
    private var matcher: TapMatcher? = null
    private var recorder: AudioChunkRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var emergencyRunning = false
    private var voice: VoiceEngine? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: riavvio automatico da parte di Android (START_STICKY) -> ripristino.
        val action = intent?.action ?: ACTION_RESTORE
        val wantFull = action != ACTION_RESTORE || (running && !degraded)
        if (!startFg(wantFull)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        when (action) {
            ACTION_DISARM -> if (!SecurePrefs.isEmergencyActive(this)) {
                SecurePrefs.setArmed(this, false)
                SecurePrefs.setFakeDisarmed(this, false)
            }
            ACTION_ALERT -> {
                SecurePrefs.setArmed(this, true)
                SecurePrefs.setFakeDisarmed(this, false)
            }
            ACTION_HELP -> if (!SecurePrefs.isEmergencyActive(this)) startEmergency(coerced = false)
            ACTION_STOP_EMERGENCY -> stopEmergency()
            ACTION_COERCED -> coerced()
            ACTION_RELOAD -> { listener?.stop(); listener = null } // riparte sotto con sequenza e sensibilità nuove
        }

        val armed = SecurePrefs.isArmed(this)
        val emergency = SecurePrefs.isEmergencyActive(this)
        if (!armed && !emergency) {
            shutdown()
            return START_NOT_STICKY
        }

        ensureListening()
        if (emergency && !emergencyRunning) resumeEmergency()
        manageVoice()
        if (!degraded) Notifications.cancel(this, Notifications.ID_RESTORE)
        return START_STICKY
    }

    // ------------------------------------------------------------------ foreground

    private fun fullType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else -> 0
    }

    private fun degradedType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun startFg(full: Boolean): Boolean {
        val n = Notifications.service(this)
        if (full) {
            try {
                ServiceCompat.startForeground(this, Notifications.ID_SERVICE, n, fullType())
                degraded = false
                return true
            } catch (e: Exception) {
                // Avvio non consentito con microfono/posizione: si passa alla modalità ridotta.
            }
        }
        return try {
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, n, degradedType())
            degraded = true
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ ascolto colpetti

    private fun ensureListening() {
        if (listener != null) return
        acquireWakeLock()
        matcher = TapMatcher(SecurePrefs.getPattern(this))
        listener = TapListener(this, { now -> onTap(now) }).also { it.start() }
    }

    private fun onTap(now: Long) {
        if (pauseDetection) { matcher?.reset(); return }
        if (matcher?.onTap(now) == true && !SecurePrefs.isEmergencyActive(this)) {
            startEmergency(coerced = false)
        }
    }

    /** Senza questo il processore si addormenta a schermo spento e i colpetti non arrivano. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiaLifeApp:protection").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    // ------------------------------------------------------------------ emergenza

    private fun startEmergency(coerced: Boolean) {
        SecurePrefs.setEmergencyActive(this, true)
        SecurePrefs.setEmergencyStart(this, System.currentTimeMillis())
        SecurePrefs.setCoerced(this, coerced)
        Notifications.cancel(this, Notifications.ID_RESTORE)
        Notifications.cancel(this, Notifications.ID_DRIVE)
        // 1) prima di tutto la richiesta di aiuto: non dipende dal microfono
        if (coerced && !Messages.hasCode(this)) SmsHelper.sendToAll(this, Messages.coerced(this))
        sendLocation(Messages.Kind.HELP)
        // 2) poi registrazione e cicli periodici
        beginLoops()
    }

    /** Ripresa dopo un riavvio del servizio/telefono con l'emergenza ancora in corso. */
    private fun resumeEmergency() {
        SmsHelper.sendToAll(this, Messages.resumed(this))
        sendLocation(Messages.Kind.UPDATE)
        beginLoops()
    }

    private fun beginLoops() {
        emergencyRunning = true
        voice?.stop(); voice = null
        io.execute { Evidence.recoverParts(this) }
        // In modalità ridotta si prova comunque a registrare: su alcuni telefoni funziona.
        recorder = AudioChunkRecorder(this, Config.CHUNK_MS, "audio").also { it.start() }
        handler.removeCallbacks(locationLoop)
        handler.removeCallbacks(uploadLoop)
        handler.postDelayed(locationLoop, Config.LOCATION_EVERY_MS)
        handler.postDelayed(uploadLoop, Config.UPLOAD_EVERY_MS)
    }

    private val locationLoop = object : Runnable {
        override fun run() {
            if (!emergencyRunning || !SecurePrefs.isEmergencyActive(this@ProtectionService)) return
            SmsHelper.flushQueue(this@ProtectionService)
            sendLocation(Messages.Kind.UPDATE)
            handler.postDelayed(this, Config.LOCATION_EVERY_MS)
        }
    }

    private val uploadLoop = object : Runnable {
        override fun run() {
            if (!emergencyRunning) return
            uploadNow()
            handler.postDelayed(this, Config.UPLOAD_EVERY_MS)
        }
    }

    private fun uploadNow() {
        val ctx = applicationContext
        io.execute {
            val r = DriveUploader.uploadPending(ctx)
            // Senza rete: il lavoro di riserva parte da solo appena torna la linea.
            if (r == DriveUploader.Result.FAILED || r == DriveUploader.Result.NEEDS_AUTH) DriveUploadWorker.enqueue(ctx)
        }
    }

    private fun sendLocation(kind: Messages.Kind) {
        val ctx = applicationContext
        var sent = false
        // Se il GPS non risponde entro 20 secondi, l'SMS parte comunque (senza posizione).
        val fallback = Runnable {
            if (!sent) { sent = true; SmsHelper.sendToAll(ctx, Messages.location(ctx, kind, null)) }
        }
        handler.postDelayed(fallback, 20_000)
        LocationHelper.getCurrent(ctx) { loc ->
            if (!sent) {
                sent = true
                handler.removeCallbacks(fallback)
                SmsHelper.sendToAll(ctx, Messages.location(ctx, kind, loc))
            } else if (loc != null) {
                // Posizione arrivata in ritardo: la mandiamo subito come aggiornamento.
                SmsHelper.sendToAll(ctx, Messages.location(ctx, Messages.Kind.UPDATE, loc))
            }
            io.execute { Evidence.writeLocation(ctx, loc) }
        }
    }

    private fun coerced() {
        if (!SecurePrefs.isEmergencyActive(this)) {
            startEmergency(coerced = true)
        } else if (!SecurePrefs.isCoerced(this)) {
            SecurePrefs.setCoerced(this, true)
            if (!Messages.hasCode(this)) SmsHelper.sendToAll(this, Messages.coerced(this))
            sendLocation(Messages.Kind.UPDATE)
        }
    }

    /**
     * Ferma registrazione e cicli. L'SMS "Sono al sicuro" e lo stato li gestisce già
     * [EmergencyControl.stopWithRealPin] prima di arrivare qui.
     */
    private fun stopEmergency() {
        val wasRunning = emergencyRunning
        emergencyRunning = false
        handler.removeCallbacks(locationLoop)
        handler.removeCallbacks(uploadLoop)
        recorder?.stop()
        recorder = null
        SecurePrefs.setEmergencyActive(this, false)
        SecurePrefs.setCoerced(this, false)
        matcher?.reset()
        if (wasRunning) {
            uploadNow()
            DriveUploadWorker.enqueue(this, chain = true)
        }
    }

    // ------------------------------------------------------------------ ascolto vocale (Modalità appuntamento)

    private val appointmentEnd = Runnable { manageVoice() }

    /** Avvia o ferma l'ascolto vocale secondo lo stato. L'emergenza vocale non parte mai durante una vera emergenza. */
    private fun manageVoice() {
        handler.removeCallbacks(appointmentEnd)
        val now = System.currentTimeMillis()
        val until = SecurePrefs.getAppointmentUntil(this)
        val active = SecurePrefs.isArmed(this) &&
            !SecurePrefs.isEmergencyActive(this) &&
            !degraded &&
            SecurePrefs.isVoiceEnabled(this) &&
            SecurePrefs.hasVoiceTemplates(this) &&
            until > now
        if (active) {
            if (voice == null) {
                val engine = VoiceEngine(
                    ctx = this,
                    testMode = false,
                    onScore = { _ -> },
                    onTrigger = { handler.post { if (!SecurePrefs.isEmergencyActive(this)) startEmergency(coerced = false) } }
                )
                if (engine.start()) voice = engine
            }
            handler.postDelayed(appointmentEnd, (until - now).coerceAtMost(60 * 60_000L))
        } else {
            voice?.stop()
            voice = null
            if (until in 1..now) SecurePrefs.setAppointmentUntil(this, 0)
        }
    }

    // ------------------------------------------------------------------ chiusura

    private fun shutdown() {
        listener?.stop()
        listener = null
        recorder?.stop()
        recorder = null
        emergencyRunning = false
        voice?.stop(); voice = null
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        running = false
        degraded = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener?.stop()
        listener = null
        recorder?.stop()
        recorder = null
        emergencyRunning = false
        voice?.stop(); voice = null
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        io.shutdown()
        running = false
        degraded = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

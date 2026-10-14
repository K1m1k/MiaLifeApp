package com.mialifeapp.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mialifeapp.core.Config
import com.mialifeapp.core.SecurePrefs
import kotlin.math.sqrt

/**
 * Ascolto vocale SENZA vocabolario e senza download: confronta quello che viene detto con la
 * FRASE registrata dall'utente (sequenza MFCC). Il confronto tiene conto insieme di cosa è detto
 * e del timbro della voce, quindi un'altra persona o la stessa frase detta da altri non corrisponde.
 *
 * Il microfono resta acceso, ma il confronto parte solo quando c'è una voce (filtro di energia),
 * così nel silenzio il consumo è minimo.
 */
class VoiceEngine(
    private val ctx: Context,
    private val testMode: Boolean,
    private val onScore: (score: Int) -> Unit,
    private val onTrigger: () -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    private val sr = Config.VOICE_SAMPLE_RATE
    private val templates = SecurePrefs.getVoiceTemplates(ctx)
    private val threshold = SecurePrefs.getVoiceThreshold(ctx)

    private var lastTriggerAt = 0L

    /** Avvia l'ascolto. Ritorna false se non c'è una frase registrata. */
    fun start(): Boolean {
        if (running) return true
        if (templates.isNullOrEmpty()) return false
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(800) } catch (_: Exception) {}
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        } catch (e: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        record = rec

        val buf = ShortArray(sr / 10)                 // 100 ms
        val segment = ArrayList<Float>(sr * 4)
        var inSpeech = false
        var silenceMs = 0
        val maxSegMs = 4000
        var segMs = 0

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val f = buf[i] / 32768f; sum += (f * f).toDouble() }
                val rms = sqrt(sum / n)
                val voiced = rms > 0.02

                if (voiced) {
                    inSpeech = true
                    silenceMs = 0
                    for (i in 0 until n) segment.add(buf[i] / 32768f)
                    segMs += 100
                    if (segMs >= maxSegMs) { evaluate(segment); segment.clear(); inSpeech = false; segMs = 0 }
                } else if (inSpeech) {
                    for (i in 0 until n) segment.add(buf[i] / 32768f)  // coda di silenzio
                    silenceMs += 100
                    segMs += 100
                    if (silenceMs >= 400) {
                        evaluate(segment); segment.clear(); inSpeech = false; silenceMs = 0; segMs = 0
                    }
                }
            }
        } catch (e: Exception) {
            // esce silenziosamente: il servizio resta vivo con i colpetti
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
    }

    private fun evaluate(segment: ArrayList<Float>) {
        val tpls = templates ?: return
        if (segment.size < sr / 2) return               // meno di mezzo secondo: ignora
        val seq = Mfcc.frames(segment.toFloatArray())
        if (seq.size < 5) return
        val score = tpls.maxOf { Mfcc.sequenceSimilarity(seq, it) }
        if (testMode) { onScore(score); return }
        val now = System.currentTimeMillis()
        if (score >= threshold && now - lastTriggerAt > 8_000) {
            lastTriggerAt = now
            onTrigger()
        }
    }
}

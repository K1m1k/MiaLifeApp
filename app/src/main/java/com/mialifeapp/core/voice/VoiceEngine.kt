package com.mialifeapp.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mialifeapp.core.Config
import com.mialifeapp.core.SecurePrefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Ascolto vocale: riconosce la FRASE registrata (Vosk, vocabolario limitato) e verifica che la
 * VOCE somigli a quella dell'utente (MFCC). L'allarme scatta solo se frase e voce corrispondono.
 *
 * Due usi:
 *  - prova in configurazione: [onScore] riporta i due valori senza far scattare nulla.
 *  - protezione: [onTrigger] viene chiamato quando frase e voce corrispondono davvero.
 *
 * Il microfono resta acceso, ma il riconoscimento gira solo quando c'è voce (filtro di energia),
 * così il consumo nel silenzio è minimo.
 */
class VoiceEngine(
    private val ctx: Context,
    private val testMode: Boolean,
    private val onScore: (phraseOk: Boolean, speaker: Int, heard: String) -> Unit,
    private val onTrigger: () -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    private val sampleRate = Config.VOICE_SAMPLE_RATE
    private val phrase = SecurePrefs.getVoicePhrase(ctx)
    private val template = SecurePrefs.getVoiceTemplate(ctx)
    private val threshold = SecurePrefs.getVoiceThreshold(ctx)

    /** Finestra scorrevole degli ultimi ~2,5 s di audio, per controllare frase e voce insieme. */
    private val windowSamples = (sampleRate * 2.5f).toInt()
    private val ring = FloatArray(windowSamples)
    private var ringPos = 0
    private var lastTriggerAt = 0L

    /** Avvia l'ascolto. Ritorna false se il modello vocale non è presente sul telefono. */
    fun start(): Boolean {
        if (running) return true
        if (!VoiceModel.isReady(ctx)) return false
        running = true
        thread = Thread {
            // Il modello si carica qui (1-2 secondi), non sul thread dello schermo.
            val model = try { Model(VoiceModel.modelPath(ctx).absolutePath) } catch (e: Exception) { null }
            if (model == null) { running = false } else loop(model)
        }.also { it.isDaemon = true; it.start() }
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
    private fun loop(model: Model) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sampleRate / 4) * 2
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) { model.close(); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); model.close(); return }
        record = rec

        val recognizer = try {
            if (phrase.isNotEmpty()) Recognizer(model, sampleRate.toFloat(), grammar()) else Recognizer(model, sampleRate.toFloat())
        } catch (e: Exception) { rec.release(); model.close(); return }

        val shortBuf = ShortArray(bufSize / 2)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                // RMS: filtro di energia, salta il riconoscimento nel silenzio
                var sum = 0.0
                for (i in 0 until n) {
                    val f = shortBuf[i] / 32768f
                    sum += (f * f).toDouble()
                    ring[ringPos] = f
                    ringPos = (ringPos + 1) % windowSamples
                }
                val rms = sqrt(sum / n)
                if (rms < 0.012) continue // quasi silenzio: non consuma il riconoscitore

                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (shortBuf[i].toInt() and 0xff).toByte()
                    bytes[i * 2 + 1] = ((shortBuf[i].toInt() shr 8) and 0xff).toByte()
                }
                val finished = recognizer.acceptWaveForm(bytes, n * 2)
                val text = textOf(if (finished) recognizer.result else recognizer.partialResult, finished)
                if (text.isNotBlank()) evaluate(text)
            }
        } catch (e: Exception) {
            // esce silenziosamente: il servizio resta vivo con i colpetti
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
            try { recognizer.close() } catch (_: Exception) {}
            model.close()
        }
    }

    private fun evaluate(heard: String) {
        val phraseOk = phraseMatches(heard)
        if (!phraseOk && !testMode) return
        val speaker = speakerScore()
        if (testMode) {
            onScore(phraseOk, speaker, heard)
            return
        }
        val now = System.currentTimeMillis()
        if (phraseOk && speaker >= threshold && now - lastTriggerAt > 8_000) {
            lastTriggerAt = now
            onTrigger()
        }
    }

    /** Frase riconosciuta se almeno (N-1) parole su N compaiono nell'ordine giusto. */
    private fun phraseMatches(heard: String): Boolean {
        if (phrase.isEmpty()) return false
        val words = heard.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var idx = 0
        var matched = 0
        for (target in phrase) {
            var found = false
            while (idx < words.size) {
                if (words[idx] == target) { found = true; idx++; break }
                idx++
            }
            if (found) matched++
        }
        val need = if (phrase.size >= 3) phrase.size - 1 else phrase.size
        return matched >= need
    }

    /** Somiglianza della voce sulla finestra audio recente; 100 se non c'è impronta (solo frase). */
    private fun speakerScore(): Int {
        val tpl = template ?: return 100
        val ordered = FloatArray(windowSamples)
        for (i in 0 until windowSamples) ordered[i] = ring[(ringPos + i) % windowSamples]
        val mean = Mfcc.meanVector(Mfcc.frames(ordered)) ?: return 0
        return Mfcc.similarity(mean, tpl)
    }

    private fun textOf(json: String?, finished: Boolean): String = try {
        val o = JSONObject(json ?: "{}")
        if (finished) o.optString("text") else o.optString("partial")
    } catch (e: Exception) { "" }

    /** Grammatica Vosk: solo le parole della frase (più [unk] per lo sconosciuto). */
    private fun grammar(): String {
        val uniq = phrase.distinct().joinToString(" ")
        return "[\"$uniq\", \"[unk]\"]"
    }
}

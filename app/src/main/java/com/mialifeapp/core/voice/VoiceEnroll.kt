package com.mialifeapp.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mialifeapp.core.Config

/** Registra una breve frase dal microfono e ne ricava l'impronta MFCC (per la verifica della voce). */
object VoiceEnroll {

    /** Registra fino a [maxMs] e ritorna i campioni audio (-1..1), o null in caso di errore. */
    @SuppressLint("MissingPermission")
    fun record(maxMs: Long): FloatArray? {
        val sr = Config.VOICE_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        } catch (e: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }

        val total = (sr * maxMs / 1000).toInt()
        val out = FloatArray(total)
        var written = 0
        val buf = ShortArray(sr / 10)
        return try {
            rec.startRecording()
            val endAt = System.currentTimeMillis() + maxMs
            while (written < total && System.currentTimeMillis() < endAt) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var i = 0
                while (i < n && written < total) { out[written++] = buf[i] / 32768f; i++ }
            }
            if (written < sr / 2) null else out.copyOf(written)
        } catch (e: Exception) {
            null
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** Impronta MFCC media di una registrazione, o null se è silenzio. */
    fun templateOf(samples: FloatArray): FloatArray? = Mfcc.meanVector(Mfcc.frames(samples))
}

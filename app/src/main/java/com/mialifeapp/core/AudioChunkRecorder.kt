package com.mialifeapp.core

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Registra l'audio a pezzi di [chunkMs]. Ogni pezzo chiuso diventa un file pronto da caricare.
 * Se il microfono non risponde riprova da solo: la registrazione non si ferma mai per errori temporanei.
 */
class AudioChunkRecorder(
    private val ctx: Context,
    private val chunkMs: Long,
    private val prefix: String = "audio"
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var running = false

    var lastError: String? = null
        private set

    private val rotate = Runnable { if (running) { closeCurrent(); startChunk() } }
    private val retry = Runnable { if (running) startChunk() }

    fun start() {
        if (running) return
        running = true
        startChunk()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(rotate)
        handler.removeCallbacks(retry)
        closeCurrent()
    }

    fun isRecording() = recorder != null

    @Suppress("DEPRECATION")
    private fun startChunk() {
        val f = Evidence.newAudioPart(ctx, prefix)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            current = f
            lastError = null
            handler.postDelayed(rotate, chunkMs)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            try { r.release() } catch (_: Exception) {}
            f.delete()
            handler.postDelayed(retry, 3_000)
        }
    }

    private fun closeCurrent() {
        val r = recorder ?: return
        val f = current
        recorder = null
        current = null
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        try { r.release() } catch (_: Exception) {}
        if (f != null) {
            if (ok && f.length() > 0) Evidence.finalize(f) else f.delete()
        }
    }
}

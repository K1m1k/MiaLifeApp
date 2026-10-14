package com.mialifeapp.core.voice

import android.content.Context
import com.mialifeapp.core.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Scarica e scompatta UNA VOLTA il modello vocale italiano (Vosk) nella cartella privata dell'app.
 * Serve solo in configurazione, con rete. Dopo, il riconoscimento è tutto sul telefono, offline.
 */
object VoiceModel {

    fun dir(c: Context) = File(c.filesDir, "voice-model")
    fun modelPath(c: Context) = File(dir(c), Config.VOICE_MODEL_NAME)

    fun isReady(c: Context): Boolean {
        val m = modelPath(c)
        return m.isDirectory && File(m, "am").exists()
    }

    /** Scarica e scompatta. NON sul thread principale. [progress] 0..100, -1 = scompattazione. */
    fun download(c: Context, progress: (Int) -> Unit): Boolean {
        if (isReady(c)) return true
        val target = dir(c).apply { mkdirs() }
        val zip = File(target, "model.zip")
        try {
            val conn = URL(Config.VOICE_MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            progress(-1)
            unzip(zip, target)
            zip.delete()
            return isReady(c)
        } catch (e: Exception) {
            zip.delete()
            return false
        }
    }

    private fun unzip(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(target, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
        }
    }
}

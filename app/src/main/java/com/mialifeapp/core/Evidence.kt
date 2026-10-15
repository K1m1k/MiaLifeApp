package com.mialifeapp.core

import android.content.Context
import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cartella privata delle prove sul telefono.
 * I file pronti stanno in "prove/", quelli già inviati per email vengono spostati in "prove/caricati/".
 * I file ancora in scrittura finiscono in ".part" e non vengono caricati.
 */
object Evidence {
    private const val PART = ".part"

    fun dir(c: Context) = File(c.filesDir, "prove").apply { mkdirs() }
    private fun uploadedDir(c: Context) = File(dir(c), "caricati").apply { mkdirs() }

    fun stamp(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ITALY).format(Date(t))

    fun newAudioPart(c: Context, prefix: String): File =
        File(dir(c), "${prefix}_${stamp()}_${System.nanoTime() % 1000}.m4a$PART")

    /** Rende "pronto" un file terminato. */
    fun finalize(part: File): File {
        val done = File(part.parentFile, part.name.removeSuffix(PART))
        if (!part.renameTo(done)) return part
        return done
    }

    /** Recupera i pezzi rimasti a metà (es. app chiusa di colpo): meglio un file parziale che niente. */
    fun recoverParts(c: Context) {
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(PART) }?.forEach {
            if (it.length() > 0) finalize(it) else it.delete()
        }
    }

    /** File pronti da caricare, dal più vecchio. */
    fun pending(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile && !it.name.endsWith(PART) }?.sortedBy { it.name } ?: emptyList()

    fun markUploaded(c: Context, f: File) {
        val target = File(uploadedDir(c), f.name)
        if (!f.renameTo(target)) f.delete()
    }

    fun writeLocation(c: Context, loc: Location?, prefix: String = "posizione") {
        val txt = buildString {
            append("MiaLifeApp - posizione\n")
            append("Data e ora: ").append(stamp()).append('\n')
            if (loc != null) {
                append("Latitudine: ").append(loc.latitude).append('\n')
                append("Longitudine: ").append(loc.longitude).append('\n')
                if (loc.hasAccuracy()) append("Precisione: ~").append(loc.accuracy.toInt()).append(" m\n")
                append("Mappa: ").append(LocationHelper.mapsLink(loc)).append('\n')
            } else {
                append("Posizione non disponibile in questo momento.\n")
            }
        }
        writeText(c, "${prefix}_${stamp()}.txt", txt)
    }

    fun writeText(c: Context, name: String, text: String) {
        val part = File(dir(c), name + PART)
        part.writeText(text)
        finalize(part)
    }

    fun mimeOf(f: File): String = when {
        f.name.endsWith(".m4a") -> "audio/mp4"
        f.name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }
}

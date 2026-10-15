package com.mialifeapp.core.mail

import android.content.Context
import com.mialifeapp.core.Evidence
import com.mialifeapp.core.SecurePrefs
import java.io.File

/**
 * Manda per email tutte le prove pronte (audio a pezzi e posizioni) alla casella scelta
 * dall'utente e alle eventuali email di fiducia. Ogni email contiene al massimo [MAX_BYTES] di allegati.
 * Un solo invio alla volta (servizio e lavori in coda condividono lo stesso lucchetto).
 * NON chiamare dal thread principale.
 */
object EmailUploader {
    enum class Result { OK, NOTHING, NEEDS_AUTH, NOT_CONFIGURED, FAILED }

    private const val MAX_BYTES = 8L * 1024 * 1024
    private val lock = Any()

    @Volatile var lastError: String? = null
        private set

    fun client(c: Context): SmtpClient? {
        val from = SecurePrefs.getMailAddress(c) ?: return null
        val pw = SecurePrefs.getMailPassword(c) ?: return null
        val host = SecurePrefs.getMailHost(c) ?: return null
        return SmtpClient(host, SecurePrefs.getMailPort(c), from, pw)
    }

    fun recipients(c: Context): List<String> {
        val own = SecurePrefs.getMailAddress(c) ?: return emptyList()
        return (listOf(own) + SecurePrefs.getMailTrusted(c)).distinctBy { it.lowercase() }
    }

    fun uploadPending(c: Context): Result = synchronized(lock) {
        val files = Evidence.pending(c)
        if (files.isEmpty()) return Result.NOTHING
        val smtp = client(c) ?: return Result.NOT_CONFIGURED
        val from = SecurePrefs.getMailAddress(c)!!
        for (batch in batches(files)) {
            try {
                smtp.send(from, recipients(c), subject(batch), body(batch), batch.map { SmtpClient.Attachment(it, Evidence.mimeOf(it)) })
                batch.forEach { Evidence.markUploaded(c, it) }
                lastError = null
            } catch (e: SmtpClient.SmtpException) {
                lastError = e.message
                return if (e.auth) Result.NEEDS_AUTH else Result.FAILED
            } catch (e: Exception) {
                lastError = e.message
                return Result.FAILED
            }
        }
        Result.OK
    }

    private fun batches(files: List<File>): List<List<File>> {
        val out = ArrayList<List<File>>()
        var cur = ArrayList<File>()
        var size = 0L
        for (f in files) {
            val len = f.length()
            if (cur.isNotEmpty() && size + len > MAX_BYTES) { out.add(cur); cur = ArrayList(); size = 0 }
            cur.add(f); size += len
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun subject(batch: List<File>): String {
        val test = batch.all { it.name.startsWith("prova") }
        return (if (test) "MiaLifeApp - PROVA " else "MiaLifeApp - prove ") + Evidence.stamp()
    }

    private fun body(batch: List<File>): String = buildString {
        if (batch.all { it.name.startsWith("prova") }) {
            append("Email di PROVA di MiaLifeApp: nessuna emergenza.\n\n")
        } else {
            append("MiaLifeApp: prove registrate durante una richiesta di aiuto.\n")
            append("Conserva questa email: gli allegati sono registrazioni audio e posizioni con data e ora.\n\n")
        }
        append("Allegati:\n")
        batch.forEach { append("- ").append(it.name).append('\n') }
    }
}

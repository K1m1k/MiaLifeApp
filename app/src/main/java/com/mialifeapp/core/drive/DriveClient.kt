package com.mialifeapp.core.drive

import android.content.Context
import com.mialifeapp.core.Config
import com.mialifeapp.core.Evidence
import com.mialifeapp.core.SecurePrefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Chiamate essenziali alle API REST di Google Drive v3 (senza librerie pesanti). */
object DriveClient {

    class DriveException(val code: Int, msg: String) : Exception(msg)

    private const val FILES = "https://www.googleapis.com/drive/v3/files?fields=id"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"

    private fun ensureFolder(c: Context, token: String): String {
        SecurePrefs.getDriveFolderId(c)?.let { return it }
        val meta = JSONObject()
            .put("name", Config.DRIVE_FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
        val id = post(FILES, token, "application/json; charset=UTF-8", meta.toString().toByteArray())
        SecurePrefs.setDriveFolderId(c, id)
        return id
    }

    /** Carica un file nella cartella delle prove. Se la cartella è stata cancellata la ricrea. */
    fun upload(c: Context, token: String, file: File) {
        try {
            uploadInto(token, ensureFolder(c, token), file)
        } catch (e: DriveException) {
            if (e.code == 404) {
                SecurePrefs.setDriveFolderId(c, null)
                uploadInto(token, ensureFolder(c, token), file)
            } else throw e
        }
    }

    private fun uploadInto(token: String, folderId: String, file: File) {
        val boundary = "mialife" + System.nanoTime()
        val meta = JSONObject()
            .put("name", file.name)
            .put("parents", org.json.JSONArray().put(folderId))
        val head = ("--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta + "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: ${Evidence.mimeOf(file)}\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val body = head + file.readBytes() + tail
        post(UPLOAD, token, "multipart/related; boundary=$boundary", body)
    }

    private fun post(url: String, token: String, contentType: String, body: ByteArray): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw DriveException(code, "Drive HTTP $code ${err ?: ""}".trim())
            }
            val resp = conn.inputStream.bufferedReader().readText()
            return JSONObject(resp).getString("id")
        } finally {
            conn.disconnect()
        }
    }
}

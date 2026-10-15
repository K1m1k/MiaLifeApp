package com.mialifeapp.core.mail

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Invio email essenziale via SMTP, senza librerie esterne e senza server propri:
 * l'app si collega direttamente al server di posta della casella dell'utente.
 *
 * Sempre cifrato: porta 465 = TLS dall'inizio, porte 587/25 = STARTTLS obbligatorio.
 * Se il server non offre la cifratura, l'invio viene rifiutato (la password non viaggia mai in chiaro).
 */
class SmtpClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000
) {
    /** [auth] = true: utente o password rifiutati dal server. */
    class SmtpException(msg: String, val auth: Boolean = false) : Exception(msg)

    class Attachment(val file: File, val mime: String)

    private lateinit var socket: Socket
    private lateinit var input: BufferedReader
    private lateinit var output: OutputStream

    val implicitTls: Boolean get() = port == 465

    fun send(from: String, to: List<String>, subject: String, body: String, attachments: List<Attachment> = emptyList()) {
        if (to.isEmpty()) throw SmtpException("Nessun destinatario.")
        try {
            open()
            val ehlo = handshake()
            login(ehlo)
            cmd("MAIL FROM:<$from>", 250)
            for (r in to) cmd("RCPT TO:<$r>", 250, 251)
            cmd("DATA", 354)
            writeMessage(from, to, subject, body, attachments)
            cmd(".", 250)
            try { cmd("QUIT", 221) } catch (_: Exception) {}
        } catch (e: SmtpException) {
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            throw SmtpException("Connessione sicura non riuscita con $host: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            throw SmtpException("Server $host non trovato (rete assente o nome sbagliato).")
        } catch (e: java.io.IOException) {
            throw SmtpException("Connessione con $host interrotta: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Solo verifica di server, utente e password (nessuna email inviata). */
    fun checkLogin() {
        try {
            open()
            login(handshake())
            try { cmd("QUIT", 221) } catch (_: Exception) {}
        } catch (e: SmtpException) {
            throw e
        } catch (e: java.io.IOException) {
            throw SmtpException("Connessione con $host non riuscita: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun open() {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        raw.soTimeout = readTimeoutMs
        socket = if (implicitTls) secure(raw) else raw
        streams()
        expect(220)
    }

    private fun streams() {
        input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        output = socket.getOutputStream()
    }

    private fun secure(raw: Socket): Socket {
        val s = sslFactory.createSocket(raw, host, port, true) as SSLSocket
        s.useClientMode = true
        s.startHandshake()
        if (!verifier.verify(host, s.session)) {
            s.close()
            throw SmtpException("Il certificato del server non corrisponde a $host.")
        }
        return s
    }

    /** EHLO (e STARTTLS se serve). Ritorna le capacità dichiarate dal server. */
    private fun handshake(): List<String> {
        var caps = cmd("EHLO mialifeapp", 250)
        if (!implicitTls) {
            if (caps.none { it.uppercase(Locale.US).startsWith("STARTTLS") })
                throw SmtpException("Il server $host non offre la connessione cifrata: invio bloccato per proteggere la password.")
            cmd("STARTTLS", 220)
            socket = secure(socket)
            streams()
            caps = cmd("EHLO mialifeapp", 250)
        }
        return caps
    }

    private fun login(caps: List<String>) {
        val auth = caps.firstOrNull { it.uppercase(Locale.US).startsWith("AUTH") }?.uppercase(Locale.US) ?: "AUTH PLAIN LOGIN"
        val enc = Base64.getEncoder()
        try {
            if (auth.contains("PLAIN")) {
                val token = enc.encodeToString("\u0000$user\u0000$password".toByteArray(Charsets.UTF_8))
                cmd("AUTH PLAIN $token", 235)
            } else {
                cmd("AUTH LOGIN", 334)
                cmd(enc.encodeToString(user.toByteArray(Charsets.UTF_8)), 334)
                cmd(enc.encodeToString(password.toByteArray(Charsets.UTF_8)), 235)
            }
        } catch (e: SmtpException) {
            throw SmtpException("Email o password rifiutate dal server. ${e.message ?: ""}".trim(), auth = true)
        }
    }

    private fun cmd(line: String, vararg ok: Int): List<String> {
        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        return expect(*ok)
    }

    /** Legge una risposta (anche su più righe) e controlla il codice. */
    private fun expect(vararg ok: Int): List<String> {
        val lines = ArrayList<String>()
        while (true) {
            val l = input.readLine() ?: throw SmtpException("Il server $host ha chiuso la connessione.")
            if (l.length < 3) throw SmtpException("Risposta non valida dal server: $l")
            lines.add(if (l.length > 4) l.substring(4) else "")
            if (l.length == 3 || l[3] == ' ') {
                val code = l.substring(0, 3).toIntOrNull() ?: throw SmtpException("Risposta non valida dal server: $l")
                if (code !in ok) throw SmtpException("$code ${lines.joinToString(" ")}".trim())
                return lines
            }
        }
    }

    private fun writeMessage(from: String, to: List<String>, subject: String, body: String, attachments: List<Attachment>) {
        val boundary = "mialife_" + System.nanoTime()
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("From: <").append(from).append(">\r\n")
        sb.append("To: ").append(to.joinToString(", ") { "<$it>" }).append("\r\n")
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n")
        sb.append("Date: ").append(date).append("\r\n")
        sb.append("Message-ID: <").append(System.currentTimeMillis()).append('.').append(System.nanoTime()).append("@mialifeapp>\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(base64Lines(body.toByteArray(Charsets.UTF_8)))
        write(sb.toString())
        for (a in attachments) {
            val name = a.file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            write("--$boundary\r\n" +
                "Content-Type: ${a.mime}; name=\"$name\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "Content-Disposition: attachment; filename=\"$name\"\r\n\r\n")
            write(base64Lines(a.file.readBytes()))
        }
        write("--$boundary--\r\n")
    }

    private fun write(s: String) {
        // Nessuna riga può iniziare con "." (base64 non contiene punti; il testo sì): dot-stuffing.
        val stuffed = s.replace("\r\n.", "\r\n..").let { if (it.startsWith(".")) ".$it" else it }
        output.write(stuffed.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        fun base64Lines(data: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(data)
            val sb = StringBuilder(b64.length + b64.length / 76 * 2 + 2)
            var i = 0
            while (i < b64.length) {
                sb.append(b64, i, minOf(i + 76, b64.length)).append("\r\n")
                i += 76
            }
            return sb.toString()
        }

        fun encodeHeader(s: String): String =
            if (s.all { it.code in 32..126 }) s
            else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="
    }
}

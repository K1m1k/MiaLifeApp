package com.mialifeapp.core.mail

import java.util.Locale

/** Server di posta già noti: l'utente scrive solo indirizzo e password, il resto lo propone l'app. */
object MailSettings {

    class Preset(val host: String, val port: Int, val note: String, val supported: Boolean = true)

    private val presets: Map<String, Preset> = buildMap {
        val gmail = Preset("smtp.gmail.com", 465,
            "Gmail: serve una \"password per le app\" (non la password normale). Si crea dall'account Google, dopo aver attivato la verifica in due passaggi.")
        listOf("gmail.com", "googlemail.com").forEach { put(it, gmail) }
        val ms = Preset("smtp-mail.outlook.com", 587,
            "Outlook/Hotmail/Live non permettono più l'invio da app con la password: usa un'altra casella (per esempio Gmail).", supported = false)
        listOf("outlook.com", "outlook.it", "hotmail.com", "hotmail.it", "live.com", "live.it", "msn.com").forEach { put(it, ms) }
        val libero = Preset("smtp.libero.it", 465, "Libero: usa la password della casella.")
        listOf("libero.it", "inwind.it", "iol.it", "blu.it", "giallo.it").forEach { put(it, libero) }
        val virgilio = Preset("out.virgilio.it", 465, "Virgilio: usa la password della casella.")
        listOf("virgilio.it", "tin.it").forEach { put(it, virgilio) }
        val yahoo = Preset("smtp.mail.yahoo.com", 465, "Yahoo: serve una \"password per le app\" creata dalle impostazioni di sicurezza dell'account.")
        listOf("yahoo.com", "yahoo.it", "ymail.com").forEach { put(it, yahoo) }
        val icloud = Preset("smtp.mail.me.com", 587, "iCloud: serve una \"password specifica per le app\" creata dall'ID Apple.")
        listOf("icloud.com", "me.com", "mac.com").forEach { put(it, icloud) }
        val gmx = Preset("mail.gmx.net", 465, "GMX: attiva l'accesso POP3/IMAP nelle impostazioni della casella.")
        listOf("gmx.com", "gmx.it", "gmx.net", "gmx.de").forEach { put(it, gmx) }
        put("aruba.it", Preset("smtps.aruba.it", 465, "Aruba: usa la password della casella."))
        put("tiscali.it", Preset("smtp.tiscali.it", 465, "Tiscali: usa la password della casella."))
    }

    fun domainOf(address: String): String = address.substringAfterLast('@', "").trim().lowercase(Locale.ROOT)

    /** Preset noto oppure ipotesi "smtp.dominio" sulla porta 465 (modificabile dall'utente). */
    fun guess(address: String): Preset {
        val d = domainOf(address)
        return presets[d] ?: Preset("smtp.$d", 465,
            "Casella non riconosciuta: controlla server e porta nelle impostazioni del tuo fornitore di posta (porta 465 o 587).")
    }

    fun isKnown(address: String) = presets.containsKey(domainOf(address))

    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    fun isValidAddress(s: String) = emailRegex.matches(s.trim())
}

package com.mialifeapp.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Configurazione salvata SOLO cifrata sul telefono.
 * Se la cifratura non funziona non c'è nessun ripiego in chiaro: [isAvailable] torna false
 * e l'app blocca la configurazione mostrando un messaggio chiaro.
 */
object SecurePrefs {

    private const val FILE = "mialife_secure_v2"
    private val cache = ConcurrentHashMap<String, String>()
    @Volatile private var available: Boolean? = null

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAvailable(c: Context): Boolean {
        available?.let { return it }
        val ok = Crypto.selfTest()
        available = ok
        return ok
    }

    // ---- base ----
    private fun getStr(c: Context, k: String): String? {
        cache[k]?.let { return it }
        val raw = sp(c).getString(k, null) ?: return null
        val v = try { Crypto.decrypt(raw) } catch (e: Exception) { return null }
        cache[k] = v
        return v
    }

    /** Ritorna false se non è stato possibile salvare in modo cifrato. */
    private fun putStr(c: Context, k: String, v: String?): Boolean {
        if (v == null) {
            cache.remove(k)
            return sp(c).edit().remove(k).commit()
        }
        val enc = try { Crypto.encrypt(v) } catch (e: Exception) { return false }
        val ok = sp(c).edit().putString(k, enc).commit()
        if (ok) cache[k] = v
        return ok
    }

    private fun getBool(c: Context, k: String) = getStr(c, k) == "1"
    private fun putBool(c: Context, k: String, v: Boolean) = putStr(c, k, if (v) "1" else null)
    private fun getLong(c: Context, k: String) = getStr(c, k)?.toLongOrNull() ?: 0L

    // ---- contatti ----
    fun getContacts(c: Context): List<String> =
        (1..3).mapNotNull { getStr(c, "contact$it") }.map { it.trim() }.filter { it.isNotEmpty() }

    fun getContactRaw(c: Context, idx: Int): String = getStr(c, "contact$idx") ?: ""

    fun setContacts(c: Context, list: List<String>): Boolean {
        var ok = true
        for (i in 1..3) ok = putStr(c, "contact$i", list.getOrNull(i - 1)?.trim()?.ifEmpty { null }) && ok
        return ok
    }

    // ---- sequenza ----
    fun getPattern(c: Context): List<Int> {
        val raw = getStr(c, "pattern") ?: return Config.DEFAULT_PATTERN
        return raw.split("-").mapNotNull { it.toIntOrNull() }.filter { it > 0 }
            .ifEmpty { Config.DEFAULT_PATTERN }
    }

    fun setPattern(c: Context, groups: List<Int>) = putStr(c, "pattern", groups.joinToString("-"))

    // ---- PIN (salvati come impronta PBKDF2, mai in chiaro) ----
    fun getPinRecord(c: Context): String? = getStr(c, "pin")
    fun setPinRecord(c: Context, v: String?) = putStr(c, "pin", v)
    fun getCoercionRecord(c: Context): String? = getStr(c, "pin_coercion")
    fun setCoercionRecord(c: Context, v: String?) = putStr(c, "pin_coercion", v)

    fun getPinFails(c: Context): Int = getStr(c, "pin_fails")?.toIntOrNull() ?: 0
    fun setPinFails(c: Context, v: Int) = putStr(c, "pin_fails", if (v > 0) v.toString() else null)
    fun getPinLockUntil(c: Context): Long = getLong(c, "pin_lock")
    fun setPinLockUntil(c: Context, t: Long) = putStr(c, "pin_lock", if (t > 0) t.toString() else null)

    /** Messaggio in codice concordato con i contatti, usato al posto degli SMS espliciti in coercizione. */
    fun getCoercionCode(c: Context): String = getStr(c, "coercion_code")?.trim() ?: ""
    fun setCoercionCode(c: Context, v: String) = putStr(c, "coercion_code", v.trim().ifEmpty { null })

    // ---- stato ----
    fun isOnboarded(c: Context) = getBool(c, "onboarded")
    fun setOnboarded(c: Context, v: Boolean) = putBool(c, "onboarded", v)

    fun isArmed(c: Context) = getBool(c, "armed")
    fun setArmed(c: Context, v: Boolean) = putBool(c, "armed", v)

    /** Scadenza automatica dell'allerta, Fase 1 (ms); 0 = nessuna. L'allerta attiva è [isArmed]. */
    fun getAlertUntil(c: Context) = getLong(c, "alert_until")
    fun setAlertUntil(c: Context, t: Long) = putStr(c, "alert_until", if (t > 0) t.toString() else null)

    /** Avvisi e consigli accettati: versione accettata (0 = mai). */
    fun getConsentVersion(c: Context) = getStr(c, "consent")?.toIntOrNull() ?: 0
    fun setConsentVersion(c: Context, v: Int) = putStr(c, "consent", v.toString())

    /** Sequenze del tasto volume: "d"=giù, "u"=su. Allerta e Aiuto. */
    fun getVolAlertSeq(c: Context) = getStr(c, "vol_alert") ?: Config.VOL_ALERT_DEFAULT
    fun setVolAlertSeq(c: Context, v: String) = putStr(c, "vol_alert", v)
    fun getVolHelpSeq(c: Context) = getStr(c, "vol_help") ?: Config.VOL_HELP_DEFAULT
    fun setVolHelpSeq(c: Context, v: String) = putStr(c, "vol_help", v)

    fun isEmergencyActive(c: Context) = getBool(c, "emergency")
    fun setEmergencyActive(c: Context, v: Boolean) = putBool(c, "emergency", v)

    fun getEmergencyStart(c: Context) = getLong(c, "emergency_start")
    fun setEmergencyStart(c: Context, t: Long) = putStr(c, "emergency_start", if (t > 0) t.toString() else null)

    /** Emergenza avviata/continuata con il PIN di coercizione (in segreto). */
    fun isCoerced(c: Context) = getBool(c, "coerced")
    fun setCoerced(c: Context, v: Boolean) = putBool(c, "coerced", v)

    /** Lo schermo mostra "protezione disattivata" ma in realtà è attiva (coercizione). */
    fun isFakeDisarmed(c: Context) = getBool(c, "fake_disarmed")
    fun setFakeDisarmed(c: Context, v: Boolean) = putBool(c, "fake_disarmed", v)

    fun isOemDone(c: Context) = getBool(c, "oem_done")
    fun setOemDone(c: Context, v: Boolean) = putBool(c, "oem_done", v)

    /** Sensibilità colpetti: 0 bassa, 1 media (predefinita), 2 alta. */
    fun getTapSensitivity(c: Context): Int = getStr(c, "tap_sens")?.toIntOrNull()?.coerceIn(0, 2) ?: 1
    fun setTapSensitivity(c: Context, v: Int) = putStr(c, "tap_sens", v.coerceIn(0, 2).toString())

    // ---- Attivazione vocale ----
    fun isVoiceEnabled(c: Context) = getBool(c, "voice_on")
    fun setVoiceEnabled(c: Context, v: Boolean) = putBool(c, "voice_on", v)

    /**
     * Le registrazioni della frase (una sequenza MFCC per registrazione), salvate CIFRATE in un file
     * privato dell'app. Formato interno: registrazioni separate da "|", frame da ";", valori da ",".
     */
    fun getVoiceTemplates(c: Context): List<List<FloatArray>>? {
        val f = java.io.File(c.filesDir, "voice_tpl.enc")
        if (!f.exists()) return null
        return try {
            val raw = Crypto.decrypt(f.readText())
            val takes = raw.split("|").filter { it.isNotEmpty() }.map { take ->
                take.split(";").filter { it.isNotEmpty() }.map { row ->
                    row.split(",").map { it.toFloat() }.toFloatArray()
                }
            }.filter { it.isNotEmpty() }
            takes.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    fun setVoiceTemplates(c: Context, takes: List<List<FloatArray>>?): Boolean {
        val f = java.io.File(c.filesDir, "voice_tpl.enc")
        if (takes.isNullOrEmpty()) { f.delete(); return true }
        return try {
            val raw = takes.joinToString("|") { take ->
                take.joinToString(";") { row -> row.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it) } }
            }
            f.writeText(Crypto.encrypt(raw))
            true
        } catch (e: Exception) { false }
    }

    fun hasVoiceTemplates(c: Context) = java.io.File(c.filesDir, "voice_tpl.enc").exists()

    fun getVoiceThreshold(c: Context): Int = getStr(c, "voice_thr")?.toIntOrNull()?.coerceIn(0, 100) ?: Config.VOICE_SPEAKER_THRESHOLD_DEFAULT
    fun setVoiceThreshold(c: Context, v: Int) = putStr(c, "voice_thr", v.coerceIn(0, 100).toString())


    // ---- Email delle prove (al posto di Google Drive) ----
    fun getMailAddress(c: Context): String? = getStr(c, "mail_addr")
    fun setMailAddress(c: Context, v: String?) = putStr(c, "mail_addr", v?.trim()?.ifEmpty { null })
    /** Password (o "password per le app") della casella: salvata solo cifrata. */
    fun getMailPassword(c: Context): String? = getStr(c, "mail_pw")
    fun setMailPassword(c: Context, v: String?) = putStr(c, "mail_pw", v?.ifEmpty { null })
    fun getMailHost(c: Context): String? = getStr(c, "mail_host")
    fun setMailHost(c: Context, v: String?) = putStr(c, "mail_host", v?.trim()?.ifEmpty { null })
    fun getMailPort(c: Context): Int = getStr(c, "mail_port")?.toIntOrNull() ?: 465
    fun setMailPort(c: Context, v: Int) = putStr(c, "mail_port", v.toString())
    /** Email di persone di fiducia che ricevono anche loro le prove (facoltative, massimo 2). */
    fun getMailTrusted(c: Context): List<String> =
        (getStr(c, "mail_trusted") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun setMailTrusted(c: Context, list: List<String>) = putStr(c, "mail_trusted", list.joinToString(",").ifEmpty { null })
    /** Email di prova arrivata: la casella funziona. */
    fun isMailOk(c: Context) = getBool(c, "mail_ok")
    fun setMailOk(c: Context, v: Boolean) = putBool(c, "mail_ok", v)

    // ---- Allerta (Fase 1) ----
    /** Durata dell'allerta quando si attiva dai tasti (minuti). */
    fun getAlertMinutes(c: Context): Int = getStr(c, "alert_min")?.toIntOrNull() ?: Config.ALERT_DEFAULT_MIN
    fun setAlertMinutes(c: Context, v: Int) = putStr(c, "alert_min", v.toString())

    // ---- Pulsante Bluetooth (telecomando / portachiavi) ----
    fun getBtDescriptor(c: Context): String? = getStr(c, "bt_desc")
    fun getBtName(c: Context): String? = getStr(c, "bt_name")
    fun getBtKey(c: Context): Int = getStr(c, "bt_key")?.toIntOrNull() ?: 0
    fun setBtButton(c: Context, descriptor: String?, name: String?, key: Int) {
        putStr(c, "bt_desc", descriptor)
        putStr(c, "bt_name", name)
        putStr(c, "bt_key", if (descriptor == null) null else key.toString())
    }

    // ---- coda SMS ----
    data class QueuedSms(val number: String, val text: String)

    @Synchronized
    fun getSmsQueue(c: Context): MutableList<QueuedSms> {
        val raw = getStr(c, "sms_queue") ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) {
                val o = arr.getJSONObject(it)
                QueuedSms(o.getString("n"), o.getString("t"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun setSmsQueue(c: Context, q: List<QueuedSms>) {
        if (q.isEmpty()) { putStr(c, "sms_queue", null); return }
        val arr = JSONArray()
        q.forEach { arr.put(JSONObject().put("n", it.number).put("t", it.text)) }
        putStr(c, "sms_queue", arr.toString())
    }
}

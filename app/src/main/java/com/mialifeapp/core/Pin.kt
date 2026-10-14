package com.mialifeapp.core

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class PinResult { REAL, COERCION, WRONG, LOCKED }

/**
 * Gestione dei PIN: si salva solo l'impronta (PBKDF2 con sale), mai il PIN.
 * Dopo [MAX_FAILS] tentativi sbagliati di fila, i PIN vengono bloccati per [LOCK_MS]:
 * chi ha il telefono in mano non può provarli tutti.
 */
object Pin {
    private const val ITERATIONS = 12_000
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60_000L

    fun isValidFormat(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    fun makeRecord(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return b64(salt) + ":" + b64(derive(pin, salt))
    }

    private fun matches(pin: String, record: String?): Boolean {
        if (record == null) return false
        val parts = record.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val expected = Base64.decode(parts[1], Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, derive(pin, salt))
    }

    /** Minuti di blocco rimasti (0 = non bloccato). */
    fun lockMinutesLeft(c: Context): Int {
        val left = SecurePrefs.getPinLockUntil(c) - System.currentTimeMillis()
        return if (left <= 0) 0 else ((left + 59_999) / 60_000).toInt()
    }

    /** Controllo del PIN inserito dall'utente: conta i tentativi sbagliati e applica il blocco. */
    fun check(c: Context, pin: String): PinResult {
        if (lockMinutesLeft(c) > 0) return PinResult.LOCKED
        val r = peek(c, pin)
        if (r == PinResult.WRONG) {
            val fails = SecurePrefs.getPinFails(c) + 1
            if (fails >= MAX_FAILS) {
                SecurePrefs.setPinFails(c, 0)
                SecurePrefs.setPinLockUntil(c, System.currentTimeMillis() + LOCK_MS)
            } else {
                SecurePrefs.setPinFails(c, fails)
            }
        } else {
            SecurePrefs.setPinFails(c, 0)
        }
        return r
    }

    /** Confronto senza contare i tentativi (usato solo nella configurazione). */
    fun peek(c: Context, pin: String): PinResult = when {
        matches(pin, SecurePrefs.getPinRecord(c)) -> PinResult.REAL
        matches(pin, SecurePrefs.getCoercionRecord(c)) -> PinResult.COERCION
        else -> PinResult.WRONG
    }

    fun hasPin(c: Context) = SecurePrefs.getPinRecord(c) != null
    fun hasCoercion(c: Context) = SecurePrefs.getCoercionRecord(c) != null

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}

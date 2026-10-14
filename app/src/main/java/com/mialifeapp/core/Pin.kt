package com.mialifeapp.core

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class PinResult { REAL, COERCION, WRONG }

/** Gestione dei PIN: si salva solo l'impronta (PBKDF2 con sale), mai il PIN. */
object Pin {
    private const val ITERATIONS = 12_000

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

    fun check(c: Context, pin: String): PinResult = when {
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

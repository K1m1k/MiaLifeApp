package com.mialifeapp.core.drive

import android.content.Context
import com.mialifeapp.core.Evidence

/**
 * Carica su Drive tutti i file pronti. Un solo caricamento alla volta (servizio e lavori in coda
 * condividono lo stesso lucchetto), così nessun file viene caricato due volte.
 * NON chiamare dal thread principale.
 */
object DriveUploader {
    enum class Result { OK, NOTHING, NEEDS_AUTH, FAILED }

    private val lock = Any()

    fun uploadPending(c: Context): Result = synchronized(lock) {
        val files = Evidence.pending(c)
        if (files.isEmpty()) return Result.NOTHING
        val token = DriveAuth.tokenBlocking(c) ?: return Result.NEEDS_AUTH
        for (f in files) {
            try {
                DriveClient.upload(c, token, f)
                Evidence.markUploaded(c, f)
            } catch (e: DriveClient.DriveException) {
                return if (e.code == 401 || e.code == 403) Result.NEEDS_AUTH else Result.FAILED
            } catch (e: Exception) {
                return Result.FAILED
            }
        }
        Result.OK
    }
}

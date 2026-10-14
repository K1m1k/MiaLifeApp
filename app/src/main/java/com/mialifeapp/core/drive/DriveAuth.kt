package com.mialifeapp.core.drive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.mialifeapp.core.SecurePrefs
import java.util.concurrent.TimeUnit

/**
 * Accesso al Google Drive dell'utente con il suo account Google.
 * Permesso richiesto: "drive.file" = l'app vede SOLO i file che crea lei.
 * Nessun server: il token lo gestiscono i Google Play services sul telefono.
 */
object DriveAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    private fun request(c: Context): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        SecurePrefs.getDriveAccount(c)?.let { b.setAccount(Account(it, "com.google")) }
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun remember(c: Context, res: AuthorizationResult) {
        res.toGoogleSignInAccount()?.email?.let { SecurePrefs.setDriveAccount(c, it) }
    }

    /** Dalla guida: chiede il consenso (se serve mostra la schermata Google). */
    fun authorizeInteractive(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onResult: (token: String?, error: String?) -> Unit
    ) {
        // Nuovo collegamento: dimentica l'account precedente per poterne scegliere un altro.
        SecurePrefs.setDriveAccount(activity, null)
        Identity.getAuthorizationClient(activity).authorize(request(activity))
            .addOnSuccessListener { res ->
                val pi = res.pendingIntent
                if (res.hasResolution() && pi != null) {
                    launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    remember(activity, res)
                    onResult(res.accessToken, null)
                }
            }
            .addOnFailureListener { onResult(null, explain(it)) }
    }

    /** Risultato della schermata di consenso Google. */
    fun handleResult(activity: Activity, data: Intent?): Pair<String?, String?> = if (data == null) {
        Pair(null, "Operazione annullata.")
    } else try {
        val res = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        remember(activity, res)
        Pair(res.accessToken, null)
    } catch (e: Exception) {
        Pair(null, explain(e))
    }

    /** In background (servizio / lavori): token senza interfaccia, o null se serve ricollegare. */
    fun tokenBlocking(c: Context): String? = try {
        val res = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(c)), 30, TimeUnit.SECONDS)
        if (res.hasResolution()) null else res.accessToken
    } catch (e: Exception) {
        null
    }

    private fun explain(e: Exception): String {
        if (e is ApiException) {
            return when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR ->
                    "Configurazione Google Cloud mancante o errata (codice 10). Vedi README, sezione Google Drive."
                CommonStatusCodes.NETWORK_ERROR -> "Nessuna connessione a internet."
                CommonStatusCodes.CANCELED -> "Operazione annullata."
                else -> "Errore Google (codice ${e.statusCode})."
            }
        }
        return e.message ?: "Errore sconosciuto."
    }
}

package com.mialifeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mialifeapp.core.EmergencyControl
import com.mialifeapp.core.SecurePrefs
import com.mialifeapp.databinding.ActivityWelcomeBinding

/** Punto di ingresso: benvenuto alla prima apertura, poi smista alla schermata giusta. */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = SecurePrefs.isAvailable(this)
        if (ok && SecurePrefs.isOnboarded(this)) {
            val emergencyScreen = EmergencyControl.showDarkScreen(this)
            startActivity(Intent(this, if (emergencyScreen) EmergencyActivity::class.java else MainActivity::class.java))
            finish()
            return
        }
        val b = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (!ok) {
            // Cifratura non disponibile: niente ripiego in chiaro, l'app si ferma qui.
            b.cryptoCard.visibility = View.VISIBLE
            b.startButton.visibility = View.GONE
            return
        }
        b.startButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }
}

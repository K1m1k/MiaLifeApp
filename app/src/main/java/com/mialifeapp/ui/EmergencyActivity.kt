package com.mialifeapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mialifeapp.R
import com.mialifeapp.core.Pin
import com.mialifeapp.core.PinResult
import com.mialifeapp.core.EmergencyControl
import com.mialifeapp.databinding.ActivityEmergencyBinding

/**
 * Schermata mostrata se si apre l'app durante un'emergenza: tutta nera, luminosità al minimo.
 * Solo un tasto quasi invisibile in basso porta al PIN per fermare l'emergenza.
 */
class EmergencyActivity : AppCompatActivity() {

    private lateinit var b: ActivityEmergencyBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(b.root)

        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        b.hiddenButton.setOnClickListener {
            b.pinPanel.visibility = View.VISIBLE
            b.pinInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.pinInput, InputMethodManager.SHOW_IMPLICIT)
        }
        b.pinConfirm.setOnClickListener { confirm() }
    }

    override fun onResume() {
        super.onResume()
        if (!EmergencyControl.showDarkScreen(this)) goMain()
    }

    private fun confirm() {
        when (Pin.check(this, b.pinInput.text.toString())) {
            PinResult.REAL -> {
                EmergencyControl.stopWithRealPin(this)
                done()
            }
            PinResult.COERCION -> {
                // Sembra fermata, ma continua in segreto e avvisa i contatti della costrizione.
                EmergencyControl.coerce(this, showAsDisarmed = true)
                done()
            }
            PinResult.WRONG -> {
                b.pinInput.setText("")
                b.pinMessage.text = getString(R.string.pin_wrong)
            }
        }
    }

    private fun done() {
        b.pinMessage.text = getString(R.string.dark_stopped)
        b.pinInput.setText("")
        handler.postDelayed({ goMain() }, 700)
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

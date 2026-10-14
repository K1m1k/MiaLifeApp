package com.mialifeapp.ui

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mialifeapp.R
import com.mialifeapp.core.Pin
import com.mialifeapp.core.PinResult

enum class Level { OK, WARN, BAD }

/** Scrive una riga di stato colorata: ✓ verde, ! arancio, ✗ rosso. */
fun TextView.status(level: Level, text: String) {
    val (sym, color) = when (level) {
        Level.OK -> "✓" to R.color.ok
        Level.WARN -> "!" to R.color.amber
        Level.BAD -> "✗" to R.color.danger
    }
    this.text = "$sym  $text"
    setTextColor(ContextCompat.getColor(context, color))
}

fun Activity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Finestra per inserire il PIN; il risultato distingue PIN vero, di coercizione o errato. */
object PinDialog {
    fun show(a: Activity, onResult: (PinResult) -> Unit) {
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            hint = a.getString(R.string.pin_hint)
            gravity = Gravity.CENTER
            textSize = 22f
        }
        val box = FrameLayout(a).apply {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pin_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onResult(Pin.check(a, input.text.toString())) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }
}

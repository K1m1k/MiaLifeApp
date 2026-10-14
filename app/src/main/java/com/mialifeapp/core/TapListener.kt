package com.mialifeapp.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Rileva i colpetti sul telefono dall'accelerometro.
 * Un colpetto è uno "scatto" brusco: l'accelerazione cambia molto tra due letture consecutive
 * (lette 200 volte al secondo). Camminare o girare il telefono produce cambi lenti e non conta.
 * Sensibilità: 0 = bassa, 1 = media, 2 = alta (scelta nella configurazione).
 */
class TapListener(
    context: Context,
    private val onTap: (Long) -> Unit,
    sensitivity: Int = SecurePrefs.getTapSensitivity(context)
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Variazione minima (m/s²) tra due letture per considerarla un colpetto. */
    private val threshold = when (sensitivity) {
        0 -> 3.0f
        2 -> 0.9f
        else -> 1.6f
    }
    private val refractoryMs = 100L

    private var lx = 0f
    private var ly = 0f
    private var lz = 0f
    private var hasLast = false
    private var lastTapAt = 0L

    fun start(): Boolean {
        val s = accel ?: return false
        hasLast = false
        return sm.registerListener(this, s, 5_000) // 200 letture al secondo
    }

    fun stop() {
        try { sm.unregisterListener(this) } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!hasLast) {
            lx = x; ly = y; lz = z; hasLast = true
            return
        }
        val dx = x - lx
        val dy = y - ly
        val dz = z - lz
        lx = x; ly = y; lz = z
        val jerk = sqrt(dx * dx + dy * dy + dz * dz)
        val now = System.currentTimeMillis()
        if (jerk > threshold && now - lastTapAt > refractoryMs) {
            lastTapAt = now
            onTap(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

package com.mialifeapp.core.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estrae i coefficienti MFCC da un audio (16 kHz, mono, campioni normalizzati -1..1).
 * Gli MFCC descrivono il "colore" del suono e sono la base per confrontare come viene detta
 * una frase e il timbro di chi la dice. Tutto in puro Kotlin, nessuna libreria esterna.
 */
object Mfcc {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME = 512           // ~32 ms
    private const val HOP = 256             // ~16 ms
    private const val MEL_FILTERS = 26
    const val COEFFS = 13
    private const val PREEMPH = 0.97f

    /** Energia minima di un frame perché sia considerato "voce" e non silenzio. */
    private const val FRAME_ENERGY_MIN = 0.0008f

    /** Ritorna un vettore MFCC per ogni frame sonoro (silenzio escluso). */
    fun frames(samples: FloatArray): List<FloatArray> {
        if (samples.size < FRAME) return emptyList()
        val pre = FloatArray(samples.size)
        pre[0] = samples[0]
        for (i in 1 until samples.size) pre[i] = samples[i] - PREEMPH * samples[i - 1]

        val mel = melFilterbank()
        val out = ArrayList<FloatArray>()
        var start = 0
        val window = hamming(FRAME)
        while (start + FRAME <= pre.size) {
            var energy = 0f
            val frame = FloatArray(FRAME)
            for (i in 0 until FRAME) {
                val v = pre[start + i]
                energy += v * v
                frame[i] = v * window[i]
            }
            if (energy / FRAME > FRAME_ENERGY_MIN) {
                out.add(mfccOf(frame, mel))
            }
            start += HOP
        }
        return out
    }

    /** Media dei vettori MFCC: l'"impronta" di una frase detta da una voce. */
    fun meanVector(frameList: List<FloatArray>): FloatArray? {
        if (frameList.isEmpty()) return null
        val acc = FloatArray(COEFFS)
        for (f in frameList) for (i in 0 until COEFFS) acc[i] += f[i]
        for (i in 0 until COEFFS) acc[i] = acc[i] / frameList.size.toFloat()
        return acc
    }

    /** Media di più impronte (le registrazioni ripetute). */
    fun average(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val acc = FloatArray(COEFFS)
        for (v in vectors) for (i in 0 until COEFFS) acc[i] += v[i]
        for (i in 0 until COEFFS) acc[i] = acc[i] / vectors.size.toFloat()
        return acc
    }

    /** Somiglianza 0..100 tra due impronte (coseno). */
    fun similarity(a: FloatArray, b: FloatArray): Int {
        if (a.size != b.size) return 0
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na <= 0f || nb <= 0f) return 0
        val cos = dot / (sqrt(na) * sqrt(nb))
        return (((cos + 1f) / 2f) * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * Somiglianza 0..100 tra due FRASI (sequenze di vettori MFCC) con allineamento temporale (DTW).
     * Confronta insieme "cosa è detto" e "come": è la base del riconoscimento sulla frase registrata.
     */
    fun sequenceSimilarity(a: List<FloatArray>, b: List<FloatArray>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val n = a.size; val m = b.size
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(m + 1) { inf }
        var cur = FloatArray(m + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            cur[0] = inf
            for (j in 1..m) {
                val cost = dist(a[i - 1], b[j - 1])
                val best = minOf(prev[j], cur[j - 1], prev[j - 1])
                cur[j] = cost + best
            }
            val t = prev; prev = cur; cur = t
        }
        val pathLen = (n + m).toFloat()
        val avg = prev[m] / pathLen
        // avg piccolo = molto simile. SCALE è la distanza media oltre la quale la somiglianza è 0.
        val sim = (1f - avg / SCALE) * 100f
        return sim.toInt().coerceIn(0, 100)
    }

    /** Distanza media DTW tra due sequenze (per scegliere la registrazione "tipica"). */
    fun dtwDistance(a: List<FloatArray>, b: List<FloatArray>): Float =
        (100 - sequenceSimilarity(a, b)).toFloat()

    private const val SCALE = 45f

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    // ---- interni ----
    private fun mfccOf(frame: FloatArray, mel: Array<FloatArray>): FloatArray {
        val power = powerSpectrum(frame)
        val logMel = FloatArray(MEL_FILTERS)
        for (m in 0 until MEL_FILTERS) {
            var sum = 0f
            val filt = mel[m]
            for (k in filt.indices) sum += filt[k] * power[k]
            logMel[m] = ln(max(sum, 1e-10f))
        }
        val out = FloatArray(COEFFS)
        for (i in 0 until COEFFS) {
            var sum = 0f
            for (m in 0 until MEL_FILTERS) sum += logMel[m] * cos(PI.toFloat() * i * (m + 0.5f) / MEL_FILTERS)
            out[i] = sum
        }
        return out
    }

    private fun powerSpectrum(frame: FloatArray): FloatArray {
        val re = frame.copyOf(FRAME)
        val im = FloatArray(FRAME)
        fft(re, im)
        val half = FRAME / 2 + 1
        val p = FloatArray(half)
        for (k in 0 until half) p[k] = (re[k] * re[k] + im[k] * im[k]) / FRAME
        return p
    }

    /** FFT iterativa in loco (FRAME è potenza di 2). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2f * PI.toFloat() / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun hamming(n: Int): FloatArray =
        FloatArray(n) { 0.54f - 0.46f * cos(2f * PI.toFloat() * it / (n - 1)) }

    private fun melFilterbank(): Array<FloatArray> {
        val half = FRAME / 2 + 1
        val low = 0f
        val high = 2595f * kotlin.math.log10(1f + (SAMPLE_RATE / 2f) / 700f)
        val points = FloatArray(MEL_FILTERS + 2) { low + (high - low) * it / (MEL_FILTERS + 1) }
        val hz = FloatArray(points.size) { 700f * (Math.pow(10.0, (points[it] / 2595f).toDouble()).toFloat() - 1f) }
        val bin = IntArray(hz.size) { ((FRAME + 1) * hz[it] / SAMPLE_RATE).toInt().coerceIn(0, half - 1) }
        return Array(MEL_FILTERS) { m ->
            val f = FloatArray(half)
            val a = bin[m]; val b = bin[m + 1]; val c = bin[m + 2]
            for (k in a until b) if (b > a) f[k] = (k - a).toFloat() / (b - a)
            for (k in b until c) if (c > b) f[k] = (c - k).toFloat() / (c - b)
            f
        }
    }
}

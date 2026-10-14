package com.mialifeapp.core

/**
 * Riconosce una sequenza di colpi descritta come dimensioni di gruppi, es. [2,3,2]
 * = due colpi, pausa, tre colpi, pausa, due colpi.
 *
 * I valori di soglia sono volutamente conservativi e andranno tarati sul campo.
 */
class TapMatcher(
    private val target: List<Int>,
    private val groupGapMs: Long = 650L,   // pausa oltre la quale inizia un nuovo gruppo
    private val resetMs: Long = 4000L,     // silenzio dopo cui si azzera tutto
    private val debounceMs: Long = 120L    // intervallo minimo tra due colpi
) {
    private val taps = ArrayList<Long>()
    private var lastTap = 0L

    /** Ritorna true quando la sequenza completa è riconosciuta. */
    fun onTap(now: Long): Boolean {
        if (now - lastTap < debounceMs) return false
        if (taps.isNotEmpty() && now - taps.last() > resetMs) taps.clear()
        lastTap = now
        taps.add(now)

        if (taps.size > 60) taps.subList(0, taps.size - 60).clear()

        val groups = segment(taps)
        // Basta che gli ULTIMI gruppi combacino: un colpetto casuale prima (separato da una pausa)
        // non fa più fallire la sequenza.
        if (groups.size >= target.size && groups.takeLast(target.size) == target) {
            taps.clear()
            return true
        }
        return false
    }

    private fun segment(list: List<Long>): List<Int> {
        if (list.isEmpty()) return emptyList()
        val groups = ArrayList<Int>()
        var count = 1
        for (i in 1 until list.size) {
            if (list[i] - list[i - 1] <= groupGapMs) {
                count++
            } else {
                groups.add(count)
                count = 1
            }
        }
        groups.add(count)
        return groups
    }

    fun reset() { taps.clear(); lastTap = 0L }
}

/** Cattura i colpi dell'utente e ne ricava i gruppi, per registrare una sequenza personale. */
class TapRecorder(private val groupGapMs: Long = 650L) {
    private val taps = ArrayList<Long>()
    fun onTap(now: Long) { taps.add(now) }
    fun result(): List<Int> {
        if (taps.isEmpty()) return emptyList()
        val groups = ArrayList<Int>()
        var count = 1
        for (i in 1 until taps.size) {
            if (taps[i] - taps[i - 1] <= groupGapMs) count++ else { groups.add(count); count = 1 }
        }
        groups.add(count)
        return groups
    }
    fun totalTaps() = taps.size
    fun reset() = taps.clear()
}

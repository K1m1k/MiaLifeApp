package com.mialifeapp.core

/** Valori di funzionamento decisi nella specifica. */
object Config {
    /** Durata di ogni pezzo di registrazione audio. */
    const val CHUNK_MS = 15_000L

    /** Ogni quanto si mandano per email i pezzi pronti (X). */
    const val UPLOAD_EVERY_MS = 2 * 60_000L

    /** Ogni quanto si rileva la posizione e si manda l'SMS di aggiornamento (Y). */
    const val LOCATION_EVERY_MS = 5 * 60_000L

    /** Durata della registrazione nella "Prova completa". */
    const val TEST_RECORD_MS = 5_000L

    /** Sequenza predefinita: 2 colpetti, pausa, 3 colpetti, pausa, 2 colpetti. */
    val DEFAULT_PATTERN = listOf(2, 3, 2)

    // ---- Due fasi ----
    /** Durate selezionabili dell'allerta (minuti). */
    val ALERT_DURATIONS_MIN = listOf(30, 60, 120, 240, 480)
    const val ALERT_DEFAULT_MIN = 120

    /** Sequenze del tasto volume ("d" = giù, "u" = su) proposte nella configurazione. */
    val VOL_ALERT_CHOICES = linkedMapOf("dddd" to "Giù 4 volte", "ddddd" to "Giù 5 volte", "dudu" to "Giù, Su, Giù, Su")
    val VOL_HELP_CHOICES = linkedMapOf("uudd" to "Su, Su, Giù, Giù", "uuuuu" to "Su 5 volte", "uddu" to "Su, Giù, Giù, Su")
    const val VOL_ALERT_DEFAULT = "dddd"
    const val VOL_HELP_DEFAULT = "uudd"
    /** Tempo massimo per completare una sequenza dei tasti volume. */
    const val VOL_WINDOW_MS = 4_000L

    /** Pulsante Bluetooth: pressioni ravvicinate per chiedere aiuto, entro [BT_WINDOW_MS]. */
    const val BT_HELP_PRESSES = 3
    const val BT_WINDOW_MS = 3_000L

    // ---- Attivazione vocale ("Modalità appuntamento") ----
    /** Frequenza di campionamento del microfono per il riconoscimento vocale. */
    const val VOICE_SAMPLE_RATE = 16_000
    /** Quante volte registrare la frase in configurazione. */
    const val VOICE_ENROLL_COUNT = 5
    /** Durata massima di una registrazione della frase (ms). */
    const val VOICE_ENROLL_MAX_MS = 4_000L
    /** Soglia predefinita di somiglianza della voce (0..100). Tollerante per non bocciare la voce sotto stress. */
    const val VOICE_SPEAKER_THRESHOLD_DEFAULT = 55

}

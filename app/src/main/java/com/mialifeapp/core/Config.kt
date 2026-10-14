package com.mialifeapp.core

/** Valori di funzionamento decisi nella specifica. */
object Config {
    /** Durata di ogni pezzo di registrazione audio. */
    const val CHUNK_MS = 15_000L

    /** Ogni quanto si caricano su Google Drive i pezzi pronti (X). */
    const val UPLOAD_EVERY_MS = 2 * 60_000L

    /** Ogni quanto si rileva la posizione e si manda l'SMS di aggiornamento (Y). */
    const val LOCATION_EVERY_MS = 5 * 60_000L

    /** Durata della registrazione nella "Prova completa". */
    const val TEST_RECORD_MS = 5_000L

    /** Sequenza predefinita: 2 colpetti, pausa, 3 colpetti, pausa, 2 colpetti. */
    val DEFAULT_PATTERN = listOf(2, 3, 2)

    /** Nome della cartella sul Google Drive dell'utente. */
    const val DRIVE_FOLDER_NAME = "MiaLifeApp - Prove"

    // ---- Attivazione vocale ("Modalità appuntamento") ----
    /** Frequenza di campionamento del microfono per il riconoscimento vocale. */
    const val VOICE_SAMPLE_RATE = 16_000
    /** Quante volte registrare la frase in configurazione. */
    const val VOICE_ENROLL_COUNT = 5
    /** Durata massima di una registrazione della frase (ms). */
    const val VOICE_ENROLL_MAX_MS = 4_000L
    /** Soglia predefinita di somiglianza della voce (0..100). Tollerante per non bocciare la voce sotto stress. */
    const val VOICE_SPEAKER_THRESHOLD_DEFAULT = 55
    /** Durate selezionabili della Modalità appuntamento (minuti). */
    val APPOINTMENT_DURATIONS_MIN = listOf(60, 120, 240)
    /** Nome del modello vocale italiano (Vosk) scaricato sul telefono. */
    const val VOICE_MODEL_NAME = "vosk-model-small-it-0.22"
    const val VOICE_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
}

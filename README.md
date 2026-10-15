# MiaLifeApp

App Android di sicurezza personale, senza server. Funziona in **due fasi**:

- **A riposo** il telefono non fa nulla. Resta attivo solo il servizio di Accessibilità che ascolta i tasti.
- **Fase 1 – Allerta**: sequenza *Allerta* del tasto volume (anche dalla tasca) o pulsante nell'app.
  Nessun invio: si attivano colpetti sul retro e frase vocale. Finisce alla scadenza scelta oppure
  rifacendo la sequenza Allerta + PIN.
- **Fase 2 – Aiuto**: sequenza *Aiuto* del volume, colpetti, frase vocale (solo in allerta) o 3 pressioni
  di un pulsante Bluetooth (telecomando per selfie). Allora:
  1. parte subito un **SMS di aiuto con la posizione** ai contatti;
  2. inizia la **registrazione audio a pezzi da 15 s**;
  3. ogni **2 minuti** i pezzi vengono mandati **per email** alla casella dell'utente (e a 2 email di fiducia facoltative);
  4. ogni **5 minuti** si rileva di nuovo la posizione e si manda l'aggiornamento ai contatti;
  5. senza rete SMS ed email vanno in coda e ripartono appena torna la linea; la registrazione non si ferma.

Quando è fuori pericolo, l'utente apre l'app: vede uno **schermo nero**, tocca in basso, inserisce il
**PIN** e l'emergenza si ferma. I contatti ricevono "Sono al sicuro".

> ⚠️ Nessuna app è infallibile. MiaLifeApp può avere difetti o non funzionare su alcuni modelli di
> telefono. Non sostituisce il 112.

## Novità della versione 3.1
- **Email al posto di Google Drive**: niente Google Cloud. Nella configurazione si scrivono email e
  password (per Gmail/Yahoo/iCloud la "password per le app"); server e porta si compilano da soli.
  Invio sempre cifrato (porta 465 o 587 con STARTTLS). Outlook/Hotmail non sono supportati
  (Microsoft non consente più l'invio con password).
- **Passo "Tasti volume e pulsante Bluetooth"** nella configurazione: attivazione dell'Accessibilità,
  scelta delle sequenze Allerta/Aiuto, durata dell'allerta, prova che non invia nulla, registrazione del
  pulsante Bluetooth.
- **Frase vocale** ascoltata durante l'allerta (non più "Modalità appuntamento").
- **Fine allerta** a tempo oppure con la stessa sequenza + PIN.

---

## Novità della versione 2.0

| Problema della v1 | Soluzione |
|---|---|
| Due servizi: l'emergenza veniva avviata in background e Android poteva negare il microfono | **Un solo servizio** (`ProtectionService`) avviato a schermo acceso quando si tocca "Attiva protezione". Ascolto colpetti ed emergenza stanno nello stesso servizio. |
| Se la cifratura falliva, i dati finivano in chiaro | **Nessun ripiego in chiaro**: cifratura con Android Keystore (AES-256-GCM). Se non funziona, l'app si ferma con un messaggio chiaro. Rimossa la libreria deprecata `security-crypto`. |
| Dopo il riavvio si tentava di riavviare il microfono (vietato da Android 14) | Dopo il riavvio si riparte in **modalità ridotta** (colpetti + SMS + posizione) e una notifica chiede di toccare per riattivare tutto. |
| A schermo spento il processore dorme e i colpetti non arrivavano | Il servizio tiene sveglio il processore (wake lock) mentre la protezione è attiva. |
| Drive non collegato | **Google Drive integrato** nella prima configurazione, con caricamento di prova obbligatorio. |
| Nessuna protezione contro chi prende il telefono | **PIN** per fermare l'emergenza, disattivare o modificare. **PIN di coercizione**: sembra fermare tutto ma l'emergenza continua in segreto e i contatti vengono avvisati. |

Altre aggiunte: configurazione guidata in 8 passi con controllo di ogni passo, istruzioni per
Xiaomi/Huawei/Samsung/OPPO/vivo, scelta dei contatti dalla rubrica, schermata di stato con tutti i
controlli, **Prova completa** (posizione + microfono + Drive), controllo automatico ogni 15 minuti,
SMS di prova, nuovo logo e nuovi colori.

---

## 1. Aprire il progetto
Sul tuo computer, in **Android Studio**: `File › Open` e scegli la cartella `MiaLifeApp`.
Attendi la sincronizzazione Gradle (scarica anche la nuova libreria `play-services-auth`).
Se Android Studio propone di aggiornare AGP/Gradle puoi accettare.

## 2. Email delle prove
Non serve configurare nulla fuori dall'app. Per Gmail: account Google › Sicurezza › Verifica in due
passaggi attiva › "Password per le app" › crea "MiaLifeApp" e incolla le 16 lettere nell'app.
Nella versione di **debug** c'è un pulsante "Salta" nel passo Email, per provare il resto dell'app.

## 3. Installare sul telefono
- Sul telefono: `Impostazioni › Info telefono`, tocca 7 volte **Numero build**, poi in *Opzioni
  sviluppatore* attiva **Debug USB**.
- Collega il telefono e premi **Run ▶** in Android Studio.

## 4. Prova consigliata
1. Completa la guida usando come contatto **un tuo secondo numero**.
2. Nel passo Sequenza usa **Prova la sequenza** finché viene riconosciuta sempre.
3. Nella schermata principale tocca **Attiva protezione**, poi **Prova completa**.
4. In un posto tranquillo: schermo spento, telefono in tasca, batti la sequenza.
   Controlla che arrivi l'SMS e, entro 2 minuti, l'email con gli audio.
5. Apri l'app: schermo nero › tocca in basso › PIN › deve arrivare "Sono al sicuro".
6. Ripeti con il **PIN di coercizione**: l'app deve sembrare spenta ma i contatti ricevono l'avviso
   e la registrazione continua. Per chiudere davvero: *Modifica configurazione* o *Disattiva* con il PIN vero.
7. Riavvia il telefono: deve comparire la notifica "Protezione da riattivare".

---

## Struttura
- `core/ProtectionService` – unico servizio: stato PRONTO (ascolto) ed EMERGENZA.
- `core/TapListener`, `core/TapPattern` – rilevamento colpetti e riconoscimento sequenza.
- `core/AudioChunkRecorder`, `core/Evidence` – registrazione a pezzi e cartella prove (privata).
- `core/mail/*` – invio SMTP cifrato, server noti, invio delle prove e invio di riserva.
- `core/VolumeKeyService` – tasti volume e pulsante Bluetooth (Accessibilità).
- `core/SmsHelper`, `SmsResultReceiver`, `SmsRetryWorker` – SMS con coda e ritentativi.
- `core/Crypto`, `core/SecurePrefs`, `core/Pin` – dati solo cifrati, PIN salvati come impronta.
- `core/EmergencyControl` – fine emergenza con PIN e coercizione.
- `core/BootReceiver`, `core/WatchdogWorker` – riavvio telefono e controllo ogni 15 minuti.
- `core/BatteryHelper` – risparmio energetico e impostazioni dei produttori.
- `ui/WelcomeActivity`, `ui/SetupActivity`, `ui/MainActivity`, `ui/EmergencyActivity`, `ui/FullTest`.

Valori modificabili in `core/Config.kt` (durata pezzi audio, intervallo email, intervallo posizione, sequenze, durate allerta).

## Limiti tecnici (onesti)
- Da Android 12 compare il **pallino verde** quando il microfono registra: si vede solo a schermo acceso.
- Android obbliga a mostrare una **notifica** per il servizio attivo: è resa neutra ("Servizio attivo")
  e identica in stato pronto e in emergenza.
- **Dopo un riavvio** Android non concede il microfono finché l'app non viene aperta: serve un tocco.
- Ascoltare i colpetti a schermo spento **consuma un po' più batteria**.
- Nessuna app può impedire di **spegnere il telefono** o togliere la SIM: per questo l'SMS di aiuto
  parte subito e ogni pezzo audio viene caricato appena possibile.
- Per la **pubblicazione su Google Play** servono: moduli di dichiarazione per SMS, posizione in
  background e servizi in primo piano; e un `targetSdk` aggiornato a quello richiesto da Google al
  momento della pubblicazione.

## Attivazione vocale
- **Nessun download e nessun vocabolario**: l'utente registra la sua frase 5 volte; l'app la riconosce
  confrontando l'audio (impronta MFCC con allineamento nel tempo) con tutte le registrazioni.
  Il confronto tiene conto insieme della frase e della voce di chi la dice.
- Si ascolta **solo durante l'allerta** (Fase 1): microfono acceso, pallino verde, più batteria.
- Nel passo "Attivazione vocale" c'è una prova che mostra la somiglianza in % e se "scatterebbe";
  la soglia è regolabile e va tarata sul telefono, anche in tasca e con rumore.

## Xiaomi / Redmi / POCO: SMS in background
HyperOS/MIUI blocca gli SMS inviati dalle app in background se il permesso non è su "Consenti".
Impostazioni › App › MiaLifeApp › Autorizzazioni: **SMS → Consenti**; in "Altre autorizzazioni"
consentire gli SMS e "Mostra finestre pop-up in background". La guida dell'app lo ricorda.

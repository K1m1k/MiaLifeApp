# MiaLifeApp

App Android di sicurezza personale. Chi la usa configura contatti di fiducia, un PIN e una sequenza
di colpetti sul retro del telefono. Quando batte la sequenza (schermo spento, nessun segnale):

1. parte subito un **SMS di aiuto con la posizione** ai contatti;
2. inizia la **registrazione audio a pezzi da 15 s**;
3. ogni **2 minuti** i pezzi vengono caricati sul **Google Drive dell'utente** (cartella `MiaLifeApp - Prove`);
4. ogni **5 minuti** si rileva di nuovo la posizione e si manda l'aggiornamento ai contatti;
5. senza rete SMS e caricamenti vanno in coda e ripartono appena torna la linea; la registrazione non si ferma.

Quando è fuori pericolo, l'utente apre l'app: vede uno **schermo nero**, tocca in basso, inserisce il
**PIN** e l'emergenza si ferma. I contatti ricevono "Sono al sicuro" e l'app torna **pronta**.

> ⚠️ Nessuna app è infallibile. MiaLifeApp può avere difetti o non funzionare su alcuni modelli di
> telefono. Non sostituisce il 112.

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

## 2. Collegare Google Drive (obbligatorio, una volta sola)
Serve perché Google permetta all'app di caricare file sul Drive dell'utente. Si fa sul sito
**https://console.cloud.google.com** con il tuo account Google.

1. **Crea un progetto** (in alto, selettore progetti › *Nuovo progetto*), nome per esempio `MiaLifeApp`.
2. **Attiva l'API di Drive**: menu *API e servizi › Libreria*, cerca **Google Drive API** › *Abilita*.
3. **Schermata di consenso OAuth**: *API e servizi › Schermata consenso OAuth*
   - tipo utente **Esterno**, nome app `MiaLifeApp`, la tua email come contatto;
   - ambiti: aggiungi `.../auth/drive.file` (è un ambito "non sensibile");
   - **Utenti di prova**: aggiungi gli account Google tuoi e dei volontari.
4. **Impronta SHA-1 della chiave di firma**. Nel **terminale di Android Studio** (scheda *Terminal*
   in basso, già posizionato nella cartella del progetto) esegui:
   ```
   ./gradlew signingReport
   ```
   Su Windows: `gradlew signingReport`. Copia il valore **SHA1** della variante `debug`.
5. **Credenziale Android**: *API e servizi › Credenziali › Crea credenziali › ID client OAuth*
   - tipo **Android**, nome pacchetto `com.mialifeapp`, incolla lo **SHA-1** del punto 4.
   - Quando firmerai la versione definitiva (release) aggiungi un secondo ID client con lo SHA-1 della chiave release.

Non serve copiare nessun codice nell'app: Google riconosce l'app da nome pacchetto + SHA-1.
Se nella guida compare "codice 10", lo SHA-1 o il nome pacchetto non corrispondono.

> Finché l'app su Google Cloud è in modalità **"Test"**, solo gli utenti di prova possono collegarsi.
> Per tutti gli altri va pubblicata (*Pubblica app*); con il solo ambito `drive.file` non serve la
> verifica approfondita di Google.

Nella versione di **debug** c'è un pulsante "Salta" nel passo Drive, per provare il resto dell'app
anche prima di configurare Google Cloud. Nella versione release non c'è.

## 3. Installare sul telefono
- Sul telefono: `Impostazioni › Info telefono`, tocca 7 volte **Numero build**, poi in *Opzioni
  sviluppatore* attiva **Debug USB**.
- Collega il telefono e premi **Run ▶** in Android Studio.

## 4. Prova consigliata
1. Completa la guida usando come contatto **un tuo secondo numero**.
2. Nel passo Sequenza usa **Prova la sequenza** finché viene riconosciuta sempre.
3. Nella schermata principale tocca **Attiva protezione**, poi **Prova completa**.
4. In un posto tranquillo: schermo spento, telefono in tasca, batti la sequenza.
   Controlla che arrivi l'SMS e che su Drive compaiano i file nella cartella `MiaLifeApp - Prove`.
5. Apri l'app: schermo nero › tocca in basso › PIN › deve arrivare "Sono al sicuro".
6. Ripeti con il **PIN di coercizione**: l'app deve sembrare spenta ma i contatti ricevono l'avviso
   e la registrazione continua. Per chiudere davvero: *Modifica configurazione* o *Disattiva* con il PIN vero.
7. Riavvia il telefono: deve comparire la notifica "Protezione da riattivare".

---

## Struttura
- `core/ProtectionService` – unico servizio: stato PRONTO (ascolto) ed EMERGENZA.
- `core/TapListener`, `core/TapPattern` – rilevamento colpetti e riconoscimento sequenza.
- `core/AudioChunkRecorder`, `core/Evidence` – registrazione a pezzi e cartella prove (privata).
- `core/drive/*` – autorizzazione Google, caricamento su Drive, caricamento di riserva.
- `core/SmsHelper`, `SmsResultReceiver`, `SmsRetryWorker` – SMS con coda e ritentativi.
- `core/Crypto`, `core/SecurePrefs`, `core/Pin` – dati solo cifrati, PIN salvati come impronta.
- `core/EmergencyControl` – fine emergenza con PIN e coercizione.
- `core/BootReceiver`, `core/WatchdogWorker` – riavvio telefono e controllo ogni 15 minuti.
- `core/BatteryHelper` – risparmio energetico e impostazioni dei produttori.
- `ui/WelcomeActivity`, `ui/SetupActivity`, `ui/MainActivity`, `ui/EmergencyActivity`, `ui/FullTest`.

Valori modificabili in `core/Config.kt` (durata pezzi audio, intervallo Drive, intervallo posizione).

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

## Attivazione vocale — "Modalità appuntamento" (sperimentale, v2.4)
Oltre ai colpetti, l'app può attivare l'aiuto con una **frase scelta dall'utente**, verificando
anche che sia **la sua voce** a dirla.

- Riconoscimento **tutto sul telefono** (Vosk), offline. Il modello italiano (~50 MB) si scarica
  una volta sola in configurazione (serve rete solo in quel momento).
- La frase si registra **5 volte**: l'app impara la frase (vocabolario limitato) e un'impronta MFCC
  della voce. In emergenza confronta frase + voce; parte solo se entrambe corrispondono.
- Va accesa **a tempo** dalla schermata principale ("Modalità appuntamento": 1, 2 o 4 ore), perché
  tiene il microfono acceso: compare il **pallino verde** e consuma più batteria. Allo scadere si
  spegne da sola; i colpetti restano sempre attivi.
- Nel passo "Attivazione vocale" c'è una **prova** che mostra i due valori ("frase: sì · voce: 78%")
  e una **severità voce** regolabile: va tarata sul telefono, anche con il telefono in tasca.

> ⚠️ Funzione nuova e NON testata su dispositivo in questa consegna: va provata e tarata sul campo
> prima di affidarcisi. In caso di dubbio, i colpetti restano il metodo principale.

Dipendenze aggiunte: `com.alphacephei:vosk-android:0.3.47`, `net.java.dev.jna:jna:5.13.0@aar`.


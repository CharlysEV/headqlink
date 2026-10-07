# HeadQLink · Manuale utente

**Lingue:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · [English](MANUAL.en.md) · **Italiano**

Manuale per chi guida, per la versione **0.2** di HeadQLink (fork [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink)).
I nomi di pulsanti e menu appaiono «tra virgolette», esattamente come li mostra l'app in italiano.

> [!WARNING]
> Fai tutta la configurazione **con l'auto parcheggiata**. Non usare il telefono mentre guidi e non guardare video, TV,
> pagine web o giochi sullo schermo dell'auto se non è ferma.

**Riepilogo rapido**

1. Installa l'APK da [GitHub Releases](https://github.com/CharlysEV/headqlink/releases).
2. Apri HeadQLink e segui la procedura guidata in 3 passi finché la «Verifica requisiti» non dice «Tutto pronto».
3. In auto: attiva l'hotspot del telefono, apri l'app di mirroring sullo schermo dell'auto e tocca «Connetti».
4. Quando compare Android Auto, blocca il telefono e mettilo via.

## Indice

1. [Cos'è e cosa ti serve](#1-cosè-e-cosa-ti-serve)
2. [Installazione](#2-installazione)
3. [Primo avvio](#3-primo-avvio)
4. [Uso in auto](#4-uso-in-auto)
5. [Impostazioni utili](#5-impostazioni-utili)
6. [Calore e batteria](#6-calore-e-batteria)
7. [Risoluzione dei problemi](#7-risoluzione-dei-problemi)
8. [Esportare il log per chiedere aiuto](#8-esportare-il-log-per-chiedere-aiuto)
9. [Sicurezza, privacy e note legali](#9-sicurezza-privacy-e-note-legali)

---

## 1. Cos'è e cosa ti serve

HeadQLink porta **Android Auto** sullo schermo della **Leapmotor C10** usando la connessione di mirroring che l'auto ha
già (quella della sua app QDLink/SSPLink). Non serve nessun adattatore né cavo: il telefono e l'auto si parlano via
Wi-Fi, e il telefono può restare **bloccato e con lo schermo spento**. Non serve il root.

Ci sono due modalità:

| Modalità | Cosa vedi in auto |
|---|---|
| «Auto» (consigliata) | Android Auto a schermo intero: mappe, musica e messaggi. È la più leggera per il telefono e per il collegamento. |
| «Auto esteso» | Lo stesso, più un pannello proprio a sinistra con più informazioni sull'auto e sul viaggio (percorso, guida, viaggi, strumenti, efficienza) e più funzioni (foto, video, web, TV, radio, giochi). |

**Cosa ti serve**

| | |
|---|---|
| Auto | Leapmotor C10 con la sua app di mirroring (proiezione del telefono) sullo schermo. |
| Telefono | Android con **Android Auto** installato. È consigliato **Android 10 o successivo**; per scegliere la lingua dell'app serve Android 13. |
| Provato su | Samsung Galaxy **S25 Ultra** con **Android 16** e Android Auto 17.7. Altri telefoni potrebbero funzionare, ma non sono stati provati. |
| Android Auto 17.4 o successivo | Devi attivare una volta la **modalità sviluppatore** di Android Auto e il servizio di **accessibilità** di HeadQLink. L'app ti guida (sezione 3). Con le versioni precedenti non serve nessuna delle due. |
| Internet (facoltativo) | Solo per le funzioni di «Auto esteso» come percorsi, meteo o radio. |

> [!NOTE]
> **Stato dei test.** HeadQLink è un progetto personale e sperimentale. In un viaggio reale con una C10, un S25 Ultra e
> la connessione «Hotspot del telefono», Android Auto ha funzionato in due sessioni di circa 16 minuti ciascuna. In quel
> test il telefono si è scaldato troppo, quindi questa versione aggiunge il profilo immagine «Veicolo», lo spegnimento
> dello schermo del telefono e l'adattamento al calore (sezione 6). La connessione Wi-Fi Direct con il motore QDAuto non
> è ancora stata provata in auto.

---

## 2. Installazione

HeadQLink non è su Google Play: si installa da un file APK.

### Installare

1. Sul telefono, apri [github.com/CharlysEV/headqlink/releases](https://github.com/CharlysEV/headqlink/releases).
2. Nella release più recente, espandi **Assets** e tocca il file `.apk` (qualcosa come `com.headqlink.app_0.2….apk`).
3. Apri il file scaricato (dalla notifica o dalla cartella Download del tuo gestore file).
4. Android dirà che non può installare app sconosciute da quella fonte. Tocca **Impostazioni**, attiva **Consenti da
   questa fonte** e torna indietro.
5. Tocca **Installa**.

<!-- schermata: avviso di Android «installa app sconosciute» -->

> [!TIP]
> **Avviso di Play Protect.** Siccome l'app non arriva da Google Play, Play Protect potrebbe avvisarti che è sconosciuta
> o bloccarla. Se ti fidi di questo progetto, tocca **Altri dettagli › Installa comunque**.
>
> **Samsung:** se è attivo il **Blocco automatico** (Impostazioni › Sicurezza e privacy), non ti lascerà installare
> APK. Disattivalo per installare e, se vuoi, riattivalo dopo.

### Aggiornare

1. Scarica il nuovo APK dalla stessa pagina Releases.
2. Installalo sopra quello che hai: le tue impostazioni restano.
3. Apri HeadQLink e guarda la «Verifica requisiti»: **Android spesso disattiva l'accessibilità quando un'app si
   aggiorna**, e dovrai riattivarla.

> [!IMPORTANT]
> Puoi aggiornare solo con un APK **firmato con la stessa chiave**, cioè dalle Releases di questo fork. Se Android dice
> che l'app non è stata installata o che è in conflitto con un'altra, hai una versione con una firma diversa (per
> esempio l'HeadQLink originale di ryazrm, che usa lo stesso ID app, o una che hai compilato tu). Disinstallala prima;
> perderai le sue impostazioni.

### Disinstallare

1. Se non la userai più: nelle impostazioni di Android Auto puoi uscire dalla modalità sviluppatore (menu ⋮).
2. Impostazioni di Android › App › HeadQLink › **Disinstalla**.
3. I log esportati restano in Download/HeadQLink: eliminali a mano se non ti servono.

---

## 3. Primo avvio

La prima volta che apri HeadQLink compare una procedura guidata in 3 passi. Falla con l'auto parcheggiata; meglio
ancora dentro l'auto, con l'app di mirroring aperta sul suo schermo.

### Passo 1 di 3: benvenuto

La schermata «Il tuo telefono, sullo schermo della C10», con quello che serve «Prima di iniziare». Tocca «Inizia».

### Passo 2 di 3: modalità e connessione

<!-- schermata: passo 2 della procedura guidata, modalità e connessione -->

**«Cosa vuoi vedere in auto?»** Scegli «Auto esteso» o «Auto» (tabella della sezione 1).

> [!NOTE]
> **Privacy in «Auto esteso».** I suoi pannelli usano la posizione del telefono, se la consenti, e servizi internet
> aperti: OpenStreetMap e OSRM per percorsi e colonnine, Open-Meteo per meteo e vento, e radio-browser.info per la
> radio. Per fare questi calcoli, quei servizi ricevono la tua posizione o la tua destinazione. Se non vuoi, scegli
> «Auto» o non concedere l'autorizzazione «Foto, video e posizione».

**«Connessione all'auto»**

| Opzione | Come funziona | Consiglio |
|---|---|---|
| «Hotspot del telefono» | L'auto si collega all'hotspot del tuo telefono. | **Consigliata**: è quella provata sulla C10, anche con lo schermo spento. |
| «Wi-Fi Direct» | L'auto crea la rete e il telefono si collega. L'hotspot del telefono deve essere spento. | È la connessione dell'HeadQLink originale. Con il motore QDAuto non è ancora stata provata in auto. |
| «Cavo USB» (etichetta «Sperimentale») | Il telefono si collega con un cavo alla porta USB dati dell'auto, senza Wi-Fi. | **Sperimentale**: funziona solo se l'auto mette il telefono in modalità accessorio, e non si sa ancora se la C10 lo fa. Vedi [Connessione via cavo USB (sperimentale)](#connessione-via-cavo-usb-sperimentale). |

> [!TIP]
> L'app seleziona «Hotspot del telefono» di default, con l'etichetta «Consigliato». Se preferisci «Wi-Fi Direct»,
> toccalo prima di toccare «Continua».

Tocca «Continua». Puoi cambiare la modalità e la connessione quando vuoi con il pulsante «Cambia» della schermata
principale.

### Passo 3 di 3: «Configura Auto esteso» (o «Configura Auto»)

Questa è la **verifica dei requisiti**: un elenco di tutto ciò che serve alla tua configurazione. È lo stesso elenco che
troverai poi nel menu ⚙ › «Verifica requisiti».

<!-- schermata: Verifica requisiti -->

**Come leggerla**

- In alto dice «Tutto pronto» (verde) o «Mancano N cose»: in rosso se manca qualcosa di obbligatorio, in ambra se manca
  solo qualcosa di consigliato.
- Ogni riga ha un'icona (✓ fatto, ! manca, ✕ errore, ? sconosciuto, … verifica in corso, i consiglio), la sua importanza
  («Obbligatorio», «Consigliato», «Facoltativo» o «Consiglio») e un **pulsante che ti porta all'impostazione esatta**.
- Si aggiorna da sola quando torni su HeadQLink.

**Riga per riga.** Compaiono solo le righe che contano per la tua modalità e la tua connessione.

| Riga | Cosa significa | Cosa toccare |
|---|---|---|
| «Android Auto» | Deve essere installato e attivo. Mostra la sua versione. | «Installa» (Play Store), o «Info app» se è disattivato. |
| «Accessibilità di HeadQLink» (Obbligatorio con Android Auto 17.4 o successivo; Facoltativo con l'avvio manuale) | HeadQLink la usa per avviare Android Auto senza che tu lo veda e per ricevere i tocchi dell'auto. Nelle impostazioni di Android si chiama **«HeadQLink touch»**. | «Attiva» e accendi «HeadQLink touch». Vedi la nota qui sotto. |
| «Senza accessibilità?» (Consiglio, solo se manca l'accessibilità) | Puoi avviare tu il server di Android Auto e lasciarla disattivata. | «Avvio manuale» (vedi [Senza accessibilità](#senza-accessibilità-avvio-manuale-del-server-di-android-auto)). |
| «Modalità sviluppatore di Android Auto» (Obbligatorio con 17.4 o successivo) | Android Auto accetta uno «schermo d'auto» dentro il telefono solo tramite la sua modalità sviluppatore. Si attiva una volta. | «Apri AA» e segui la guida qui sotto. «Verifica» la controlla (serve l'accessibilità attiva). |
| «Server di Android Auto» (Info, solo con l'avvio manuale) | Quello che si sa **senza connettersi al server** (una connessione di prova lo bloccherebbe): «In uso da HeadQLink», «Non risponde» (i tentativi con l'auto falliscono) oppure, se non si sa, come avviarlo. Non ti impedisce mai di connetterti: se non risponde, HeadQLink ti avvisa e riprova. | «Apri AA» (⋮ › «Arresta server unità principale» se compare e ⋮ › «Avvia server unità principale») o «Modalità automatica». |
| «Notifiche» (Consigliato) | Per vedere lo stato della connessione e gli avvisi. | «Consenti» (o «Apri» se le hai bloccate). |
| «Batteria senza restrizioni» (Consigliato) | Perché Android non chiuda HeadQLink a schermo spento né blocchi la connessione automatica. | «Consenti» e accetta la richiesta di Android. |
| «Samsung: app mai in sospensione» (Consiglio, solo Samsung) | Samsung chiude da sola le app in background. L'app non può verificarlo. | «Apri» e scegli «Senza restrizioni». Inoltre: Impostazioni › Batteria › Limiti utilizzo in background › App mai in sospensione › aggiungi HeadQLink. |
| «Gestione energia di …» (Consiglio, altre marche) | Xiaomi, Honor, Oppo e altre hanno un loro risparmio energetico. | «Apri» e consenti l'avvio automatico o l'attività in background. |
| «Hotspot del telefono» (Obbligatorio con «Hotspot del telefono») | L'hotspot deve essere acceso. Quando va bene dice «Hotspot attivo (…)». | «Apri» e attivalo. |
| «Banda a 5 GHz» (Consiglio) | L'app non può leggerlo. A 5 GHz funziona meglio, perché a 2,4 GHz la radio è condivisa con il Bluetooth dell'auto. | «Apri»: scegli 5 GHz e disattiva lo spegnimento automatico dell'hotspot. |
| «Dispositivi Wi-Fi nelle vicinanze» o «Posizione» (Obbligatorio con «Wi-Fi Direct») | Autorizzazione per collegarsi alla rete Wi-Fi Direct dell'auto («Posizione» su Android 12 o precedente). | «Consenti» (o «Apri» se l'hai negata). |
| «Wi-Fi attivo» (Obbligatorio con «Wi-Fi Direct») | Wi-Fi Direct ha bisogno del Wi-Fi acceso, anche se non sei connesso a nessuna rete. | «Apri». |
| «Hotspot spento» (Obbligatorio con «Wi-Fi Direct») | Wi-Fi Direct non funziona con l'hotspot acceso. | «Apri» e spegnilo. |
| «QDLink» (Consigliato) | Sul telefono è installata l'app ufficiale QDLink. Se è aperta, occupa la porta 18463 e l'auto non trova HeadQLink. | «Info app» › «Forza interruzione» prima di connetterti (sui Samsung il pulsante di Android si chiama «Forza arresto»). |
| «QDLink» o «Porta 18463» in rosso («Occupata») | QDLink (o un'altra app) è aperta in questo momento. | «Forza interruzione». |
| «Bluetooth (dispositivi nelle vicinanze)» (Obbligatorio se attivi la connessione automatica) | Per riconoscere il Bluetooth dell'auto e connettersi da sola. | «Consenti». |
| «Mostra sopra altre app» (Facoltativo) | Per aprire Android Auto con il telefono in background. | «Consenti». |
| «Foto, video e posizione» (Facoltativo, «Auto esteso») | Per la galleria e i pannelli di guida in auto. | «Consenti». |
| «Posizione sempre consentita» (Consigliato, «Auto esteso») | Perché i dati dell'auto continuino con il telefono bloccato (vedi [Cosa vedi e come si usa](#cosa-vedi-e-come-si-usa)). | «Apri»: spiega il perché e apre la pagina di Android; scegli «Consenti sempre». Se l'app non ha ancora la posizione, prima te la chiede. |

> [!IMPORTANT]
> **«Impostazione con limitazioni» quando attivi l'accessibilità.** Su Android 13 o successivo, Android non lascia che le
> app installate da un APK attivino subito l'accessibilità. Se quando accendi «HeadQLink touch» compare «Impostazione
> con limitazioni»:
>
> 1. Tocca il secondo pulsante della riga, «Info app» (oppure vai in Impostazioni › App › HeadQLink).
> 2. Tocca il menu ⋮ (in alto a destra) › **«Consenti impostazioni con limitazioni»** e conferma. Questa opzione compare
>    dopo che hai provato ad attivare il servizio almeno una volta.
> 3. Torna indietro, tocca «Attiva» e accendi «HeadQLink touch».
>
> Se la riga dice «Attiva ma non in funzione (Android l'ha fermata)», disattivala e riattivala.
>
> Preferisci saltare tutto questo? Vedi [Senza accessibilità](#senza-accessibilità-avvio-manuale-del-server-di-android-auto).

**Come attivare la modalità sviluppatore di Android Auto** (la stessa guida che mostra l'app):

1. Tocca «Apri AA». Se non compare, tocca prima «Verifica».
2. Scorri fino in fondo alle impostazioni di Android Auto.
3. Tocca **10 volte «Versione»** e accetta l'avviso.
4. Torna su HeadQLink: la verifica è automatica.

<!-- schermata: impostazioni di Android Auto con «Versione» in fondo -->

Con la connessione «Hotspot del telefono», sotto l'elenco compare anche «Come collegare l'auto all'hotspot» (sezione 4).

Tocca **«Termina»**. Se manca qualcosa di obbligatorio, l'avviso «Manca ancora» ti dice cosa: puoi tornare «Indietro» o
«Termina comunque» e completarlo più tardi.

> [!NOTE]
> Quando finisci la procedura guidata, e ogni volta che apri HeadQLink già configurata, **l'app inizia a connettersi da
> sola** (come se toccassi «Connetti») e aspetta l'auto fino a 5 minuti (o «Attesa dell'auto», se è di più). Con
> Android Auto 17.4 o successivo vedrai per un attimo la schermata «Avvio di Android Auto…»: non toccare nulla. Se non
> sei in auto, tocca «Disconnetti».

### Senza accessibilità (avvio manuale del server di Android Auto)

Se non vuoi (o non puoi) attivare l'accessibilità, scegli **«Impostazioni immagine» › «Avanzate ▾» › «Avvio del server
di Android Auto» › «Manuale (senza accessibilità)»**, oppure tocca «Avvio manuale» nella riga «Senza accessibilità?»
della «Verifica requisiti». Con «Auto» e «Auto esteso» HeadQLink allora non la usa più: l'accessibilità diventa
«Facoltativo» («Solo per la modalità automatica») e compare la riga «Server di Android Auto».

> [!IMPORTANT]
> **Il server di Android Auto continua a rispondere finché è acceso, purché le connessioni si chiudano in modo
> ordinato.** HeadQLink chiude sempre così le sue (Disconnetti, fine del viaggio, cambio del profilo immagine), quindi
> **non serve riavviarlo tra un viaggio e l'altro**. Quello che lo blocca è una connessione **interrotta a metà**
> (qualcosa che si connette e se ne va senza dire nulla, o una sessione che cade di colpo): da quel momento accetta
> connessioni ma non risponde a nessuno finché non lo fermi e lo riavvii. Per questo HeadQLink **non lo verifica prima
> di connettersi** (verificarlo vorrebbe dire proprio questo): lo dice il primo tentativo vero con l'auto.

1. Avvia tu il server: «Apri AA» › ⋮ (in alto a destra) › **«Avvia server unità principale»**. Se quell'opzione non
   c'è, attiva prima la modalità sviluppatore (10 tocchi su «Versione»). Se il menu dice «Arresta server unità
   principale», è già acceso: lascialo così. Può spegnersi quando il telefono si riavvia (o quando Android Auto si
   aggiorna): allora avvialo di nuovo.
2. Tocca «Connetti» (o lascia che parta da solo via Bluetooth o con il cavo). Quando l'auto si connette, HeadQLink lancia
   Android Auto e aspetta che **risponda** (al massimo 6 s). Con il server acceso funziona anche **con il telefono
   bloccato**: senza sbloccare e senza automatismi.
3. Se non risponde (spento, o bloccato da una connessione interrotta a metà), vedrai la notifica **«Avvia (o riavvia) il
   server di Android Auto»** e la riga «Auto» (e il widget) dirà «In attesa del server di Android Auto». Toccala:
   Android Auto › ⋮ › «Arresta server unità principale» (se compare) e ⋮ › «Avvia server unità principale», poi torna
   indietro. HeadQLink riprova da solo ogni 5 s finché l'auto è connessa; appena Android Auto risponde, la notifica
   sparisce da sola.
4. Tutto il resto funziona come con la modalità automatica: se l'auto se ne va, 30 s di video attivo e poi Android Auto
   in pausa; quando scade «Attesa dell'auto» (o con «Disconnetti») HeadQLink chiude Android Auto in modo ordinato.
   L'unica cosa che non fa è spegnere il server (senza accessibilità non può): **resta acceso, e il viaggio successivo
   lo riusa senza riavviarlo**, anche con il telefono bloccato, finché resta acceso.

Cosa cambia, e perché la modalità automatica resta quella **consigliata**:

- Devi avviarlo a mano ogni volta che è spento (per esempio dopo un riavvio del telefono) e, se a un certo punto si
  blocca, fermarlo e riavviarlo.
- **Resta acceso** finché non lo fermi tu, e ascolta su tutta la rete: su un **Wi-Fi pubblico**, qualsiasi dispositivo di
  quella rete potrebbe provare a connettersi (e, connettendosi e andandosene senza dire nulla, lasciarlo bloccato).
  Fermalo quando non lo userai.
- La modalità «App» (un'app specifica in auto) ha comunque bisogno dell'accessibilità per i tocchi.

> [!TIP]
> **Installare con Obtainium di solito evita il passaggio delle «impostazioni con limitazioni».** Obtainium installa
> tramite il programma di installazione a sessioni di Android e, su Android 13 e 14, di solito l'accessibilità si può
> attivare subito. Su Android 15 o successivo non è garantito.

### Versioni di Android Auto

Dalla 17.4, HeadQLink avvia Android Auto tramite il suo «server unità principale» per sviluppatori, e ogni nuova versione
di Android Auto può cambiarlo (i testi del suo menu ⋮ o il modo in cui gestisce la connessione). Versioni con cui è stato
provato:

| Android Auto | Stato |
|---|---|
| 17.7.x | **Provata** con HeadQLink (ottobre 2026) |
| 17.8, 17.9 e successive | **Non ancora provate.** Altri progetti (Open Headunit) segnalano problemi: con la 17.8 la connessione cade dopo 1-2 s; con la 17.9 l'avvio automatico del server smette di funzionare |

La «Verifica requisiti» lo indica nella riga «Android Auto 17.x» (provata / non ancora provata), e il log lo registra
all'avvio (`androidAuto=…`) e per ogni sessione (colonna `aa_version` di `sessions.csv`).

**Consigliato: disattiva l'aggiornamento automatico di Android Auto.** Play Store › cerca «Android Auto» › ⋮ (in alto a
destra) › togli la spunta da «Attiva aggiornamento automatico». Così una nuova versione non ti lascia l'auto senza
immagine da un giorno all'altro; aggiorna a mano quando sarà stata provata.

Se una nuova versione smette di funzionare:

1. **«HeadQLink non trova il pulsante del server»**: Android Auto ha cambiato il suo menu. Tocca «Apri AA» e avvialo a
   mano (⋮ › «Avvia server unità principale», o come si chiama adesso), oppure tocca «Avvio manuale» per non dipendere
   dall'accessibilità (vedi [Senza accessibilità](#senza-accessibilità-avvio-manuale-del-server-di-android-auto)).
2. **«Android Auto si connette e si disconnette dopo pochi secondi»** (due cadute di fila in meno di 10 s): svuota la
   cache di Android Auto (Impostazioni › App › Android Auto › Spazio di archiviazione › **Svuota cache**; non serve
   cancellare i dati) e ferma e riavvia il suo server (⋮). HeadQLink riprova da solo, al massimo ogni 10 s.
3. Se continua, **esporta il log** (sezione 8) e invialo: contiene la versione di Android Auto e, una volta, quello che
   HeadQLink ha visto nel suo menu (i testi del menu, senza dati personali), che è ciò che serve per adattarlo.

> [!WARNING]
> **Tornare a una versione precedente di Android Auto non è «Disinstalla aggiornamenti».** Su molti telefoni la copia di
> fabbrica di Android Auto è solo uno scheletro (1.x) che non serve a HeadQLink: devi installare a mano l'APK della
> versione provata (17.7.x) adatta al tuo telefono (architettura e versione di Android) e poi disattivare
> l'aggiornamento automatico, altrimenti il Play Store la aggiornerà di nuovo.

---

## 4. Uso in auto

### La schermata principale

<!-- schermata: schermata principale di HeadQLink -->

- In alto, la modalità e la connessione (per esempio «Auto esteso · Hotspot del telefono») con il pulsante «Cambia», e
  il menu ⚙.
- L'interruttore «Connetti al rilevamento del Bluetooth dell'auto».
- La scheda «Stato». In alto, il pannello «In diretta»: due numeri grandi, gli **fps** (fotogrammi al secondo) e i
  **Mbps** (Mbit/s) del video che arriva all'auto, ognuno con una barra che si riempie rispetto a quello che dà il tuo
  profilo immagine (30 fps con «Veicolo»). Il punto accanto a «In diretta» lampeggia mentre arriva il video; senza
  video, i numeri mostrano «—». Sotto, una riga per ogni parte della connessione:

| Riga | Cosa ti dice |
|---|---|
| «Veicolo» | «Disconnesso», «Ricerca…», «Trovato, connessione…», «Connesso» (con le dimensioni dello schermo dell'auto) o «Riconnessione… (Android Auto in attesa)». |
| «Rete» | «Hotspot attivo (…)», «Hotspot spento: attivalo», «Wi-Fi Direct: ricerca dell'auto» o «Wi-Fi Direct: nel gruppo dell'auto (…)». Se l'hotspot è spento, toccando la riga si aprono le sue impostazioni. |
| «Immagine» | Il video che si sta inviando all'auto. |
| «Auto» | Lo stato di Android Auto e, se non si avvia, il motivo (per esempio «Non si avvia: sblocca il telefono»). |
| «Requisiti» | «Tutto pronto» o «Mancano N cose». Toccala per aprire la «Verifica requisiti». |

- Un testo di aiuto e il grande pulsante «Connetti» / «Disconnetti».

### Passo per passo con «Hotspot del telefono» (consigliato)

1. **Attiva l'hotspot del telefono**, meglio a 5 GHz e senza spegnimento automatico.
2. **Solo la prima volta:** in auto, vai in Impostazioni › Wi-Fi e scegli l'hotspot del tuo telefono. Da lì in poi
   l'auto si collega da sola.
3. **Apri l'app di mirroring** sullo schermo dell'auto.
4. **Apri HeadQLink** con il telefono sbloccato. Inizia a connettersi da sola; se era già aperta e disconnessa, tocca
   «Connetti». Con Android Auto 17.4 o successivo vedrai per un attimo «Avvio di Android Auto…».
5. La riga «Veicolo» passa a «Trovato, connessione…» e poi a «Connesso». **Android Auto compare sullo schermo
   dell'auto.**
6. **Blocca il telefono** e lascialo in un posto fresco. L'immagine continua ad arrivare all'auto con lo schermo spento.

> [!CAUTION]
> Mentre l'hotspot è attivo, **l'auto potrebbe usare i tuoi dati mobili.**

**Con «Wi-Fi Direct»:** lascia l'hotspot spento e il Wi-Fi acceso, apri l'app di mirroring in auto e poi HeadQLink. La
riga «Rete» passa da «Wi-Fi Direct: ricerca dell'auto» a «Wi-Fi Direct: nel gruppo dell'auto (…)».

> [!IMPORTANT]
> Se sul telefono è installata QDLink, **forzane l'interruzione prima di connetterti**. Se è aperta, occupa la porta che
> serve a HeadQLink e l'auto non trova HeadQLink.

### Connessione via cavo USB (sperimentale)

> **Provata sulla C10 (2026-10-06): 5 minuti a 40 fps senza cadute**, e intanto il telefono si ricarica. Se l'auto non risponde entro pochi secondi, **scollega e ricollega il cavo**: l'auto parla solo nei primi secondi dopo il collegamento.

Come l'app originale dell'auto, HeadQLink può portare l'immagine **via cavo** invece che via Wi-Fi: l'auto mette il
telefono in «modalità accessorio» e la solita sessione passa dal cavo. **Non si sa ancora se la C10 lo fa** (l'app
originale dice che lo fanno solo alcuni modelli), quindi è sperimentale.

1. Usa un **cavo dati** (non uno solo di ricarica) e collegalo alla **porta USB dati** dell'auto (quella per la musica o
   per Android Auto/CarPlay, non una porta solo di ricarica).
2. Apri l'app di mirroring sullo schermo dell'auto.
3. Se l'auto mette il telefono in modalità accessorio, Android apre HeadQLink da solo. Se è installata anche **QDLink**,
   Android potrebbe chiedere **quale app apre «QDriveLink»**: scegli **HeadQLink** e **«Sempre»** (oppure disinstalla
   QDLink).
4. La riga «Rete» dice «Cavo USB: Neusoft QDriveLink 1» e l'auto si connette come al solito.

- **Non serve l'hotspot.** Con il cavo collegato, **il cavo ha la precedenza**: anche se è scelta un'altra connessione,
  HeadQLink mette in pausa il Wi-Fi e ci torna quando scolleghi il cavo. Per usare solo il cavo, scegli «Cavo USB» con
  «Cambia».
- Scollegare il cavo è come se l'auto se ne andasse (video attivo per 30 s, poi Android Auto in pausa); ricollegandolo
  riprende all'istante.

**Se non funziona**, esporta il log ([sezione 8](#8-esportare-il-log-per-chiedere-aiuto)) subito dopo aver collegato il
cavo e inviacelo, con l'ora. Cerchiamo le righe **`HQL/USB`**: cosa dice Android del cavo (`USB_STATE`), se l'auto ha
messo il telefono in modalità accessorio e con quale nome (produttore, modello e versione). Così sapremo se la C10 lo
supporta.

### Cosa vedi e come si usa

- **«Auto»:** Android Auto a schermo intero.
- **«Auto esteso»:** un pannello a sinistra, dal lato del guidatore, con «Auto», «Veicolo» (schede Percorso, Guida,
  Viaggi, Strumenti, Efficienza e Stato), «Foto», «Video», «Web», «TV», «Radio», «Giochi» e «Impostazioni». Con Android
  Auto sullo schermo, il pannello si nasconde da solo dopo qualche secondo; tocca il bordo sinistro per farlo
  ricomparire. Foto, video, web, TV e giochi sono **solo per quando l'auto è ferma**.
- **Sezione «Veicolo»** (stime dai sensori del telefono e da servizi aperti; con l'account Leapmotor, anche dati reali
  dell'auto: vedi [Dati reali dell'auto](#dati-reali-dellauto-account-leapmotor)):
  - «Percorso»: destinazione, arrivo, energia e consumo previsti; il profilo altimetrico colorato in base alla pendenza
    (in verde le discese, dove si recupera energia), il vento lungo il tragitto, le colonnine e la batteria prevista; la
    batteria all'arrivo in un anello (indica la tua % con «−5»/«+5»), il meteo a destinazione e le colonnine lungo il
    percorso («Vai» apre la navigazione di Google Maps).
    - **Cerca destinazione:** cerca mentre scrivi (quando ti fermi un attimo, da 3 lettere in su), con la ricerca di
      indirizzi di Android (quella di Google) e con Photon (OpenStreetMap) insieme: indirizzi con numero civico e luoghi
      («distributore», «supermercato»), ognuno con la sua distanza in km. Ogni tasto si illumina quando lo premi e
      quello che scrivi compare anche sopra la tastiera. Con la casella vuota compaiono le tue **destinazioni recenti**.
      «Parla» cerca quello che dici (il riconoscimento vocale del telefono; serve l'autorizzazione al microfono).
    - **Da dove viene la previsione:** sotto i riquadri, quanto salgono e scendono i km che mancano e quanto costano
      quelle salite («Il resto sale di 90 m e scende di 20 m: +0,5 kWh»). Con l'account Leapmotor, la linea tratteggiata
      sopra le barre è **la media della tua auto** (dal suo storico: vedi [Dati reali
      dell'auto](#dati-reali-dellauto-account-leapmotor)) e la previsione si adatta al tuo consumo reale; all'arrivo
      compare «previsti … · reali …».
    - **Filtrare le colonnine:** «Filtra» sceglie la potenza minima (qualsiasi, 22, 50, 100 o 150 kW) e gli operatori
      (Tesla, Zunder, Ionity, Iberdrola, Endesa X, Repsol, Wenea…, quelli presenti sul percorso, con quante colonnine ha
      ciascuno). La scelta viene salvata, e la seguono anche le icone a fulmine sul profilo. I dati vengono da
      OpenStreetMap: potenza o operatore possono mancare.
    - **Piano di ricarica (gratis, come ABRP):** se non arrivi con il margine, Percorso dice **dove fermarti e fino a
      quanto ricaricare** tra le colonnine del filtro: la migliore colonnina rapida dell'ultimo tratto che raggiungi e
      solo quello che serve (la C10 ricarica più lentamente sopra il 50 %: meglio due soste brevi che una lunga fino al
      100 %). Ogni sosta mostra il km, con quanto arrivi, fino a quanto ricaricare e i minuti; la linea della batteria
      sale a ogni sosta. «Vai con le soste» apre Google Maps con le soste (fino a 3). In «Filtra» scegli con quanto
      arrivare (10-30 %) e fino a quanto ricaricare al massimo (70-100 %). Su una REEV non c'è piano: il generatore
      mette quello che manca.
    - **Il piano si rifà da solo durante il viaggio:** con l'account Leapmotor confronta quanto scende davvero la
      batteria con quanto previsto per quei km e adatta il resto («Adattato al consumo di questo viaggio: +12 %»). Le
      soste scelte restano finché le raggiungi con margine; se stai restando senza batteria (o ne avanza), il piano cambia
      e **ti avvisa**: una scheda ambra nel pannello dell'auto (anche sopra Android Auto) con «Vai» (Google Maps con le
      nuove soste) e «Percorso», il pulsante «Veicolo» in ambra e **un avviso vocale** («Piano di ricarica cambiato. Stai
      consumando il 12 % in più del previsto. Nuova sosta: …»). La voce si disattiva in «Filtra» → «Avviso vocale se il
      piano cambia». Mentre ricarichi non avvisa (l'hai deciso tu).
  - «Guida»: la prossima manovra in grande, con una barra che si svuota fino alla svolta, le corsie e la manovra
    successiva; la velocità con il cartello del limite, la direzione, l'altitudine, la pendenza e il sole fino al
    tramonto.
  - «Viaggi»: il viaggio in corso e quelli salvati, con percorso, consumo, dislivello e costo; i km degli ultimi 14
    giorni e i record. **Tocca un viaggio** per aprirlo in grande: il percorso su una mappa (OpenStreetMap), la partenza,
    l'arrivo e **le soste** (2 minuti o più nello stesso posto, con durata, ora e km), e i suoi dati. Le soste sono note
    per i viaggi dalla 0.2.7 in poi.
  - «Strumenti»: tachimetro con il limite e la velocità massima, forze G con la scia degli ultimi secondi e i picchi,
    inclinazione e un punteggio di dolcezza di guida (0-100) in base agli strappi in accelerazione, frenata e sterzata.
  - «Efficienza»: la potenza degli ultimi 2 minuti (in verde quella recuperata), il consumo attuale, del viaggio e
    medio, dove va l'energia, il costo del viaggio, la CO₂ che non è uscita da un tubo di scarico e un consiglio.
  - «Stato» (con l'account Leapmotor): batteria, autonomia, ricarica, pressione dei pneumatici, porte e contachilometri
    reali dell'auto, con l'età del dato; le pressioni compaiono su **una C10 in 3D che ruoti con il dito** (porte e
    bagagliaio aperti si vedono aperti, in ambra). Senza account, spiega come configurarlo.
  - **Con il telefono bloccato: «Posizione sempre consentita».** Percorso, Guida, Viaggi, Strumenti ed Efficienza usano
    il GPS del telefono. Se la posizione di HeadQLink è solo «Consenti solo mentre l'app è in uso», Android le dà il GPS
    solo quando HeadQLink è sullo schermo o se il collegamento è passato in primo piano con HeadQLink davanti. Quando
    il collegamento parte senza HeadQLink in primo piano (la connessione automatica via Bluetooth, il widget, l'avvio di
    Android Auto…), bloccando il telefono lo schermo dell'auto e i sensori di movimento continuano, ma velocità,
    percorso, viaggio e consumo si fermano finché non sblocchi. HeadQLink lo recupera da solo appena lo vedi con il
    telefono sbloccato, ma per non dipendere da questo la «Verifica requisiti» consiglia **«Posizione sempre
    consentita»** in «Auto esteso»: «Apri» › «Consenti sempre» (oppure Impostazioni › App › HeadQLink › Autorizzazioni ›
    Posizione). In «Auto» non serve. HeadQLink usa il GPS solo con il collegamento attivo o con le schermate «Veicolo»
    aperte.
  - **Senza GPS, niente numeri congelati.** Dopo più di 5 s senza posizioni, la velocità mostra «—» e i pannelli dicono
    «GPS in pausa · telefono bloccato (consenti la posizione “sempre”)» (o «senza GPS», per esempio in galleria).
    Quando torna, il tratto senza posizioni viene aggiunto al viaggio in linea retta, alla sua velocità media, invece di
    contare come se l'auto fosse stata ferma.
- **Touchscreen:** funziona come in Android Auto, con il **multitouch** fino a 3 dita (per esempio, allarga o stringi
  due dita per zoomare sulla mappa).
- **Pulsanti al volante:** play/pausa, brano successivo e precedente funzionano tramite il **Bluetooth dell'auto**,
  senza altri abbinamenti.
- **Audio:** musica e indicazioni escono dal telefono tramite il **Bluetooth dell'auto**, quindi il telefono deve essere
  collegato come al solito. Le chiamate passano dal vivavoce dell'auto.

### Cadute e riconnessione automatica

- Se il collegamento con l'auto cade per un attimo, la riga «Veicolo» dice «Riconnessione… (Android Auto in attesa)» e
  l'immagine torna da sola, di solito in meno di un secondo, **senza riavviare Android Auto** (con il motore QDAuto,
  quello predefinito).
- Se l'auto **sparisce più a lungo** (per esempio perché la spegni per una breve sosta), dopo 30 secondi HeadQLink smette
  di inviare l'immagine e **mette in pausa Android Auto**, ma **resta in ascolto dell'auto** per il tempo di «Attesa
  dell'auto» (**5 minuti** di default; si cambia in «Impostazioni immagine» › «Avanzate»). La notifica dice «In attesa
  dell'auto · Android Auto in pausa». Se l'auto torna entro quel tempo, l'immagine ricompare **all'istante**, senza
  sbloccare il telefono né toccare nulla.
- Quando scade «Attesa dell'auto», HeadQLink chiude tutto: Android Auto, il suo server e la connessione. Per riusarla,
  tocca «Connetti», o lascia fare alla connessione automatica via Bluetooth. Con l'avvio manuale del server chiude tutto
  allo stesso modo tranne il server, che resta acceso per il prossimo viaggio ([Senza
  accessibilità](#senza-accessibilità-avvio-manuale-del-server-di-android-auto)).
- Se tocchi «Connetti» ed entro **5 minuti** (o «Attesa dell'auto», se è di più) non compare nessuna auto, HeadQLink si
  ferma da solo. Finché l'auto continua ad annunciarsi, anche se non riesce a connettersi, l'attesa ricomincia.

### Il video si adatta al collegamento

La radio tra il telefono e l'auto non sempre regge i 5 Mbit/s che chiede la C10: con l'auto lontana dal telefono, a
2,4 GHz o con interferenze, l'immagine andava a scatti e in ritardo. Ora, con i profili «Veicolo», «Automatico»,
«Medio» e «Molto basso» (quelli che ricodificano sul telefono):

- HeadQLink misura il collegamento dieci volte al secondo (dati in attesa di invio e tempo di andata e ritorno) e, se si
  intasa davvero (mezzo secondo di fila con dati accumulati o con il tempo di andata e ritorno alle stelle), **abbassa
  il bitrate** (×0,75 ogni volta, mai sotto la metà di quello che chiede l'auto: 2,5 Mbit/s sulla C10); se è ancora
  intasato, scende a **24 fps**. Quando il collegamento è pulito da 3 secondi torna su rapidamente (prima gli fps, poi
  +25 % di bitrate ogni 3 s): dal minimo a quello che chiede l'auto in circa 12 s. Le ritrasmissioni Wi-Fi occasionali
  non contano (sono normali).
- Quello che non entra nel collegamento viene scartato sul telefono prima che si accumuli ritardo (al massimo ~150 ms in
  coda).
- Se l'auto smette di leggere per un attimo ma continua a parlare (heartbeat, tocchi), la sessione resiste fino a
  **20 s** prima di darla per persa (prima erano 10 s), scartando il video vecchio e inviando un'immagine fresca appena
  può.

Nel log (sezione 8) compare come `enlace: congestión (outq 96 KB 300 ms, retrans +21) → bitrate 3.6 Mbit/s`,
`enlace: enlace limpio 5 s → bitrate 4.1 Mbit/s`, e nel riepilogo di ogni sessione `enlace: bitrate mín. 2.5 Mbit/s ·
congestiones 4`.

### Alla fine del viaggio

- Spegni l'auto (HeadQLink si chiude da solo quando scade «Attesa dell'auto», 5 minuti di default) o tocca
  «Disconnetti» per chiuderlo subito.
- Se il telefono era bloccato quando si è chiuso, vedrai la notifica «Android Auto in attesa finché non sblocchi il
  telefono (poi si chiude da solo)». **Sblocca il telefono una volta** perché si chiuda del tutto: vedrai per qualche
  secondo «Chiusura di Android Auto…» (sezione 9). Se torni in auto prima di sbloccarlo e usi la connessione automatica
  via Bluetooth, Android Auto torna all'istante, senza passare da «Chiusura di Android Auto…». Con l'avvio manuale non
  serve: Android Auto si chiude subito e il suo server resta acceso.
- «Chiusura di Android Auto…» e «Avvio di Android Auto…» durano qualche secondo. Se qualcosa li blocca, spariscono da
  soli dopo 15 s; se il server di Android Auto potrebbe essere rimasto acceso, vedrai «Il server di Android Auto è ancora
  attivo · Tocca per spegnerlo»: toccala con il telefono sbloccato.
- Spegni l'hotspot se non ti serve.

### Connessione automatica: «Connetti al rilevamento del Bluetooth dell'auto»

Con questo interruttore, HeadQLink inizia ad aspettare l'auto appena il telefono si connette al Bluetooth dell'auto,
senza che tu apra l'app.

1. Attivalo nella schermata principale.
2. Nella finestra «Connessione automatica», scrivi un testo contenuto nel **nome Bluetooth dell'auto** (quello
   predefinito è `Leapmotor_BT`; maiuscole e minuscole non contano) e tocca «Salva».
3. Concedi l'autorizzazione Bluetooth e togli le restrizioni di batteria quando te lo chiede. Altrimenti Android non la
   lascia avviarsi in background.

Da lì in poi:

- Se il Bluetooth si scollega prima che riesca a connettersi all'auto, HeadQLink si ferma. Se si scollega dopo una
  sessione (spegni l'auto), continua ad aspettare l'auto finché non scade «Attesa dell'auto».
- Se Android Auto era ancora in pausa (torni prima che scada «Attesa dell'auto», o era rimasto in attesa con il telefono
  bloccato), l'immagine torna all'istante, **senza sbloccare il telefono**.
- Se Android non la lascia avviarsi in background, vedrai la notifica «Auto rilevata · Tocca per connettere HeadQLink».
  Toccala.
- Se Android Auto deve avviarsi e il telefono è bloccato, vedrai la notifica **«Sblocca il telefono per avviare Android
  Auto»**. Sbloccalo (con l'auto ferma): HeadQLink lo avvia da solo, senza aprire l'app (vedrai per un attimo «Avvio di
  Android Auto…»).

### Widget e pulsante delle Impostazioni rapide

Per connetterti senza aprire l'app:

- **Widget «HeadQLink»** (schermata Home): menu ⚙ › «Aggiungi widget alla schermata Home», oppure tieni premuto un punto
  vuoto della schermata Home › Widget › HeadQLink. Occupa 4x2 e si può ridurre fino a 2x2.
  - Il **pulsante grande** fa lo stesso di «Connetti» / «Disconnetti» nell'app: se manca qualcosa di obbligatorio, apre
    la «Verifica requisiti». Il suo colore indica lo stato: grigio spento, ambra mentre cerca o aspetta l'auto, verde
    con l'immagine in auto (con fps e Mbit/s, aggiornati ogni 5 s) e rosso se c'è un problema (per esempio, QDLink
    aperta).
  - In basso, la **connessione**: «Hotspot», «Wi-Fi Direct» o «Cavo USB». Toccane un'altra per cambiarla; con HeadQLink
    in funzione cambia subito, senza riavviare Android Auto. Se il cavo dell'auto è collegato e in uso, mantiene la
    precedenza: la connessione scelta è quella a cui torna quando lo scolleghi.
  - In alto, la **modalità** («Auto» / «Esteso»). Con HeadQLink in funzione, cambiarla riconnette il video e Android
    Auto, come quando salvi un altro profilo immagine.
  - In formato 2x2 restano il pulsante, lo stato e l'icona della connessione: tocca l'icona per passare alla connessione
    successiva.
  - «HEADQLINK» apre l'app senza connettersi. Il widget si aggiorna solo quando cambia qualcosa: non consuma batteria in
    background.
- **Pulsante «HeadQLink» delle Impostazioni rapide** (Android 8+): su Android 13+, menu ⚙ › «Aggiungi pulsante a
  Impostazioni rapide»; altrimenti apri le Impostazioni rapide, tocca la matita (modifica) e trascinalo dentro. Toccalo
  per connettere o disconnettere (per connettere con il telefono bloccato, prima ti chiede di sbloccarlo); tienilo
  premuto per aprire l'app. Sotto mostra lo stato («Ricerca dell'auto…», «30 fps · 4,8 Mbit/s»…).

---

## 5. Impostazioni utili

È tutto nel menu ⚙ della schermata principale: «Verifica requisiti», «Impostazioni immagine», «Lista TV» e «Lista radio»
(solo in «Auto esteso»), «Lingua», «Aggiungi widget alla schermata Home», «Aggiungi pulsante a Impostazioni rapide»
(Android 13+) e «Diagnostica».

### «Impostazioni immagine»

<!-- schermata: Impostazioni immagine -->

**«Profilo immagine».** L'app segna come «Consigliato» quello più adatto al tuo telefono. Sui telefoni potenti è
«Veicolo».

| Profilo | Cosa fa |
|---|---|
| «Automatico (…)» | Segue quello consigliato per questo telefono. **La scelta migliore se non sei sicuro.** |
| «Veicolo» (consigliato) | Quello che chiede l'auto (sulla C10, risoluzione piena a 30 fps e circa 5 Mbit/s), senza sforzare il telefono. Il minimo di calore e di batteria. |
| «Molto alto» | Risoluzione piena a 60 fps con la latenza più bassa. Consuma di più. La C10 non mostra più di 30 fps. |
| «Alto» | Risoluzione piena a 60 fps senza sforzare il telefono: meno batteria e calore, un po' più di ritardo. |
| «Medio» | 720p a 45 fps costanti, ricodificato sul telefono. |
| «Base» | 720p a 30 fps, senza ricodifica: il più leggero per il telefono. In «Auto esteso» si comporta come «Medio». |
| «Molto basso» | 720p a 20 fps e bitrate basso: il minimo per batteria e connessione. |

Se sei connesso, salvando un altro profilo l'auto e Android Auto si riconnettono da soli in pochi secondi.

> [!TIP]
> Se in una versione precedente avevi scelto a mano «Molto alto», resta così. Passa ad «Automatico» o a «Veicolo»: è
> quello che evita che il telefono si surriscaldi (sezione 6).

**«Fluidità».** Conta solo con i profili «Veicolo» e «Automatico»:

- «30 fps · meno calore (consigliato)»: quello che chiede l'auto (30 fps e circa 5 Mbit/s sulla C10). È quello che
  scalda meno il telefono.
- «60 fps · massima fluidità»: 60 fps e da 8 a 12 Mbit/s, come l'HeadQLink originale (la C10 li accetta). L'immagine è
  più fluida, ma il telefono si scalda di più: se raggiunge lo stato termico «moderato», HeadQLink scende da solo a
  30 fps (sezione 6). Se sei connesso, salvando l'auto e Android Auto si riconnettono da soli; altrimenti vale per la
  sessione successiva.

**Altre opzioni di «Impostazioni immagine»:**

- «Nascondi il pannello laterale dopo qualche secondo (Auto esteso)»: attiva di default.
- «Mantieni acceso lo schermo del telefono (più calore e batteria; altrimenti si spegne come sempre e la proiezione
  continua)»: disattivata di default. **Lasciala disattivata** se non ti serve.
- «Protezione dal calore»: cosa fa HeadQLink quando Android segnala che il telefono si sta scaldando (sezione 6).
  - «Normale (consigliata)»: scende a 30 fps a «moderato» (solo se la sessione va a 60), a 24 fps a «grave» e a 20 fps
    a «critico», sempre con meno bitrate.
  - «Leggera»: abbassa solo il bitrate; gli fps non scendono mai sotto 30 tranne a «critico» (20 fps). Per chi preferisce
    la fluidità anche se il telefono si scalda di più.
  - «Disattivata»: non cambia nulla, lo annota solo nel log.

  Si applica subito, senza riconnettere.
- «Avanzate ▾» › «Motore del protocollo»:
  - «QDAuto (consigliato)»: il motore predefinito, con riconnessione senza riavviare Android Auto e adattamento al
    calore.
  - «headqlink originale»: il motore dell'HeadQLink originale, come **piano B** se QDAuto ti dà problemi.

  La modifica «Si applica alla prossima connessione»: tocca «Disconnetti» e poi «Connetti».
- «Avanzate ▾» › «Attesa dell'auto»: «1 min», «5 min (consigliato)» o «15 min». Quanto tempo HeadQLink resta in ascolto
  dell'auto, con Android Auto in pausa, dopo averla persa (sezione 4). Più lungo = torna all'istante anche dopo soste
  più lunghe; più corto = il server di Android Auto si spegne prima. Vale dalla sosta successiva.
- «Avanzate ▾» › «Avvio del server di Android Auto»: «Automatico (accessibilità) · Consigliato» o «Manuale (senza
  accessibilità)» (sezione 3, [Senza accessibilità](#senza-accessibilità-avvio-manuale-del-server-di-android-auto)).
  Si applica subito.
- Il resto di «Avanzate» (fps, kbps, larghezza, altezza, profilo H.264, ottimizzazioni di latenza, freno video) serve per
  i test. Lascialo vuoto o com'è.

### Impostazioni dall'auto («Auto esteso»)

Il pulsante «Impostazioni» del pannello dell'auto permette di cambiare il profilo immagine e la «Fluidità» («Applica ·
si riconnette in pochi secondi»), l'occultamento automatico del pannello e le ottimizzazioni di latenza, il «Prezzo
dell'elettricità» (€/kWh, per il costo dei viaggi; 0,20 di default), e di vedere la connessione e il motore in uso. Usalo
solo con l'auto ferma.

### «Lingua»

«Lingua» (Android 13 o successivo): «Lingua del sistema», «Español», «English», «Português (Portugal)», «Português
(Brasil)», «Italiano», «Français», «Deutsch» o «Nederlands». L'interfaccia dell'auto cambia alla connessione successiva.
Su Android 12 o precedente, l'app usa la lingua del sistema.

### «Lista TV» e «Lista radio» («Auto esteso»)

Incolla l'URL di una lista M3U o tocca «Scegli file». Senza una lista radio, l'auto mostra le stazioni popolari.

### «Diagnostica»

- «Esporta log»: sezione 8.
- «Test senza Android Auto (immagine di prova)»: mostra in auto un'immagine di prova al posto di Android Auto. Ti dice
  se il problema è la connessione o Android Auto. **Disattivalo dopo.**
- «Opzioni di test (QDAuto)»: per provare il motore. Di norma lasciale come sono. Una di esse è «Video attivo senza
  l'auto, in secondi (5-600; vuoto = 30)».
- «Anteprima della modalità estesa»: il pannello dell'auto sul telefono (in orizzontale) con un viaggio dimostrativo;
  toccalo come in auto. Non si salva nulla, e non è disponibile mentre sei connesso all'auto.

### Dati reali dell'auto (account Leapmotor)

**Cos'è.** Facoltativo. Con il tuo account Leapmotor, HeadQLink legge lo stato della tua auto dal cloud di Leapmotor
(batteria e autonomia, ricarica, pressione dei pneumatici, porte, bagagliaio e chiusura, temperature e contachilometri)
e lo usa in «Auto esteso»:

- **«Stato»** (la sesta scheda di «Veicolo»): la batteria in un anello con l'autonomia e i kWh rimasti; la ricarica (AC
  o rapida DC, con la potenza e il tempo che manca) oppure, con il cavo scollegato, la potenza che esce dalla batteria o
  che vi entra; la temperatura della batteria (con «Batteria fredda: ricarica rapida più lenta e meno rigenerazione»
  sotto i 10 °C); le quattro pressioni su **una C10 in 3D** che ruoti con il dito, in ambra lo pneumatico basso (sotto
  2,1 bar, 0,3 bar o più sotto gli altri, o con l'avviso dell'auto stessa); porte e bagagliaio aperti (aperti anche nel
  modello), la chiusura, il contachilometri e quanto è vecchio il dato. Il cloud non indica i finestrini. La vista 3D si
  ridisegna solo mentre la muovi (non scalda il telefono); senza WebGL compare l'auto vista dall'alto.
- **C10 REEV (range extender):** HeadQLink la riconosce da solo (l'auto comunica il suo serbatoio) e porta la batteria a
  28,4 kWh. In «Stato», il **serbatoio** (%, litri esatti, autonomia a benzina e autonomia totale); in «Viaggi», i
  **litri** di ogni viaggio e i L/100 km (con il generatore spento, «0 L di benzina: tutto elettrico»), e il costo
  compresa la benzina; in «Percorso», la batteria non scende sotto il 20 % (lì entra in funzione il generatore) e indica
  quanti litri di benzina userà il generatore per arrivare.
- **«Percorso»**: la % attuale è quella reale («−5»/«+5» spariscono) e la batteria all'arrivo si calcola da essa con la
  capacità della tua versione. La **previsione del consumo** si adatta alla tua auto: parte dal consumo reale dei tuoi
  viaggi (il suo storico) e si affina con i percorsi che completi (all'arrivo confronta la previsione con quanto è scesa
  la batteria). La parte delle salite è fisica e non viene toccata: se quello che manca sale, la previsione sale anche
  se la tua media è più bassa.
- **«Efficienza»**: il consumo del viaggio è quello reale (quanto è scesa la batteria per la sua capacità, diviso i km
  del contachilometri) appena la batteria è scesa del 2 %; prima dice «consumo ancora troppo basso per misurarlo».
  Accanto alla potenza stimata compare quella reale quando il dato è recente. La ripartizione dell'energia resta
  stimata.
- **«Viaggi»**: il consumo di ogni viaggio è **quello misurato dall'auto stessa**, dal suo storico (segnato «AUTO»; lo
  stesso dell'app ufficiale Leapmotor) oppure, se manca, quello reale dal calo della % (segnato «REALE»); in «Record»,
  il totale degli ultimi 30 giorni secondo l'auto.
- **Storico dell'auto:** il cloud conserva i tuoi viaggi delle ultime settimane con i kWh (e su una REEV i litri)
  misurati dall'auto stessa, e il suo consumo medio settimanale. HeadQLink lo legge al massimo ogni 30 minuti (3 e 12
  minuti dopo la fine di un viaggio) e la media settimanale ogni 12 ore.

Ogni dato dice da dove viene: «reale · 40 s fa» (l'età del dato: il cloud non è in tempo reale e, con l'auto spenta, dà
l'ultimo che conosceva) o «stimato».

**Come configurarlo** (⚙ › «Dati dell'auto (account Leapmotor)», Android 6 o successivo):

1. **Certificato client.** Lo stesso che chiede l'app LMB10; HeadQLink non lo include né lo fornisce. Tocca «Importa
   certificato…» e scegli app.crt e app.key nel selettore di file: tutti e due insieme (pressione prolungata per
   selezionarne due) o **uno dopo l'altro** (ti dice quale manca ancora; «Ricomincia» dimentica una scelta a metà), un
   .pem con entrambi i blocchi o un .p12/.pfx (se ha una password, te la chiede).
2. **Account.** La tua email e la tua password di Leapmotor, poi «Accedi». La password non viene salvata.
3. **Auto.** Se l'account ne ha una, viene scelta; se ne ha più di una, scegli la tua.
4. **Batteria.** Il cloud non indica la versione: «C10 Life · 69,9 kWh», «C10 ProMax · 81,9 kWh»,
   «C10 REEV · 28,4 kWh + benzina» (scelta da sola se l'auto ha un serbatoio) o «Altra» (kWh a mano). Serve per
   convertire la % in kWh.
5. **«Leggi lo stato ora»** per verificarlo: batteria, autonomia, ricarica, pressioni e l'età del dato.

Con «Auto esteso» in funzione (o con l'«Anteprima della modalità estesa»), HeadQLink legge l'auto ogni 2 minuti (90 s con
la sezione «Veicolo» sullo schermo, come LMB10); se l'auto non ha caricato niente di nuovo (parcheggiata o in standby),
ogni 5 e poi ogni 15 minuti; dopo un errore, a 2, 5 e 10 minuti; e al massimo 400 letture al giorno (storico compreso),
per non abusare di un'API non ufficiale. Si ferma quando ti disconnetti. «Usa i dati reali dell'auto» lo disattiva senza
cancellare nulla.

**Privacy e sicurezza.**

- Sola lettura: HeadQLink **non invia mai comandi all'auto** (niente chiusura, clima, ricarica, nulla).
- I tuoi dati vanno **solo ai server di Leapmotor**. La posizione dell'auto non viene né letta né salvata.
- Il certificato, la sessione e lo storico dei viaggi vengono salvati **cifrati** con una chiave dell'Android Keystore,
  fuori dai backup. La password non viene salvata: se la sessione scade, «Stato» te lo dice e accedi di nuovo dal
  telefono.
- Il server di Leapmotor usa un certificato della sua stessa autorità. HeadQLink verifica che la sua chiave sia quella
  nota; se un giorno cambia, non si connette finché non la accetti sul telefono (fallo solo su una rete di cui ti fidi).
- Nel log: l'email mascherata (c\*\*\*@e\*\*\*.com) e una riga per lettura (%, autonomia, ricarica, kW, km e latenza),
  senza token, VIN né posizione.
- «Esci ed elimina i dati» elimina da questo telefono il certificato, la sessione e le impostazioni dell'account.

> [!WARNING]
> Usa un'**API non ufficiale di Leapmotor**: potrebbe smettere di funzionare in qualsiasi momento, e HeadQLink non è
> affiliato a Leapmotor. Con la **C10 REEV**, il consumo reale calcolato dal calo della % non ha senso quando il
> generatore ricarica in marcia: lì contano lo storico dell'auto e il suo contatore della benzina.

Il protocollo viene da **[LMB10](https://github.com/txurtxil/LPB10)** di **txurtxil** (GPL-3.0). I segnali del serbatoio
delle REEV e lo storico dei viaggi vengono da **[leapmotor-mate](https://github.com/ProtossBlaster/leapmotor-mate)**
(ProtossBlaster), e il consumo settimanale da **[leapmotor-api](https://github.com/markoceri/leapmotor-api)** (markoceri),
entrambi AGPL-3.0.

---

## 6. Calore e batteria

Proiettare video via Wi-Fi con il GPS acceso scalda il telefono. Nel primo test lungo, con il profilo «Molto alto» a
60 fps, l'S25 Ultra ha raggiunto lo stato termico «grave» di Android in 15 minuti e poi quello «critico». Per questo
questa versione porta tre novità:

1. **Profilo «Veicolo»:** invia 30 fps e il bitrate che chiede la C10 (circa 5 Mbit/s). È esattamente quello che mostra
   l'auto: di più non si vede meglio e scalda di più.
2. **Schermo del telefono spento:** lo schermo si spegne come sempre e la proiezione continua. L'opzione «Mantieni
   acceso lo schermo del telefono» è disattivata di default.
3. **Adattamento automatico al calore** (Android 10 o successivo e motore QDAuto; non fa nulla con «Base» in modalità
   «Auto», perché quel profilo non ricodifica):

| Stato termico di Android | «Protezione dal calore» Normale (consigliata) | «Leggera» |
|---|---|---|
| Normale o leggero | Niente: gli fps e il bitrate della sessione. | Niente. |
| Moderato | A **30 fps** se la sessione va a 60 per la «Fluidità» (a 30, gli stessi fps) e all'80 % del bitrate. | Solo il bitrate, all'80 %. |
| Grave | Scende a **24 fps** e al massimo **3,5 Mbit/s**. | **30 fps** al massimo e 3,5 Mbit/s. |
| Critico o peggio | Scende a **20 fps** e al massimo **3 Mbit/s**. | Lo stesso: 20 fps e 3 Mbit/s. |

Scende di livello subito. Torna alla normalità quando il telefono è più fresco da **30 secondi di fila**. Lo fa senza
interrompere la sessione né riavviare Android Auto, e senza avvisi: noterai solo un'immagine un po' meno fluida. Viene
annotato nel log. Con «Protezione dal calore» su «Disattivata» non cambia nulla (lo annota solo). Se anche il
collegamento è al limite, vale il più basso dei due limiti (calore o collegamento).

**Consigli**

- Usa il profilo «Automatico» o «Veicolo».
- **Blocca il telefono** appena compare Android Auto.
- Evita il **sole diretto**: non lasciarlo sul cruscotto né appoggiato al parabrezza.
- Un **supporto ventilato o con ventola**, per esempio sulla bocchetta dell'aria condizionata, è meglio di un vano
  chiuso. Se il caricatore wireless lo scalda molto, ricaricalo con il cavo.
- Mentre proietti, **non registrare video in 4K**, non giocare e non usare altre app pesanti sul telefono.
- D'estate togli una cover spessa.
- Nei viaggi lunghi, tieni il telefono in carica, con il cavo se puoi.

---

## 7. Risoluzione dei problemi

Comincia sempre dal menu ⚙ › «Verifica requisiti»: ogni riga rossa ha il suo pulsante.

| Sintomo | Causa probabile | Cosa fare |
|---|---|---|
| L'auto non viene trovata («Ricerca…» tutto il tempo) | L'auto non è collegata all'hotspot del telefono, l'hotspot si è spento da solo, o l'app di mirroring non è aperta in auto. Con Wi-Fi Direct: l'hotspot è acceso o il Wi-Fi è spento. | Guarda la riga «Rete»: dovrebbe dire «Hotspot attivo (…)». Collega l'auto all'hotspot (Impostazioni › Wi-Fi dell'auto) e apri l'app di mirroring. Disattiva lo spegnimento automatico dell'hotspot. Dopo 5 minuti senza che l'auto si annunci, HeadQLink si ferma: tocca di nuovo «Connetti». |
| «La porta 18463 è occupata (QDLink è aperta?)», o «Porta 18463 · Occupata» nella «Verifica requisiti» | L'app QDLink (o un'altra) è aperta sul telefono e tiene occupata la porta. | «Forza interruzione» di QDLink. HeadQLink riprova ogni 5 s e l'avviso sparisce da solo. |
| Schermo nero in auto, o un errore nella riga «Auto» | Android Auto non si è avviato: il telefono era bloccato («Non si avvia: sblocca il telefono»), o mancano la modalità sviluppatore o l'accessibilità. | Sblocca il telefono (con la notifica «Sblocca il telefono per avviare Android Auto», si avvia da solo quando sblocchi). Se non va ancora, controlla la «Verifica requisiti» e tocca «Disconnetti» e «Connetti». Per capire cosa non va, attiva Diagnostica › «Test senza Android Auto (immagine di prova)»: se vedi l'immagine di prova, la connessione va bene e il problema è Android Auto (disattivalo dopo). Se niente funziona, prova il motore «headqlink originale». |
| Immagine a scatti o in ritardo | Hotspot a 2,4 GHz, telefono caldo, profilo troppo alto, o schermo del telefono acceso (con lo schermo acceso, Android cerca spesso reti Wi-Fi). | Hotspot a 5 GHz, profilo «Automatico» o «Veicolo», blocca il telefono e fallo raffreddare. Se continua, prova «Medio». |
| Molte cadute della radio (l'immagine si ferma per mezzo secondo più e più volte; nel log, righe «Corte … RADIO» e «enlace muy congestionado») | La radio tra il telefono e l'auto non regge di più: hotspot a 2,4 GHz, il telefono in tasca, in un vano metallico o lontano dallo schermo. HeadQLink abbassa già la qualità quanto può (fino a 1,2 Mbit/s e 20 fps), ma con la radio satura non basta. | Metti l'hotspot a **5 GHz** (Impostazioni › Hotspot › Banda). Tieni il telefono fuori da tasche e vani metallici e vicino allo schermo dell'auto. Il **cavo USB** è il più stabile. Nel log, la riga «radio de la zona Wi-Fi» indica banda e canale quando Android lascia leggerli. |
| L'immagine è meno fluida che con l'HeadQLink originale | L'originale inviava 60 fps; HeadQLink ne invia 30 (quello che chiede l'auto) perché il telefono non si surriscaldi. Oppure il telefono è già caldo e l'adattamento al calore ha abbassato gli fps, o il collegamento è al limite e sono stati abbassati il bitrate o gli fps. | «Impostazioni immagine» › «Fluidità» › «60 fps · massima fluidità» (con il profilo «Veicolo» o «Automatico»). Se è il calore ad abbassare gli fps, «Protezione dal calore» › «Leggera» (sezione 6). Nel log, le righe «HQL/Térmico» («estado térmico 2» o più) e «enlace:» dicono quale delle due cose succede. |
| L'immagine si blocca per circa 10 s e poi si riconnette | Nelle versioni precedenti, un fotogramma completo più grande di ~512 KB bloccava il ricevitore dell'auto, che smetteva di leggere finché la connessione non cadeva. **Corretto**: HeadQLink non ne invia più di così grandi e li tiene intorno ai 300 KB al massimo. | Aggiorna HeadQLink. Se succede con il profilo «Base» (lì la dimensione la decide Android Auto), usa «Automatico» o «Veicolo». Se continua, esporta il log (sezione 8). |
| Si disconnette spesso | Spegnimento automatico dell'hotspot, risparmio batteria, QDLink aperta o l'app di mirroring dell'auto chiusa. | Le cadute brevi si riconnettono da sole («Riconnessione…»). Se sono lunghe o frequenti: «Batteria senza restrizioni», «Samsung: app mai in sospensione», disattiva lo spegnimento automatico dell'hotspot e chiudi QDLink. Se continua, esporta il log (sezione 8). |
| Notifica «Avvia (o riavvia) il server di Android Auto», o la riga «Auto» dice «In attesa del server di Android Auto» | Avvio manuale, e Android Auto non risponde: il suo server è spento (dopo un riavvio del telefono o un aggiornamento di Android Auto) o bloccato: una connessione si è interrotta a metà (qualcosa si è connesso e se n'è andato senza dire nulla, o una sessione è caduta di colpo). | Tocca la notifica o la riga: Android Auto › ⋮ › «Arresta server unità principale» (se compare) e ⋮ › «Avvia server unità principale». HeadQLink riprova ogni 5 s e prosegue da solo. |
| Notifica «HeadQLink non trova il pulsante del server» | Android Auto si è aggiornato e ha cambiato il suo menu ⋮ (l'automazione lo cerca per il testo). | «Apri AA» e avvialo a mano, oppure «Avvio manuale». Vedi [Versioni di Android Auto](#versioni-di-android-auto) ed esporta il log (sezione 8). |
| Notifica «Android Auto si connette e si disconnette dopo pochi secondi» | Un sintomo noto di alcune versioni nuove di Android Auto (17.8). | Svuota la cache di Android Auto (Impostazioni › App › Android Auto › Spazio di archiviazione › Svuota cache), ferma e riavvia il suo server, o torna a una versione provata ([Versioni di Android Auto](#versioni-di-android-auto)). |
| L'accessibilità si disattiva da sola | Android la disattiva quando l'app si aggiorna, o se l'app si è chiusa in modo anomalo. Lo fanno anche alcuni produttori. | «Verifica requisiti» › «Attiva». Se dice «Attiva ma non in funzione», disattivala e riattivala. Se dice «Impostazione con limitazioni», usa «Consenti impostazioni con limitazioni» (sezione 3). Togli le restrizioni di batteria. |
| Android Auto ti chiede di guardare il telefono | È la prima volta che Android Auto vede questo «schermo d'auto», o deve chiederti un'autorizzazione o una conferma. | Parcheggia, sblocca il telefono e accetta quello che chiede Android Auto. Di solito succede una volta sola. |
| Il telefono si scalda molto | Profilo «Molto alto» o «Alto», sole diretto, schermo acceso, ricarica wireless. | Sezione 6. |
| Notifica «Server di Android Auto aperto · Sblocca il telefono per chiuderlo» | La sessione è finita con il telefono bloccato e il server di Android Auto è ancora aperto. | Sblocca il telefono: HeadQLink lo chiude. |
| Dopo una sosta ci mette molto a tornare, o devi chiudere e riaprire l'app | La sosta è durata più di «Attesa dell'auto» e HeadQLink ha chiuso tutto; quando sblocchi, prima chiude Android Auto («Chiusura di Android Auto…») e poi deve riavviarlo. | Alza «Attesa dell'auto» a «15 min» (Impostazioni immagine › Avanzate) e attiva la connessione automatica via Bluetooth: quando torni, Android Auto ricompare all'istante senza sbloccare. Se vedi «Sblocca il telefono per avviare Android Auto», sbloccalo e aspetta: si avvia da solo, senza aprire l'app. |
| «Chiusura di Android Auto…» o «Avvio di Android Auto…» non sparisce, o la notifica «Il server di Android Auto è ancora attivo · Tocca per spegnerlo» | L'automazione delle impostazioni di Android Auto non è finita (per esempio, il telefono si è bloccato a metà). | La schermata sparisce da sola dopo 15 s. Tocca la notifica con il telefono sbloccato per spegnere il server. Se succede ancora, esporta il log (sezione 8): le righe «ciclo:» registrano ogni passaggio. |
| I dati dell'auto si bloccano quando blocchi il telefono (velocità, percorso, viaggio, consumo; tornano quando sblocchi) | La posizione di HeadQLink è solo «mentre l'app è in uso» e Android le toglie il GPS con il telefono bloccato. Lo schermo dell'auto e i sensori di movimento continuano; il GPS no. | «Verifica requisiti» › «Posizione sempre consentita» › «Apri» › «Consenti sempre». Nel log, «GPS: ubicación todo el tiempo sí · con el móvil bloqueado llega» conferma che funziona. |
| La connessione automatica non parte | Il nome Bluetooth non corrisponde, o Android non la lascia avviarsi in background. | Controlla il testo nella finestra «Connessione automatica», togli le restrizioni di batteria, o tocca la notifica «Tocca per connettere HeadQLink». |
| Non si installa o non si aggiorna | Play Protect, Blocco automatico di Samsung, o una versione con una firma diversa. | Sezione 2. |

---

## 8. Esportare il log per chiedere aiuto

Se qualcosa va storto, il log aiuta molto a trovarne la causa. **Esportalo subito dopo il problema** e, se puoi, annota
l'ora in cui è successo.

1. Menu ⚙ › «Diagnostica».
2. Tocca **«Esporta log»**. Vedrai «Esportazione del log…» e poi «Salvato in Download/HeadQLink/…».
3. Si apre «Condividi il log di HeadQLink»: invialo per email, Telegram, Drive o con l'app che preferisci, oppure tienilo
   e basta.

<!-- schermata: Diagnostica con «Esporta log» -->

**Dove finisce:** `Download/HeadQLink/HeadQLink-log-AAAAMMGG-HHMMSS.zip`. Su Android 9 o precedente resta nella cartella
dell'app (`Android/data/com.headqlink.app/files/exports/`) e il messaggio mostra il percorso esatto.

**Cosa c'è nello ZIP**

| File | Contenuto |
|---|---|
| `resumen.txt` | Versione dell'app, modello del telefono e versione di Android, le tue impostazioni, connessione e motore, stato attuale, interfacce di rete, sessioni recenti e l'elenco dei file inclusi (in spagnolo). |
| `logs/` | Il log completo, con `sessions.csv` (una riga per ogni sessione con l'auto). |
| `car/`, `perf/`, `logcat/`, `crash/` | Diario dell'auto, prestazioni, il log di sistema dell'app e i crash (i più recenti). |

**Privacy del log**

- **Contiene:** identificativi dell'auto (CarUUID, ProjectID), indirizzi IP, nomi di rete, il modello del telefono e le
  tue impostazioni.
- **Non contiene:** i tuoi viaggi con il GPS (`trips/` non viene mai esportato), coordinate o destinazioni, né
  screenshot. I log scritti da versioni precedenti potevano contenere qualche posizione.
- **Non viene inviato nulla in automatico:** scegli tu con chi condividerlo. Meglio in privato, non pubblicato
  apertamente.

Puoi chiedere aiuto sulla pagina GitHub del progetto: [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink).

---

## 9. Sicurezza, privacy e note legali

### Autorizzazioni e a cosa servono

| Autorizzazione | A cosa serve | Quando |
|---|---|---|
| Accessibilità «HeadQLink touch» | Avviare e fermare il server di Android Auto toccando il suo menu sviluppatore, e ricevere i tocchi dell'auto. **È limitata ad Android Auto:** non vede le altre app. | Android Auto 17.4 o successivo |
| Dispositivi Wi-Fi nelle vicinanze (o Posizione su Android 12 o precedente) | Collegarsi alla rete Wi-Fi Direct dell'auto. | Solo con «Wi-Fi Direct» |
| Notifiche | Vedere lo stato della connessione e gli avvisi. | Sempre (consigliato) |
| Bluetooth (dispositivi nelle vicinanze) | Riconoscere il Bluetooth dell'auto. | Solo con la connessione automatica |
| Batteria senza restrizioni | Restare in funzione con lo schermo spento. | Consigliato |
| Mostra sopra altre app | Aprire Android Auto con il telefono in background. | Facoltativo |
| Foto, video e posizione | Galleria e pannelli di guida. | Facoltativo, «Auto esteso» |
| Posizione sempre consentita | Far continuare i pannelli «Veicolo» con il telefono bloccato. Si usa solo con il collegamento attivo o con le schermate «Veicolo» aperte. | Consigliato, «Auto esteso» |
| Microfono | «Parla» in «Cerca destinazione»: lo capisce il riconoscimento vocale del telefono. | Facoltativo, «Auto esteso» |
| Internet | Servizi di «Auto esteso» (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info), la ricerca della destinazione (Photon e la ricerca di indirizzi di Android, quella di Google: ricevono quello che scrivi), la mappa dei viaggi (le tessere della mappa di OpenStreetMap), le tue liste TV e radio e, se lo configuri, il cloud di Leapmotor (dati reali dell'auto, sola lettura). | Solo per quelle funzioni |

- HeadQLink **non invia telemetria** e non ha pubblicità.
- **Non condivide il GPS** con Android Auto di default.
- I suoi servizi interni non sono aperti ad altre app.
- L'APK dichiara altre autorizzazioni ereditate da Open Headunit. HeadQLink non le chiede durante la configurazione; il
  microfono si usa solo per «Parla» nella ricerca (se non è consentito, l'auto spiega come consentirlo).
- Le **destinazioni recenti** della ricerca e i **percorsi dei viaggi** restano solo sul telefono (il log non contiene né
  destinazioni né posizioni).

> [!IMPORTANT]
> **Il server di Android Auto.** Con Android Auto 17.4 o successivo, HeadQLink accende il «server unità principale»
> della modalità sviluppatore di Android Auto. Mentre è acceso, ascolta su tutte le reti del telefono, quindi
> **HeadQLink lo spegne quando ha finito**: quando tocchi «Disconnetti» o quando scade «Attesa dell'auto» (al massimo
> 15 minuti senza l'auto, con Android Auto in pausa che tiene occupato il server). Con il telefono bloccato non può
> spegnerlo, e lo lascia in attesa finché non sblocchi («Android Auto in attesa finché non sblocchi il telefono…»).
> **Sblocca il telefono dopo ogni viaggio.**
>
> Con l'avvio manuale (senza accessibilità), HeadQLink non può spegnerlo: alla fine chiude Android Auto, ma il server
> resta acceso (è questo che permette al viaggio successivo di partire senza toccare nulla). **Fermalo tu** (Android
> Auto › ⋮ › «Arresta server unità principale») se non lo userai, soprattutto su un Wi-Fi pubblico.

**Quando non la usi:**

- Tocca «Disconnetti» se hai aperto HeadQLink fuori dall'auto: inizia a connettersi appena si apre.
- Spegni l'hotspot.
- Disattiva «Connetti al rilevamento del Bluetooth dell'auto» se non vuoi che parta da sola.
- Se smetti di usarla per un po', disattiva il servizio di accessibilità «HeadQLink touch» e, se vuoi, esci dalla
  modalità sviluppatore di Android Auto.

### Note legali

- **Il software è fornito «COSÌ COM'È», SENZA GARANZIE di alcun tipo.** È sperimentale, si basa su un protocollo non
  documentato e può presentare errori o smettere di funzionare in qualsiasi momento.
- **Gli autori non si assumono alcuna responsabilità** per danni di qualsiasi tipo: incidenti, lesioni, danni all'auto,
  al telefono o a terzi, perdita di dati, multe, perdita della garanzia o violazione di condizioni di terzi. Lo usi **a
  tuo rischio**.
- **Non usarlo durante la guida.** Il conducente è l'unico responsabile del rispetto del Codice della strada. Non
  guardare video, TV, pagine web o giochi in movimento.
- HeadQLink **non è affiliato, approvato né sponsorizzato** da Leapmotor, Google, Neusoft, Open Headunit o qualsiasi
  altra azienda o progetto. Leapmotor, C10, Android Auto e QDLink sono marchi dei rispettivi proprietari.

### Licenza e crediti

- Distribuito con licenza **[GNU AGPL-3.0](LICENSE)**. Hai diritto al codice sorgente: è su
  [github.com/CharlysEV/headqlink](https://github.com/CharlysEV/headqlink). Si applicano anche le sezioni 15 e 16 della
  licenza (esclusione di garanzia e limitazione di responsabilità).
- Basato su **[headqlink](https://github.com/ryazrm/headqlink)** di **ryazrm**, che a sua volta deriva da
  **[Open Headunit](https://github.com/andreknieriem/open-headunit)** di **André Rinas (andreknieriem)**, e sul lavoro
  originale di **Michael Reid** ([nota di copyright](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)). HeadQLink è un progetto
  indipendente, senza alcun rapporto con Open Headunit o i suoi autori.
- Motore di protocollo **QDAuto**: [CharlysEV/qdauto](https://github.com/CharlysEV/qdauto).
- Dati reali dell'auto (account Leapmotor): client di sola lettura portato da **[LMB10](https://github.com/txurtxil/LPB10)**
  di **txurtxil** (GPL-3.0); vedi [NOTICE](NOTICE).

<p align="right">Franchi Samuele, Pisa 12/01/2024</p>

# Scelte implementative

## Server
Inizialmente il server carica i parametri necessari all'esecuzione dal file di configurazione e fa il parsing di tutti i dati contenuti nei file JSON, deserializzandoli all'interno di classi Java, per permetterne la manipolazione tramite metodi. Inizialmente calcola tutti i ranking per avere un punto di partenza, poi lo farà periodicamente in un thread parallelo, con un rate specificato nel file di configurazione.
Al server è associato un thread di terminazione che viene eseguito prima di terminare l'esecuzione. Il suo scopo è quello di chiudere opportunamente tutte le risorse aperte precedentemente e persistere i dati sui file JSON per garantire consistenza. Nel mio caso la consistenza è garantita ad ogni operazione, ma nell'ottica di avere il salvataggio periodico tramite il rate specificato diventa necessario: se il server venisse chiuso nel mezzo del rate, alcuni dati andrebbero persi.

Fatta eccezione per questi thread, il server è single-threaded e gestisce le richieste del client tramite i `Channel` non bloccanti forniti da Java NIO, insieme al multiplexing dei canali per capire quali sono pronti ad eseguire una certa operazione.
Più connessioni di rete sono così gestite mediante un unico thread, consentendo di ridurre il thread switching overhead e l’uso di risorse aggiuntive per thread diversi, rispetto alla soluzione che apre un thread per ogni connessione.
I canali vengono registrati in un key set di descrittori per poter identificare la coppia canale-operazione; un sottoinsieme di queste chiavi realizzerà il ready set, ovvero i canali pronti ad eseguire l'operazione specificata nel key set.
Il server apre un `ServerSocketChannel` per permettere la connessione TCP con i client, e crea un oggetto `Selector` per eseguire una monitor delle operazioni di input/output dei canali, ovvero per capire se un determinato client è:
- In attesa di connessione
- In attesa di lettura
- In attesa di scrittura
Successivamente procede a soddisfare tutte le richieste. In particolare risponde opportunamente ai codici richiesti dal client, con controllo degli errori:
1. Registrazione di un nuovo utente
2. Login/Logout, in base se un utente è già autenticato o meno
3. Ricerca di un hotel specifico tramite nome hotel e nome città
4. Cerca tutti gli hotel in una città specifica, ordinati in base al ranking
5. Inserisce una nuova recensione, associata all'utente loggato e ad un hotel
6. Mostra il badge relativo all'utente loggato

Esso apre anche un `MulticastSocket` per poter inviare i messaggi UDP delle notifiche ad un gruppo di client iscritti all'indirizzo multicast.

Tutte le strutture dati sono thread-safe, in ottica di poter aggiungere in futuro un thread pool all'interno del server. Le strutture dati usate sono:
- `ConcurrentHashMap`: `Map` chiave-valore per avere una corrispondenza immediata tra ogni città capoluogo e il suo relativo ranking di hotel
- `ConcurrentHashMap.newKeySet()`: restituisce un `Set` che permette di avere un insieme di città presenti nel file, senza ripetizioni, che saranno poi le chiavi della `Map`
- `Vector`: implementa una `List` che manterrà i dati presenti nei file JSON, e permette di usufruire dei suoi utili metodi di manipolazione dati

### Algoritmo di ranking
L'algoritmo di ranking, implementato nella classe `HotelComparator`, ordina gli hotel in maniera decrescente, da quello che ha il punteggio più alto a quello che ha il punteggio più basso. Il punteggio tiene in considerazione vari parametri associati ad ogni hotel:
- Punteggio relativo ai rating: un numero tra 1 e 5 che dà un peso del 40% al rating generale ed un peso del 15% ai rating più specifici
- Punteggio relativo al numero di recensioni: conta quanti zeri ha il numero di recensioni totali (esempio 1546 recensioni, punteggio = 3), tramite un logaritmo in base 10
- Punteggio relativo all'attualità delle recensioni: prende il numero di giorni medi che sono passati dall'ultima recensione (chiamiamolo $g$), e svolge il seguente calcolo: $1 / {e^{\frac g {100}}}$. Esso sarà un numero tra 0 e 1 che si avvicinerà ad 1 più $g$ si avvicina a 0; in altre parole meno giorni sono passati dall'ultima recensione media, più il punteggio sarà alto
Di questi tre punteggi si fa una somma, dando un peso del 60% a quello del rating, uno del 5% a quello del numero di recensioni ed un peso del 35% al punteggio attualità.

L'unico difetto di questo algoritmo è che hotel con rating bassi, ma un gran numero di recensioni rispetto agli altri, verranno premiati con una posizione alta.
Alla attualità è stato assegnato appositamente un peso basso, mentre ai rating il peso maggiore.

## Client
Il client, similmente al server, inizialmente carica i file di configurazione e, successivamente, alloca un buffer di dimensione specificata sul file.
Le notifiche sono gestite tramite un thread che si collega allo stesso gruppo multicast del server e continua a mettersi in attesa di ricezione di pacchetti; una volta che avrà ricevuto il pacchetto con la notifica, estrae il messaggio, lo converte in una stringa e lo inserisce in una `BlockingQueue`. La `BlockingQueue` è thread safe, quindi due thread possono utilizzarla allo stesso momento. Il client, quindi, prima di svolgere qualsiasi operazione, controllerà se ci sono notifiche, e in caso positivo le stamperà una dopo l'altra fino a quando la coda non sarà vuota.

Anche nel client è associato un termination handler per chiudere correttamente gli oggetti aperti prima della chiusura e quindi per non far lanciare nessuna eccezione.

Successivamente il client cerca di stabilire una connessione con il server, creando un socket TCP, e provando a connettersi più volte se quest'ultimo risulta non raggiungibile.
Il programma permetterà ad ogni utente di inviare una richiesta al server con un codice specifico per effettuare le operazioni di:
1. Registrazione, inserendo un username univoco ed una password. Il server risponderà con un codice di successo/errore:
	- 0: Successo, registrazione avvenuta
	- 1: Errore, campo username vuoto
	- 2: Errore, campo password vuoto
	- 3: Errore, username già esistente
2. Login, inserendo username e password corrispondenti a quelle utilizzate in fase di registrazione. Se sei già autenticato, effettuerai il logout. I codici di risposta del server saranno:
	- 0: Successo, login avvenuto / logout avvenuto
	- 1: Errore, username o password errate / errore nel logout
3. Ricerca di un hotel, specificando nome e città di riferimento. Il server risponderà con una stringa, che conterrà i dati dell'hotel in formato JSON se ha effettivamente trovato l'hotel; in caso contrario restituirà una stringa vuota.
4. Ricerca di tutti gli hotel in una città. Il server risponderà con una stringa, che conterrà tutti gli hotel trovati in quella città, ordinati per ranking. Se non ci sono hotel in quella città, restituirà una stringa vuota.
5. Inserimento di una recensione relativa ad un hotel (solo per utenti autenticati), specificando il nome dell'hotel, la città di riferimento, una valutazione generale e quattro valutazioni specifiche: pulizia, posizione, servizi e prezzo. Tutte le valutazioni devono essere un numero intero compreso tra 1 e 5. Il server risponde con:
	- 0: Successo, recensione inserita
	- 1: Errore, hotel non trovato
	- 2: Errore generico nella richiesta (tra cui: rating fuori range)
6. Visualizzare il badge con il tuo grado di esperienza (devi essere autenticato), in cui il server risponderà con una stringa contenente un messaggio di errore, oppure un titolo che ti è stato assegnato in base al numero delle tue recensioni:
	- Meno di 10: Recensore
	- Tra 10 e 20: Recensore Esperto
	- Tra 20 e 30: Contributore
	- Tra 30 e 40: Contributore Esperto
	- Più di 40: Contributore Super

Le richieste da mandare al server hanno la struttura `codiceRichiesta[;arg1;arg2...]`, in cui i parametri sono stati spiegati nel paragrafo precedente, tutti nell'ordine in cui sono stati nominati.

## File di configurazione
Ci sono due file di configurazione, `client.config` e `server.config`, in cui quello del server viene usato anche dal client poiché mantiene configurazioni comuni.
Nel file di configurazione del client è specificato solo l'IP del server.
Nei file vengono specificati tutti quei dati che possono essere cambiati per dare un comportamento diverso al programma, tra cui:
- `SERVER_IP` e `PORT`: IP e porta che il server mette a disposizione per il servizio
- `bufferSize`: Dimensione del buffer di client e server in byte
- `exitMessage`: Messaggio di uscita che il client manda al server quando si disconnette
- `multicastAddress` e `multicastPort`: Indirizzo IP e porta multicast per invio e ricezione delle notifiche
- `hotelsFile`, `usersFile` e `reviewsFile`: Nome dei file JSON in cui sono contenuti i dati persistiti
- `autosaveRate`: Rate in millisecondi per il calcolo dei ranking ed il salvataggio sui file JSON (nel mio caso salvo ad ogni operazione)

## File JSON
I file JSON sono aggiornati ad ogni operazione per mantenere consistenza e per riflettere immediatamente i cambiamenti al client, in un'ottica di testing, in cui vengono eseguite poche operazioni e devono essere mostrate in tempo reale a schermo. Considerando migliaia di operazioni al secondo converrebbe avere un salvataggio periodico scandito da un rate deciso nel file di configurazione, a discapito di non avere sempre i dati aggiornati all'ultima operazione (che comunque su grandi numeri non è così rilevante), ma in compenso aumentando le performance del server, che su grandi quantità di dati se dovesse salvare i cambiamenti sul file JSON per ogni richiesta dei client, rallenterebbe troppo i tempi di risposta.
- Il file `Hotels.json` in questo caso viene aggiornato solamente per mantenere i dati necessari ad un calcolo rapido del ranking, ovvero le medie delle singole valutazioni, il numero di valutazioni e la data media di valutazione, che sarà molto indietro se l'hotel non ha ricevuto recensioni da molto tempo
	- Inizialmente deve contenere tutti gli hotel registrati manualmente
	- I parametri contenuti sono: `id`, `name`, `description`, `city`, `phone`, `services[]`, `rate`, `ratings {cleaning, position, services, quality}`
	- Dall'eseguibile, per rendere più efficiente il calcolo dei ranking, vengono aggiunti `reviews` e `avgReviewDate`, allo scopo di tenere traccia del numero di recensioni e della loro attualità, senza dover rileggere tutte le volte il file `Reviews.json`
- Il file `Users.json` viene aggiornato ogni volta che un utente si registra e ogni volta che un utente registrato lascia una recensione, per aggiornare il suo badge opportunamente
	- Inizialmente deve contenere un array vuoto `[]`, oppure utenti già registrati manualmente
	- I parametri contenuti sono: `id`, `username`, `password`, `reviews`, `badge`
	- La password è in chiaro, ovviamente in un programma reale sarebbe dovuta essere cifrata/offuscata in qualche modo!
- Il file `Reviews.json` viene usato solamente per mantenere uno storico di recensioni, ma non viene mai usato per fare calcoli; in prospettiva che diventi un file molto molto grande non sarebbe efficiente rileggerlo tutto ogni volta per calcolare i ranking. Per questo tengo aggiornati i dati degli hotel ad ogni operazione, dando il giusto peso in base al numero di recensioni già presenti. L'unico difetto è che aggiornare il file manualmente non porta cambiamenti al calcolo dei ranking e tantomeno al calcolo del badge.
	- Inizialmente deve contenere un array vuoto `[]`
	- I parametri contenuti sono: `userId`, `hotelId`, `rate`, `ratings {cleaning, position, services, quality}`, `date`

## File Manifest
I file manifest specificano i parametri necessari per creare un file JAR che contiene tutto il necessario per l'esecuzione del programma in un solo file, fatta eccezione per i file testuali usati dal programma (configurazione e JSON). In particolare viene specificata la classe che contiene il main, e quindi l'entry point del programma, e vengono specificati nel class path: la cartella che contiene i file `.class` compilati ed il percorso di tutte le librerie jar da cui dipende il programma.
- `Manifest-Version`: versione del file (facoltativo)
- `Class-Path`: lista delle cartelle / file di librerie, separate da spazio
- `Main-Class`: entry point, nome della classe (e non del file)
Nota: il file manifest deve finire con uno "`\n`" (linea vuota finale), altrimenti verrà ignorato

<div style="page-break-after: always;"></div>

# Compilazione
Compila tutti i file `.java` presenti nella cartella `src`, e li mette nella cartella `bin`. Come class path specifica la cartella che contiene i file delle classi eseguibili (`.class`) e i file delle librerie importate
```powershell
javac -d bin -cp "bin;lib\gson-2.10.1.jar" src\*.java
```
Nota: su Linux viene usato `:` al posto di `;`

# Esecuzione
Dopo aver compilato i file `.java`, posso eseguire le classi contenenti un main
```powershell
java -cp "bin;lib\gson-2.10.1.jar" ServerMain
java -cp "bin;lib\gson-2.10.1.jar" ClientMain
```

# Creazione JAR
Dopo aver compilato i file `.java`, posso creare il JAR
```powershell
jar cfm Server.jar Server.mf
jar cfm Client.jar Client.mf
```

# Esecuzione JAR
```powershell
java -jar Server.jar
java -jar Client.jar
```

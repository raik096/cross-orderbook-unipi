# CROSS – an exChange oRder bOokS Service

## 📌 Descrizione del progetto

**CROSS** è un servizio distribuito client-server che implementa un *order book*, ovvero un registro elettronico degli ordini di acquisto e vendita di asset digitali (come Bitcoin). Il progetto, sviluppato per il corso di Reti e Laboratorio III (A.A. 2024/25), simula il comportamento di un exchange centralizzato in cui più utenti possono registrarsi, effettuare login, piazzare ordini e ricevere notifiche sulle esecuzioni.

Il sistema gestisce tre tipi di ordine (Market, Limit e Stop Order), esegue automaticamente i matching tra ordini compatibili, persiste i dati in file JSON e notifica gli utenti tramite UDP.

## ⚙️ Architettura

Il sistema è composto da due applicazioni Java:

- `mainServer.java`: gestisce le connessioni client in multithreading, coordina le operazioni e invia notifiche.
- `mainClient.java`: interfaccia a linea di comando che permette all’utente di interagire con CROSS.

### 🧩 Componenti principali

- **OrderBook.java**: logica e strutture dati per la gestione degli ordini (inserimento, cancellazione, esecuzione).
- **UserManager.java**: registrazione, login/logout e gestione utenti.
- Comunicazione:
  - TCP: per richieste/risposte client-server.
  - UDP: per l’invio asincrono di notifiche.
- Serializzazione: JSON via libreria `Gson`.
- Persistenza: salvataggio ordini e utenti in file `.json`.

## 🧵 Thread & Concorrenza

**Lato Server:**
- Thread principale: accetta le connessioni in entrata.
- Pool di thread (`ThreadPoolExecutor`): gestisce le richieste client.
- Thread di invio notifiche UDP.

**Lato Client:**
- Thread principale: gestisce input/output utente.
- Thread UDP listener: riceve notifiche.
- Thread monitor: rileva disconnessioni.
- Thread timeout: gestisce l'inattività del client.

## 📂 Strutture dati

- `ConcurrentHashMap<User, List<Order>>`: database utenti-ordini.
- `OrderBook`: segmenta ordini per prezzo e tipo.
- `JsonObject`: comandi strutturati lato client.
- `DatagramPacket`: gestione UDP.

## 🔐 Sincronizzazione

- Uso di `synchronized` e collezioni sincronizzate (`Collections.synchronizedList`) per garantire thread-safety lato server.
- La scrittura su file è anch’essa sincronizzata per evitare race conditions.

## 🚀 Come eseguire il progetto

### 📦 Dipendenze

- Java JDK 8+
- Gson v2.11.0 (incluso o scaricabile [qui](https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/))

### 🛠️ Compilazione

```bash
javac -cp .:gson-2.11.0.jar *.java
```

### ▶️ Esecuzione

#### Avvio del server
```bash
java -cp .:gson-2.11.0.jar mainServer
```

#### Avvio del client
```bash
java -cp .:gson-2.11.0.jar mainClient
```

## 🧪 Stato del progetto

Il progetto è **funzionale**, ma **non completamente completato**. Nello specifico:

- La parte relativa alle **notifiche persistenti per utenti offline** non è stata implementata fino in fondo.
- Le funzioni principali richieste dalla specifica del progetto sono presenti e operative.

## 📝 Comandi supportati (formato JSON)

```json
{
  "operation": "register",
  "values": {
    "username": "user123",
    "password": "securepwd"
  }
}
```

Altri comandi: `login`, `logout`, `updateCredentials`, `insertLimitOrder`, `insertMarketOrder`, `insertStopOrder`, `cancelOrder`, `getPriceHistory`


## 📎 Credits

Progetto sviluppato come prova finale del corso **Reti e Laboratorio III – Modulo LAB** presso l’Università degli Studi di Pisa.

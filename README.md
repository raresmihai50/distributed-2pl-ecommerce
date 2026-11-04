# Distributed 2PL E-Commerce — Tranzacții Distribuite, Strict 2PL, Wait-For Graph, Undo Log & 2PC

Aplicație Java 21 + Spring Boot 3.4 pentru cursul de **Sisteme Distribuite și Controlul Concurenței**, care implementează **manual, la nivelul aplicației** (fără `@Transactional`, fără JPA/Hibernate și fără JTA):
- **Transaction Manager & Coordinator** distribuit între **PostgreSQL** și **MySQL** (prin JDBC direct și `PreparedStatement`).
- **Strict Two-Phase Locking (Strict 2PL)** centralizat (`SHARED` / `EXCLUSIVE` locks, compatibilitate, lock upgrade `S -> X`, cozi de așteptare FIFO).
- **Deadlock Detector bazat pe Wait-For Graph (WFG)** (detecție de cicluri orientate cu DFS, selecția victimei și rollback automat al victimei).
- **Undo Log & Rollback Manager** (capturarea imaginilor anterioare `before-image` și execuția instrucțiunilor SQL inverse în ordine inversă `LIFO`).
- **Two-Phase Commit (2PC)** coordonat de aplicație, cu jurnal persistent pe disc (`coordinator-2pc-journal.log`) și în baza de date (`transaction_logs`), inclusiv mecanism de **Crash Recovery** la repornire.

---

## 1. Arhitectura Aplicației

Aplicația este structurată pe 4 niveluri logice distincte:

1. **Presentation Layer (`ro.distr2pl.app.controller` + `resources/static/`)**:
   - `EcommerceController`: expune API-urile REST pentru cele 8 operații e-commerce (`/api/users`, `/api/products`, `/api/inventory`, `/api/orders`, `/api/payments`).
   - `MonitoringController`: expune starea în timp real a tranzacțiilor, tabelei de lock-uri, grafului Wait-For, istoriului de deadlock-uri și simulatoarelor de concurență (`/api/monitoring/*`).
   - Interfață Web modernă (`index.html`, `styles.css`, `app.js`) cu 8 secțiuni: *Dashboard & Simulator*, *Products*, *Inventory*, *Orders*, *Payments*, *Transactions Monitor*, *Lock Manager Monitor*, *Deadlock Monitor*.

2. **Business & Transaction Layer (`ro.distr2pl.app.service`, `ro.distr2pl.app.transaction`, `ro.distr2pl.app.concurrency`)**:
   - `EcommerceService` & `SimulationService`: logica de business pentru cele 8 operații și scenariile de concurență.
   - `TransactionManager` & `TransactionCoordinator`: gestionează ciclul de viață al tranzacțiilor distribuite (`BEGIN`, execuție SQL pe ambele baze de date, `COMMIT` sau `ROLLBACK`).
   - `LockManager`: gestionar centralizat thread-safe pentru protocolul **Strict 2PL**.
   - `DeadlockDetector` & `WaitForGraph`: detecția și rezolvarea deadlock-urilor.
   - `RollbackManager`, `UndoLog`, `UndoLogEntry`: execuția compensărilor SQL inverse în ordine `LIFO`.
   - `CommitManager` & `TwoPhaseCommitJournal`: protocolul **Two-Phase Commit (2PC)** și recuperarea după eșec.

3. **Data Access Layer (`ro.distr2pl.app.repository.postgres`, `ro.distr2pl.app.repository.mysql`)**:
   - două `DataSource`-uri complet independente (`postgresDataSource` și `mysqlDataSource` în `DataSourceConfig.java`).
   - Repository-uri JDBC pure (`UserRepository`, `ProductRepository`, `InventoryRepository`, `OrderRepository`, `OrderItemRepository`, `PaymentRepository`, `TransactionLogRepository`) care utilizează exclusiv `PreparedStatement`.

4. **Database Layer (`docker-compose.yml`)**:
   - **PostgreSQL 16** (port host `5433` -> container `5432`)
   - **MySQL 8.0** (port host `3307` -> container `3306`)

---

## 2. Distribuția Datelor între Bazele de Date

### PostgreSQL (`ecommerce_pg` — port `5433`)
1. **`users`** (`id`, `username`, `email`, `full_name`, `balance`, `created_at`) — conturile și soldul utilizatorilor.
2. **`products`** (`id`, `name`, `description`, `price`, `category`, `created_at`) — catalogul de produse.
3. **`inventory`** (`product_id` PK/FK, `quantity`, `reserved_quantity`, `updated_at`) — stocul disponibil pentru fiecare produs (`CHECK (quantity >= 0)`).
4. **`orders`** (`id`, `user_id` FK, `total_amount`, `status`, `created_at`) — antetul comenzilor plasate (`PENDING`, `CONFIRMED`, `PAID`, `CANCELLED`, `REFUNDED`).

### MySQL (`ecommerce_mysql` — port `3307`)
1. **`payments`** (`id`, `order_id`, `user_id`, `amount`, `payment_method`, `status`, `transaction_id`, `created_at`) — plățile asociate comenzilor.
2. **`order_items`** (`id`, `order_id`, `product_id`, `quantity`, `unit_price`, `subtotal`) — liniile de produse comandate.
3. **`transaction_logs`** (`transaction_id` PK, `operation_name`, `status`, `phase_2pc`, `postgres_prepared`, `mysql_prepared`, `participants`, `sql_operations`, `locks_held`, `undo_operations`, `error_message`, `started_at`, `completed_at`, `duration_ms`) — jurnalul de audit al tranzacțiilor distribuite.

---

## 3. Algoritmul Strict Two-Phase Locking (Strict 2PL)

Implementat în `LockManager.java`, `Lock.java`, `LockType.java`, `LockRequest.java`:
- **Identificatori globali de resurse**: `DATABASE:table:recordId` (ex: `POSTGRESQL:products:1`, `POSTGRESQL:inventory:4`, `MYSQL:payments:12`).
- **Matricea de compatibilitate**:
  | Lock Solicitat \ Lock Deținut | `SHARED (S)` | `EXCLUSIVE (X)` |
  | :--- | :---: | :---: |
  | **`SHARED (S)`** | **DA** | **NU** |
  | **`EXCLUSIVE (X)`** | **NU** | **NU** |
- **Faza de creștere (Growing Phase)**:
  - Înainte de orice `SELECT`, tranzacția obține un lock `SHARED (S)`.
  - Înainte de orice `INSERT`, `UPDATE` sau `DELETE`, tranzacția obține un lock `EXCLUSIVE (X)`.
  - Dacă o tranzacție deține deja `S` pe o resursă și solicită `X`, `LockManager` execută **Lock Upgrade (`S -> X`)** (imediat dacă este unicul proprietar `S`, sau cu prioritate în coada de așteptare dacă mai există alți proprietari `S`).
- **Strictness (Faza de eliberare la final)**:
  - Toate lock-urile (`S` și `X`) sunt păstrate până **după** finalizarea completă a `COMMIT`-ului (Faza 2 din 2PC) sau a `ROLLBACK`-ului (după execuția tuturor operațiilor din `UndoLog`).
  - Această regulă elimină complet *dirty reads*, *lost updates*, *inconsistent reads* și *cascading aborts*.

---

## 4. Wait-For Graph & Deadlock Detection / Resolution

Implementat în `WaitForGraph.java` și `DeadlockDetector.java`:
- **Construirea grafului**:
  - Fiecare nod este un `transactionId` activ.
  - Când `T1` solicită un lock incompatibil cu lock-ul deținut de `T2`, se adaugă muchia orientată `T1 -> T2`.
- **Detecția ciclurilor**:
  - La fiecare intrare în așteptare (și periodic în timpul așteptării), `WaitForGraph.findCycle()` rulează o parcurgere **DFS (Depth-First Search)** cu marcaj în stiva de recursie pentru a identifica orice ciclu `T1 -> T2 -> ... -> T1`.
- **Selecția victimei și rezolvarea**:
  - Strategia **Youngest Transaction**: este aleasă ca victimă tranzacția din ciclu care a început cel mai recent (`transactionStartTimes`), protejând tranzacțiile mai vechi.
  - Tranzacția victimă este trezită imediat, aruncă `DeadlockException`, execută `RollbackManager.rollback()` (restaurând modificările sale prin `UndoLog`), își eliberează toate lock-urile din `LockManager`, iar tranzacțiile care așteptau după ea își primesc lock-urile și continuă spre `COMMIT`.

---

## 5. Mecanismul de Undo Log & Rollback

Implementat în `UndoLog.java`, `UndoLogEntry.java` și `RollbackManager.java`:
- Înainte de fiecare operație SQL care modifică datele (`INSERT`, `UPDATE`, `DELETE`), aplicația memorează în `UndoLogEntry`:
  - `operationOrder`, `database` (`POSTGRESQL` / `MYSQL`), `tableName`, `recordId`, `modifiedColumn`, `oldValue`, `newValue`, `originalOperationType`, `inverseSql` și `inverseParameters`.
- **Operațiile compensatorii inverse**:
  - Pentru `INSERT` &rarr; `DELETE FROM table WHERE id = ?`
  - Pentru `UPDATE` &rarr; `UPDATE table SET col = ? WHERE id = ?` (cu `oldValue`)
  - Pentru `DELETE` &rarr; `INSERT INTO table (...) VALUES (...)` (cu valorile înregistrării șterse)
- La `ABORT`, `RollbackManager` parcurge `UndoLog`-ul în **ordine inversă (`LIFO`)**, execută fiecare instrucțiune SQL inversă pe baza de date corespunzătoare (PostgreSQL sau MySQL), eliberează lock-urile Strict 2PL și marchează tranzacția ca `ABORTED`.

---

## 6. Protocolul Two-Phase Commit (2PC) & Tratarea Commit-ului Parțial

Implementat în `CommitManager.java` și `TwoPhaseCommitJournal.java`:
- **Faza 1 — PREPARE (Voting Phase)**:
  1. Verifică faptul că tranzacția este `ACTIVE` și că toate operațiile SQL s-au executat fără eroare.
  2. Verifică în `LockManager` că tranzacția deține în continuare toate lock-urile `S` și `X` necesare.
  3. Scrie starea `PREPARE_PHASE_1` + `UndoLog`-ul complet în jurnalul persistent al coordonatorului (`./target/coordinator-2pc-journal.log`).
  4. Interoghează participanții **PostgreSQL** și **MySQL** pentru pregătire (`SELECT 1` / validarea conexiunii).
  5. Dacă oricare participant votează `NO` sau apare o eroare în Faza 1 &rarr; scrie decizia `GLOBAL_ABORT` și apelează `RollbackManager` pentru restaurarea ambelor baze de date.
  6. Dacă ambii participanți votează `YES` &rarr; scrie decizia durabilă `GLOBAL_COMMIT` în jurnalul coordonatorului (*Point of No Return*).
- **Faza 2 — COMMIT (Completion Phase)**:
  1. Confirmă modificările pe PostgreSQL (`POSTGRES_COMMITTED`) și pe MySQL (`MYSQL_COMMITTED`).
  2. Scrie `COMPLETED` în jurnal, actualizează `transaction_logs` în `COMMITTED` și eliberează toate lock-urile Strict 2PL.
- **Analiza Commit-ului Parțial și Recuperarea după Restart (`recoverInDoubtTransactions()`)**:
  - Două apeluri `commit()` succesive nu sunt atomice prin ele însele dacă aplicația se oprește între primul și al doilea participant.
  - La pornirea aplicației (sau la apelul `/api/monitoring/recover-2pc`), `TwoPhaseCommitJournal` scanează jurnalul persistent:
    - **Presumed Abort**: Dacă o tranzacție s-a întrerupt înainte de decizia `GLOBAL_COMMIT` (în `BEGIN` sau `PREPARE_PHASE_1`), coordonatorul execută `UndoLog`-ul salvat în jurnal în ordine inversă pe PostgreSQL și MySQL și marchează tranzacția `ABORTED`.
    - **Forward Recovery**: Dacă o tranzacție avea deja decizia durabilă `GLOBAL_COMMIT` (ex. `POSTGRES_COMMITTED`, dar MySQL nu a apucat să finalizeze), coordonatorul re-aplică operațiile MySQL din jurnalul de redo pentru a duce tranzacția la `COMMITTED`.

---

## 7. Instrucțiuni de Pornire și Reproducere a Scenariilor

### Pasul 1: Pornirea bazelor de date (PostgreSQL și MySQL) cu Docker Compose
Din rădăcina proiectului (`distributed-2pl-ecommerce`):
```powershell
docker compose up -d
```

### Pasul 2: Rularea testelor automate JUnit (cele 8 scenarii obligatorii)
```powershell
.\app\mvnw.cmd -f .\app\pom.xml test
```

### Pasul 3: Pornirea aplicației Spring Boot și accesarea Interfeței Web
```powershell
.\app\mvnw.cmd -f .\app\pom.xml spring-boot:run
```
Deschide în browser: **http://localhost:8080**

### Cum poți reproduce manual fiecare scenariu în Interfața Web:
1. **Concurs pe ultimul produs din stoc (Race Condition)**:
   - În tab-ul **1. Dashboard & Simulator**, apasă pe **Run Last-Item Race (2 Threads)**.
   - Observă că stocul produsului `#4` devine `0`, o singură tranzacție este `COMMITTED`, iar a doua este `ABORTED`.
2. **Simularea și rezolvarea unui Deadlock (`T1 <-> T2`)**:
   - Apasă pe **Simulate Deadlock (T1 <-> T2)** din Dashboard sau din tab-ul **8. Deadlock Monitor**.
   - Mergi în **8. Deadlock Monitor** pentru a vedea ciclul detectat în Wait-For Graph (`TX-... -> TX-... -> TX-...`), victima selectată și rezolvarea.
3. **Simularea unui Rollback Distribuit (PostgreSQL + MySQL)**:
   - Apasă pe **Simulate Distributed Rollback** în Dashboard (sau în tab-ul **4. Orders**, bifează *Simulate failure after PG & MySQL writes* și plasează o comandă).
   - Mergi în **6. Transactions Monitor** și observă instrucțiunile din `UndoLog` executate în ordine inversă.
4. **Fluxul complet al celor 8 operații E-Commerce**:
   - Creează un utilizator (Tab 1), adaugă un produs (Tab 2), editează prețul (Tab 2), verifică stocul cu `S-Lock` (Tab 3), plasează o comandă distribuită (Tab 4), procesează plata `Pay (Op #6)`, anulează o comandă neplătită `Cancel (Op #7)` sau restituie plata și stocul `Refund (Op #8)`.


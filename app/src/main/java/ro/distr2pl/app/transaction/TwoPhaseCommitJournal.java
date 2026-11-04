package ro.distr2pl.app.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.distr2pl.app.model.DatabaseType;

import javax.sql.DataSource;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent Coordinator Write-Ahead Journal for the Two-Phase Commit (2PC) protocol.
 *
 * Addresses the Partial Commit / Coordinator Crash problem in distributed transactions:
 * - Two sequential JDBC commit() calls alone cannot guarantee atomic distributed commitment
 *   if the application crashes or loses connectivity between Participant 1 (PostgreSQL)
 *   and Participant 2 (MySQL).
 * - Before executing Phase 1 (PREPARE) and Phase 2 (COMMIT / ABORT), the Coordinator
 *   persists the transaction's UndoLog, Redo SQL operations, and 2PC phase transitions
 *   to disk (coordinator-2pc-journal.log).
 * - On application startup (or manual recovery trigger), {@link #recoverInDoubtTransactions()}
 *   inspects the durable journal:
 *   1. If a transaction crashed BEFORE the "GLOBAL_COMMIT" decision (e.g., in "BEGIN" or "PREPARE_PHASE_1"),
 *      the Coordinator enforces Presumed Abort and executes all persisted UndoLog entries in reverse order.
 *   2. If a transaction crashed AFTER the "GLOBAL_COMMIT" decision (e.g., "POSTGRES_COMMITTED",
 *      before "MYSQL_COMMITTED"), the Coordinator executes forward recovery (re-applying any pending
 *      participant SQL statements from the journal) so both databases converge to COMMITTED.
 */
@Component
public class TwoPhaseCommitJournal {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCommitJournal.class);

    @Value("${app.transaction.coordinator-journal-path:./target/coordinator-2pc-journal.log}")
    private String journalFilePath;

    private final RollbackManager rollbackManager;
    private final DataSource postgresDataSource;
    private final DataSource mysqlDataSource;
    private final ObjectMapper objectMapper;

    private final List<String> recoveryReports = new ArrayList<>();

    public TwoPhaseCommitJournal(RollbackManager rollbackManager,
                                 @Qualifier("postgresDataSource") DataSource postgresDataSource,
                                 @Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.rollbackManager = rollbackManager;
        this.postgresDataSource = postgresDataSource;
        this.mysqlDataSource = mysqlDataSource;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static class JournalRecord {
        private String transactionId;
        private String operationName;
        private String phase; // BEGIN, PREPARE_PHASE_1, GLOBAL_COMMIT, POSTGRES_COMMITTED, MYSQL_COMMITTED, COMPLETED, GLOBAL_ABORT, ABORTED
        private LocalDateTime timestamp;
        private List<UndoLogEntry> undoEntries = new ArrayList<>();
        private List<SqlOperation> pendingMysqlRedoOperations = new ArrayList<>();

        public JournalRecord() {
        }

        public JournalRecord(String transactionId, String operationName, String phase,
                             List<UndoLogEntry> undoEntries, List<SqlOperation> pendingMysqlRedoOperations) {
            this.transactionId = transactionId;
            this.operationName = operationName;
            this.phase = phase;
            this.timestamp = LocalDateTime.now();
            if (undoEntries != null) {
                this.undoEntries = new ArrayList<>(undoEntries);
            }
            if (pendingMysqlRedoOperations != null) {
                this.pendingMysqlRedoOperations = new ArrayList<>(pendingMysqlRedoOperations);
            }
        }

        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        public String getOperationName() {
            return operationName;
        }

        public void setOperationName(String operationName) {
            this.operationName = operationName;
        }

        public String getPhase() {
            return phase;
        }

        public void setPhase(String phase) {
            this.phase = phase;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }

        public List<UndoLogEntry> getUndoEntries() {
            return undoEntries;
        }

        public void setUndoEntries(List<UndoLogEntry> undoEntries) {
            this.undoEntries = undoEntries;
        }

        public List<SqlOperation> getPendingMysqlRedoOperations() {
            return pendingMysqlRedoOperations;
        }

        public void setPendingMysqlRedoOperations(List<SqlOperation> pendingMysqlRedoOperations) {
            this.pendingMysqlRedoOperations = pendingMysqlRedoOperations;
        }
    }

    @PostConstruct
    public void init() {
        try {
            Path path = Path.of(journalFilePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            recoverInDoubtTransactions();
        } catch (Exception e) {
            log.warn("Could not initialize or recover 2PC coordinator journal at {}: {}", journalFilePath, e.getMessage());
        }
    }

    /**
     * Appends a durable 2PC state transition record to the coordinator's disk journal.
     */
    public synchronized void appendRecord(String transactionId, String operationName, String phase,
                                          List<UndoLogEntry> undoEntries,
                                          List<SqlOperation> pendingMysqlRedoOperations) {
        try {
            JournalRecord record = new JournalRecord(
                    transactionId,
                    operationName,
                    phase,
                    undoEntries,
                    pendingMysqlRedoOperations
            );
            String jsonLine = objectMapper.writeValueAsString(record) + System.lineSeparator();
            Path path = Path.of(journalFilePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, jsonLine, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (Exception e) {
            log.error("Failed to write 2PC journal record for tx {}: {}", transactionId, e.getMessage(), e);
        }
    }

    /**
     * Scans the persistent 2PC Coordinator Journal and resolves any transactions that were
     * interrupted by a crash or partial commit failure.
     *
     * @return List of human-readable recovery actions performed.
     */
    public synchronized List<String> recoverInDoubtTransactions() {
        List<String> actions = new ArrayList<>();
        try {
            Path path = Path.of(journalFilePath);
            if (!Files.exists(path)) {
                return actions;
            }

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            Map<String, List<JournalRecord>> recordsByTx = new LinkedHashMap<>();

            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                JournalRecord rec = objectMapper.readValue(line, JournalRecord.class);
                recordsByTx.computeIfAbsent(rec.getTransactionId(), k -> new ArrayList<>()).add(rec);
            }

            for (Map.Entry<String, List<JournalRecord>> entry : recordsByTx.entrySet()) {
                String txId = entry.getKey();
                List<JournalRecord> history = entry.getValue();
                JournalRecord latest = history.get(history.size() - 1);
                String lastPhase = latest.getPhase();

                if ("COMPLETED".equals(lastPhase) || "ABORTED".equals(lastPhase)) {
                    // Transaction already cleanly terminated
                    continue;
                }

                // Find the latest snapshot of UndoEntries and RedoOperations for this txId
                List<UndoLogEntry> undoEntries = new ArrayList<>();
                List<SqlOperation> redoOps = new ArrayList<>();
                for (JournalRecord r : history) {
                    if (r.getUndoEntries() != null && !r.getUndoEntries().isEmpty()) {
                        undoEntries = r.getUndoEntries();
                    }
                    if (r.getPendingMysqlRedoOperations() != null && !r.getPendingMysqlRedoOperations().isEmpty()) {
                        redoOps = r.getPendingMysqlRedoOperations();
                    }
                }

                boolean hasGlobalCommit = history.stream()
                        .anyMatch(r -> "GLOBAL_COMMIT".equals(r.getPhase()) || "POSTGRES_COMMITTED".equals(r.getPhase()));

                if (!hasGlobalCommit) {
                    // Crash occurred BEFORE GLOBAL_COMMIT decision -> Presumed Abort: execute UndoLog in reverse order
                    List<UndoLogEntry> reversed = new ArrayList<>(undoEntries);
                    Collections.reverse(reversed);
                    for (UndoLogEntry undo : reversed) {
                        rollbackManager.executeUndoEntry(undo, null);
                    }
                    appendRecord(txId, latest.getOperationName(), "ABORTED", Collections.emptyList(), Collections.emptyList());
                    updateTransactionLogStatusInMysql(txId, "ABORTED", "RECOVERED_ABORT",
                            "Recovered after crash in phase " + lastPhase + ": rolled back via persistent UndoLog");
                    String msg = "2PC Recovery [PRESUMED ABORT]: Tx " + txId + " was left in phase '" + lastPhase
                            + "' before GLOBAL_COMMIT. Executed " + reversed.size() + " UndoLog operations and marked ABORTED.";
                    log.info(msg);
                    actions.add(msg);
                    recoveryReports.add(msg);
                } else {
                    // Crash occurred AFTER GLOBAL_COMMIT decision (e.g. POSTGRES_COMMITTED, MySQL pending) -> Forward Recovery!
                    for (SqlOperation redo : redoOps) {
                        executeRedoOperation(redo);
                    }
                    appendRecord(txId, latest.getOperationName(), "MYSQL_COMMITTED", Collections.emptyList(), Collections.emptyList());
                    appendRecord(txId, latest.getOperationName(), "COMPLETED", Collections.emptyList(), Collections.emptyList());
                    updateTransactionLogStatusInMysql(txId, "COMMITTED", "RECOVERED_COMMIT",
                            "Recovered partial commit from phase " + lastPhase + ": completed forward redo on participants");
                    String msg = "2PC Recovery [FORWARD COMMIT]: Tx " + txId + " had durable GLOBAL_COMMIT decision at phase '"
                            + lastPhase + "'. Replayed " + redoOps.size() + " pending operations and marked COMMITTED.";
                    log.info(msg);
                    actions.add(msg);
                    recoveryReports.add(msg);
                }
            }
        } catch (Exception e) {
            log.error("Error during 2PC journal recovery: {}", e.getMessage(), e);
        }
        return actions;
    }

    private void executeRedoOperation(SqlOperation op) {
        DataSource ds = (op.getDatabase() == DatabaseType.POSTGRESQL) ? postgresDataSource : mysqlDataSource;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(op.getSql())) {
            conn.setAutoCommit(true);
            List<Object> params = op.getParameters();
            for (int i = 0; i < params.size(); i++) {
                Object val = params.get(i);
                if (val instanceof Double d) {
                    ps.setBigDecimal(i + 1, BigDecimal.valueOf(d));
                } else {
                    ps.setObject(i + 1, val);
                }
            }
            ps.executeUpdate();
            log.info("2PC Forward Recovery replayed SQL on {}: {}", op.getDatabase(), op.getSql());
        } catch (Exception e) {
            log.error("Failed to replay redo operation {}: {}", op.getSql(), e.getMessage(), e);
        }
    }

    private void updateTransactionLogStatusInMysql(String txId, String status, String phase2pc, String note) {
        String sql = "UPDATE transaction_logs SET status = ?, phase_2pc = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP WHERE transaction_id = ?";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, phase2pc);
            ps.setString(3, note);
            ps.setString(4, txId);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public List<String> getRecoveryReports() {
        return Collections.unmodifiableList(recoveryReports);
    }

    public synchronized List<JournalRecord> readAllJournalRecords() {
        List<JournalRecord> list = new ArrayList<>();
        try {
            Path path = Path.of(journalFilePath);
            if (!Files.exists(path)) {
                return list;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line != null && !line.isBlank()) {
                    list.add(objectMapper.readValue(line, JournalRecord.class));
                }
            }
        } catch (Exception e) {
            log.warn("Could not read 2PC journal records: {}", e.getMessage());
        }
        return list;
    }
}


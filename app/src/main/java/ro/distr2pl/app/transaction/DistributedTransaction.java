package ro.distr2pl.app.transaction;

import ro.distr2pl.app.monitoring.TransactionEvent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a Distributed Transaction spanning PostgreSQL and MySQL,
 * tracking its lifecycle, executed SQL statements, Undo Log, acquired Strict 2PL locks,
 * Two-Phase Commit (2PC) state, and real-time step events.
 */
public class DistributedTransaction {
    private final String transactionId;
    private final String operationName;
    private volatile TransactionStatus status;
    private volatile String phase2pc; // NONE, PREPARE_PHASE_1, PREPARED, COMMIT_PHASE_2, COMMITTED, GLOBAL_ABORT
    private volatile boolean postgresPrepared;
    private volatile boolean mysqlPrepared;
    private volatile boolean postgresCommitted;
    private volatile boolean mysqlCommitted;

    private final UndoLog undoLog = new UndoLog();
    private final List<SqlOperation> sqlOperations = new CopyOnWriteArrayList<>();
    private final Set<String> acquiredLocks = new LinkedHashSet<>();
    private final List<TransactionEvent> events = new CopyOnWriteArrayList<>();

    private final LocalDateTime startedAt;
    private volatile LocalDateTime completedAt;
    private volatile String errorMessage;

    public DistributedTransaction(String transactionId, String operationName) {
        this.transactionId = transactionId;
        this.operationName = operationName;
        this.status = TransactionStatus.ACTIVE;
        this.phase2pc = "NONE";
        this.startedAt = LocalDateTime.now();
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getOperationName() {
        return operationName;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getPhase2pc() {
        return phase2pc;
    }

    public void setPhase2pc(String phase2pc) {
        this.phase2pc = phase2pc;
    }

    public boolean isPostgresPrepared() {
        return postgresPrepared;
    }

    public void setPostgresPrepared(boolean postgresPrepared) {
        this.postgresPrepared = postgresPrepared;
    }

    public boolean isMysqlPrepared() {
        return mysqlPrepared;
    }

    public void setMysqlPrepared(boolean mysqlPrepared) {
        this.mysqlPrepared = mysqlPrepared;
    }

    public boolean isPostgresCommitted() {
        return postgresCommitted;
    }

    public void setPostgresCommitted(boolean postgresCommitted) {
        this.postgresCommitted = postgresCommitted;
    }

    public boolean isMysqlCommitted() {
        return mysqlCommitted;
    }

    public void setMysqlCommitted(boolean mysqlCommitted) {
        this.mysqlCommitted = mysqlCommitted;
    }

    public UndoLog getUndoLog() {
        return undoLog;
    }

    public List<SqlOperation> getSqlOperations() {
        return sqlOperations;
    }

    public void addSqlOperation(SqlOperation op) {
        this.sqlOperations.add(op);
    }

    public synchronized Set<String> getAcquiredLocks() {
        return new LinkedHashSet<>(acquiredLocks);
    }

    public synchronized void recordAcquiredLock(String lockDescriptor) {
        this.acquiredLocks.add(lockDescriptor);
    }

    public List<TransactionEvent> getEvents() {
        return events;
    }

    public void addEvent(String eventType, String database, String details) {
        this.events.add(new TransactionEvent(transactionId, eventType, database, details));
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getDurationMs() {
        LocalDateTime end = completedAt != null ? completedAt : LocalDateTime.now();
        return Math.max(0, Duration.between(startedAt, end).toMillis());
    }
}


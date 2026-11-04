package ro.distr2pl.app.model;

import java.time.LocalDateTime;

public class TransactionLog {
    private String transactionId;
    private String operationName;
    private String status;
    private String phase2pc;
    private boolean postgresPrepared;
    private boolean mysqlPrepared;
    private String participants;
    private String sqlOperations;
    private String locksHeld;
    private String undoOperations;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long durationMs;

    public TransactionLog() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

    public String getParticipants() {
        return participants;
    }

    public void setParticipants(String participants) {
        this.participants = participants;
    }

    public String getSqlOperations() {
        return sqlOperations;
    }

    public void setSqlOperations(String sqlOperations) {
        this.sqlOperations = sqlOperations;
    }

    public String getLocksHeld() {
        return locksHeld;
    }

    public void setLocksHeld(String locksHeld) {
        this.locksHeld = locksHeld;
    }

    public String getUndoOperations() {
        return undoOperations;
    }

    public void setUndoOperations(String undoOperations) {
        this.undoOperations = undoOperations;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}


package ro.distr2pl.app.monitoring;

import java.time.LocalDateTime;

/**
 * Represents a step-by-step trace event inside a distributed transaction.
 */
public class TransactionEvent {
    private LocalDateTime timestamp;
    private String transactionId;
    private String eventType; // BEGIN, LOCK_REQUEST, LOCK_GRANTED, LOCK_WAIT, SQL_EXEC, UNDO_LOG, PREPARE_2PC, COMMIT_2PC, ROLLBACK, DEADLOCK
    private String database;
    private String details;

    public TransactionEvent() {
    }

    public TransactionEvent(String transactionId, String eventType, String database, String details) {
        this.timestamp = LocalDateTime.now();
        this.transactionId = transactionId;
        this.eventType = eventType;
        this.database = database;
        this.details = details;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}


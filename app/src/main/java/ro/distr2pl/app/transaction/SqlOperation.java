package ro.distr2pl.app.transaction;

import ro.distr2pl.app.model.DatabaseType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single SQL operation executed as part of a distributed transaction.
 */
public class SqlOperation {
    private int sequenceNumber;
    private DatabaseType database;
    private String operationType; // SELECT, INSERT, UPDATE, DELETE
    private String tableName;
    private String recordId;
    private String sql;
    private List<Object> parameters = new ArrayList<>();
    private LocalDateTime executedAt;

    public SqlOperation() {
    }

    public SqlOperation(int sequenceNumber, DatabaseType database, String operationType,
                        String tableName, String recordId, String sql, List<Object> parameters) {
        this.sequenceNumber = sequenceNumber;
        this.database = database;
        this.operationType = operationType;
        this.tableName = tableName;
        this.recordId = recordId;
        this.sql = sql;
        if (parameters != null) {
            this.parameters.addAll(parameters);
        }
        this.executedAt = LocalDateTime.now();
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public DatabaseType getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseType database) {
        this.database = database;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public void setParameters(List<Object> parameters) {
        this.parameters = parameters;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    @Override
    public String toString() {
        return String.format("#%d [%s] %s on %s (id=%s): %s | params=%s",
                sequenceNumber, database, operationType, tableName, recordId, sql, parameters);
    }
}


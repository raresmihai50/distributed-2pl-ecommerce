package ro.distr2pl.app.transaction;

import ro.distr2pl.app.concurrency.LockType;
import ro.distr2pl.app.model.DatabaseType;

import java.util.List;

/**
 * Declarative descriptor for a single SQL step inside a generic distributed transaction collection.
 */
public class DistributedSqlStep {
    private final DatabaseType database;
    private final String tableName;
    private final String recordId;
    private final LockType requiredLockType;
    private final String operationType; // SELECT, INSERT, UPDATE, DELETE
    private final String sql;
    private final List<Object> parameters;
    private final String modifiedColumn;
    private final Object oldValue;
    private final Object newValue;
    private final String inverseSql;
    private final List<Object> inverseParameters;

    public DistributedSqlStep(DatabaseType database, String tableName, String recordId,
                              LockType requiredLockType, String operationType,
                              String sql, List<Object> parameters,
                              String modifiedColumn, Object oldValue, Object newValue,
                              String inverseSql, List<Object> inverseParameters) {
        this.database = database;
        this.tableName = tableName;
        this.recordId = recordId;
        this.requiredLockType = requiredLockType;
        this.operationType = operationType;
        this.sql = sql;
        this.parameters = parameters;
        this.modifiedColumn = modifiedColumn;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.inverseSql = inverseSql;
        this.inverseParameters = inverseParameters;
    }

    public DatabaseType getDatabase() {
        return database;
    }

    public String getTableName() {
        return tableName;
    }

    public String getRecordId() {
        return recordId;
    }

    public LockType getRequiredLockType() {
        return requiredLockType;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public String getModifiedColumn() {
        return modifiedColumn;
    }

    public Object getOldValue() {
        return oldValue;
    }

    public Object getNewValue() {
        return newValue;
    }

    public String getInverseSql() {
        return inverseSql;
    }

    public List<Object> getInverseParameters() {
        return inverseParameters;
    }
}


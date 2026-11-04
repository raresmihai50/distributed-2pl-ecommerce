package ro.distr2pl.app.transaction;

import ro.distr2pl.app.model.DatabaseType;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the before-image and compensating inverse SQL operation before a data-modifying
 * SQL statement (INSERT, UPDATE, DELETE) is executed.
 *
 * Stored fields as required:
 * - operationOrder: sequence number within the transaction
 * - database: POSTGRESQL or MYSQL
 * - tableName: modified table name
 * - recordId: primary key of the modified row
 * - modifiedColumn: name(s) of the modified column(s)
 * - oldValue: previous value(s) before modification
 * - newValue: new value(s) after modification
 * - inverseSql: parameterized compensating SQL statement to restore the previous state
 * - inverseParameters: parameter values for the PreparedStatement of inverseSql
 */
public class UndoLogEntry {
    private int operationOrder;
    private DatabaseType database;
    private String tableName;
    private String recordId;
    private String modifiedColumn;
    private String oldValue;
    private String newValue;
    private String originalOperationType; // INSERT, UPDATE, DELETE
    private String inverseSql;
    private List<Object> inverseParameters = new ArrayList<>();

    public UndoLogEntry() {
    }

    public UndoLogEntry(int operationOrder, DatabaseType database, String tableName, String recordId,
                        String modifiedColumn, String oldValue, String newValue,
                        String originalOperationType, String inverseSql, List<Object> inverseParameters) {
        this.operationOrder = operationOrder;
        this.database = database;
        this.tableName = tableName;
        this.recordId = recordId;
        this.modifiedColumn = modifiedColumn;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.originalOperationType = originalOperationType;
        this.inverseSql = inverseSql;
        if (inverseParameters != null) {
            this.inverseParameters.addAll(inverseParameters);
        }
    }

    public int getOperationOrder() {
        return operationOrder;
    }

    public void setOperationOrder(int operationOrder) {
        this.operationOrder = operationOrder;
    }

    public DatabaseType getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseType database) {
        this.database = database;
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

    public String getModifiedColumn() {
        return modifiedColumn;
    }

    public void setModifiedColumn(String modifiedColumn) {
        this.modifiedColumn = modifiedColumn;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getOriginalOperationType() {
        return originalOperationType;
    }

    public void setOriginalOperationType(String originalOperationType) {
        this.originalOperationType = originalOperationType;
    }

    public String getInverseSql() {
        return inverseSql;
    }

    public void setInverseSql(String inverseSql) {
        this.inverseSql = inverseSql;
    }

    public List<Object> getInverseParameters() {
        return inverseParameters;
    }

    public void setInverseParameters(List<Object> inverseParameters) {
        this.inverseParameters = inverseParameters;
    }

    public String toSummary() {
        return String.format("Undo #%d [%s.%s id=%s col=%s: '%s' -> '%s'] InverseSQL: %s %s",
                operationOrder, database, tableName, recordId, modifiedColumn,
                oldValue, newValue, inverseSql, inverseParameters);
    }
}


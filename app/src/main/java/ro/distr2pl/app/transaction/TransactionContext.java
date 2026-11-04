package ro.distr2pl.app.transaction;

import ro.distr2pl.app.concurrency.LockManager;
import ro.distr2pl.app.concurrency.LockType;
import ro.distr2pl.app.model.DatabaseType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context passed to repositories and business logic during a distributed transaction.
 *
 * Provides:
 * - Access to dedicated PostgreSQL and MySQL JDBC Connections.
 * - Strict 2PL lock acquisition helpers (Shared S and Exclusive X).
 * - Automatic registration of executed SQL statements and UndoLog before-images.
 * - Fault-injection flags for testing 2PC failures and rollbacks.
 */
public class TransactionContext implements AutoCloseable {

    private final DistributedTransaction transaction;
    private final LockManager lockManager;
    private final DataSource postgresDataSource;
    private final DataSource mysqlDataSource;

    private Connection postgresConnection;
    private Connection mysqlConnection;

    // resourceId -> minimum LockType required by this transaction (checked during 2PC Prepare)
    private final Map<String, LockType> requiredResourceLocks = new LinkedHashMap<>();

    // Optional fault injection flags for automated JUnit testing & UI simulation
    private boolean simulateMysqlPrepareFailure = false;
    private boolean simulatePartialCommitFailureOnMysql = false;

    public TransactionContext(DistributedTransaction transaction,
                              LockManager lockManager,
                              DataSource postgresDataSource,
                              DataSource mysqlDataSource) {
        this.transaction = transaction;
        this.lockManager = lockManager;
        this.postgresDataSource = postgresDataSource;
        this.mysqlDataSource = mysqlDataSource;
    }

    public String getTransactionId() {
        return transaction.getTransactionId();
    }

    public DistributedTransaction getTransaction() {
        return transaction;
    }

    /**
     * Acquires a Shared (S) lock on the global resource (DATABASE:table:recordId)
     * before executing a SELECT operation.
     */
    public void lockShared(DatabaseType db, String table, Object recordId) {
        String resourceId = db.resourceId(table, recordId);
        transaction.addEvent("LOCK_REQUEST", db.name(), "Requesting SHARED (S) lock on " + resourceId);
        lockManager.acquireLock(getTransactionId(), resourceId, LockType.SHARED);
        requiredResourceLocks.putIfAbsent(resourceId, LockType.SHARED);
        transaction.recordAcquiredLock("S(" + resourceId + ")");
        transaction.addEvent("LOCK_GRANTED", db.name(), "Granted SHARED (S) lock on " + resourceId);
    }

    /**
     * Acquires an Exclusive (X) lock on the global resource (DATABASE:table:recordId)
     * before executing an INSERT, UPDATE, or DELETE operation.
     */
    public void lockExclusive(DatabaseType db, String table, Object recordId) {
        String resourceId = db.resourceId(table, recordId);
        transaction.addEvent("LOCK_REQUEST", db.name(), "Requesting EXCLUSIVE (X) lock on " + resourceId);
        lockManager.acquireLock(getTransactionId(), resourceId, LockType.EXCLUSIVE);
        requiredResourceLocks.put(resourceId, LockType.EXCLUSIVE);
        transaction.recordAcquiredLock("X(" + resourceId + ")");
        transaction.addEvent("LOCK_GRANTED", db.name(), "Granted EXCLUSIVE (X) lock on " + resourceId);
    }

    /**
     * Records a SQL operation in the transaction trace.
     */
    public void recordSql(DatabaseType db, String operationType, String tableName, Object recordId,
                          String sql, List<Object> params) {
        int seq = transaction.getSqlOperations().size() + 1;
        SqlOperation op = new SqlOperation(
                seq,
                db,
                operationType,
                tableName,
                String.valueOf(recordId),
                sql,
                params
        );
        transaction.addSqlOperation(op);
        transaction.addEvent("SQL_EXEC", db.name(), op.toString());
    }

    /**
     * Records an UndoLogEntry before-image prior to modifying data.
     */
    public void recordUndo(DatabaseType db, String tableName, Object recordId,
                           String modifiedColumn, Object oldValue, Object newValue,
                           String originalOperationType, String inverseSql, List<Object> inverseParams) {
        int order = transaction.getUndoLog().size() + 1;
        UndoLogEntry entry = new UndoLogEntry(
                order,
                db,
                tableName,
                String.valueOf(recordId),
                modifiedColumn,
                oldValue != null ? String.valueOf(oldValue) : "NULL",
                newValue != null ? String.valueOf(newValue) : "NULL",
                originalOperationType,
                inverseSql,
                inverseParams
        );
        transaction.getUndoLog().addEntry(entry);
        transaction.addEvent("UNDO_LOG", db.name(), entry.toSummary());
    }

    public Connection getPostgresConnection() throws SQLException {
        if (postgresConnection == null || postgresConnection.isClosed()) {
            postgresConnection = postgresDataSource.getConnection();
            postgresConnection.setAutoCommit(true);
        }
        return postgresConnection;
    }

    public Connection getMysqlConnection() throws SQLException {
        if (mysqlConnection == null || mysqlConnection.isClosed()) {
            mysqlConnection = mysqlDataSource.getConnection();
            mysqlConnection.setAutoCommit(true);
        }
        return mysqlConnection;
    }

    public Map<String, LockType> getRequiredResourceLocks() {
        return requiredResourceLocks;
    }

    public boolean isSimulateMysqlPrepareFailure() {
        return simulateMysqlPrepareFailure;
    }

    public void setSimulateMysqlPrepareFailure(boolean simulateMysqlPrepareFailure) {
        this.simulateMysqlPrepareFailure = simulateMysqlPrepareFailure;
    }

    public boolean isSimulatePartialCommitFailureOnMysql() {
        return simulatePartialCommitFailureOnMysql;
    }

    public void setSimulatePartialCommitFailureOnMysql(boolean simulatePartialCommitFailureOnMysql) {
        this.simulatePartialCommitFailureOnMysql = simulatePartialCommitFailureOnMysql;
    }

    @Override
    public void close() {
        if (postgresConnection != null) {
            try {
                postgresConnection.close();
            } catch (SQLException ignored) {
            }
            postgresConnection = null;
        }
        if (mysqlConnection != null) {
            try {
                mysqlConnection.close();
            } catch (SQLException ignored) {
            }
            mysqlConnection = null;
        }
    }
}


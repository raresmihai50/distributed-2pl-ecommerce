package ro.distr2pl.app.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ro.distr2pl.app.concurrency.LockManager;
import ro.distr2pl.app.model.DatabaseType;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Custom Application-Level Rollback Manager based on an Undo Log.
 *
 * Does NOT rely on Spring @Transactional or Hibernate rollback.
 * Instead, before any modifying SQL statement (INSERT, UPDATE, DELETE) is executed,
 * its before-image and compensating SQL are recorded in the transaction's UndoLog.
 *
 * When a transaction receives ABORT:
 * 1. Iterates through the UndoLog entries in strict reverse order (LIFO).
 * 2. Executes the inverse SQL statements (DELETE for INSERT, UPDATE with old values for UPDATE,
 *    INSERT with old values for DELETE) across both PostgreSQL and MySQL.
 * 3. Closes JDBC connections.
 * 4. Releases all Strict 2PL locks held by the transaction in LockManager.
 * 5. Marks the transaction status as ABORTED.
 */
@Component
public class RollbackManager {

    private static final Logger log = LoggerFactory.getLogger(RollbackManager.class);

    private final LockManager lockManager;
    private final DataSource postgresDataSource;
    private final DataSource mysqlDataSource;

    public RollbackManager(LockManager lockManager,
                           @Qualifier("postgresDataSource") DataSource postgresDataSource,
                           @Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.lockManager = lockManager;
        this.postgresDataSource = postgresDataSource;
        this.mysqlDataSource = mysqlDataSource;
    }

    /**
     * Rolls back a distributed transaction by executing its UndoLog inverse SQL operations
     * in reverse chronological order across PostgreSQL and MySQL, then releasing all locks.
     */
    public void rollback(TransactionContext context, String reason) {
        DistributedTransaction tx = context.getTransaction();
        String txId = tx.getTransactionId();

        tx.setStatus(TransactionStatus.ABORTING);
        tx.setPhase2pc("GLOBAL_ABORT");
        tx.setErrorMessage(reason);
        tx.addEvent("ROLLBACK_START", "COORDINATOR", "Initiating Rollback via UndoLog. Reason: " + reason);

        log.warn("Rolling back distributed transaction [{}]. Reason: {}", txId, reason);

        List<UndoLogEntry> reverseEntries = tx.getUndoLog().getEntriesInReverseOrder();
        for (UndoLogEntry entry : reverseEntries) {
            executeUndoEntry(entry, tx);
        }

        // Close transaction connections first
        context.close();

        // Strict 2PL: Release all locks ONLY after all undo operations have been applied
        lockManager.releaseAllLocks(txId);
        tx.addEvent("LOCK_RELEASE", "COORDINATOR", "Released all Strict 2PL locks after Rollback completion");

        tx.setStatus(TransactionStatus.ABORTED);
        tx.setCompletedAt(LocalDateTime.now());
        tx.addEvent("ROLLBACK_COMPLETE", "COORDINATOR",
                "Transaction marked ABORTED. Executed " + reverseEntries.size() + " UndoLog operations.");
    }

    /**
     * Executes a single UndoLogEntry inverse SQL operation on its target database (PostgreSQL or MySQL).
     * Can be invoked during live rollback or during startup crash recovery.
     */
    public void executeUndoEntry(UndoLogEntry entry, DistributedTransaction tx) {
        DataSource targetDs = (entry.getDatabase() == DatabaseType.POSTGRESQL)
                ? postgresDataSource
                : mysqlDataSource;

        try (Connection conn = targetDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(entry.getInverseSql())) {

            conn.setAutoCommit(true);
            List<Object> params = entry.getInverseParameters();
            for (int i = 0; i < params.size(); i++) {
                bindParameter(ps, i + 1, params.get(i));
            }

            int affected = ps.executeUpdate();
            String msg = String.format("Executed Undo #%d on %s.%s (id=%s): %s | params=%s (rows=%d)",
                    entry.getOperationOrder(), entry.getDatabase(), entry.getTableName(),
                    entry.getRecordId(), entry.getInverseSql(), params, affected);
            log.info("Tx [{}] {}", tx != null ? tx.getTransactionId() : "RECOVERY", msg);
            if (tx != null) {
                tx.addEvent("UNDO_EXEC", entry.getDatabase().name(), msg);
            }
        } catch (Exception e) {
            String err = String.format("Failed to execute Undo #%d [%s]: %s",
                    entry.getOperationOrder(), entry.getInverseSql(), e.getMessage());
            log.error(err, e);
            if (tx != null) {
                tx.addEvent("UNDO_ERROR", entry.getDatabase().name(), err);
            }
        }
    }

    private void bindParameter(PreparedStatement ps, int index, Object value) throws Exception {
        if (value == null) {
            ps.setObject(index, null);
        } else if (value instanceof Integer intVal) {
            ps.setInt(index, intVal);
        } else if (value instanceof Long longVal) {
            ps.setLong(index, longVal);
        } else if (value instanceof BigDecimal bdVal) {
            ps.setBigDecimal(index, bdVal);
        } else if (value instanceof Double dblVal) {
            ps.setBigDecimal(index, BigDecimal.valueOf(dblVal));
        } else if (value instanceof Boolean boolVal) {
            ps.setBoolean(index, boolVal);
        } else {
            ps.setObject(index, value);
        }
    }
}


package ro.distr2pl.app.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ro.distr2pl.app.concurrency.LockManager;
import ro.distr2pl.app.concurrency.LockType;
import ro.distr2pl.app.exception.DistributedTransactionException;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.repository.mysql.TransactionLogRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates the execution of distributed transactions across PostgreSQL and MySQL,
 * integrating:
 * - Transaction identification & registration
 * - Strict Two-Phase Locking (Strict 2PL) via LockManager
 * - Wait-For Graph Deadlock Detection via DeadlockDetector
 * - Before-image Undo Log & Rollback via RollbackManager
 * - Two-Phase Commit (2PC) & durable journal via CommitManager & TwoPhaseCommitJournal
 * - Persistent audit logging in MySQL {@code transaction_logs}
 */
@Component
public class TransactionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TransactionCoordinator.class);

    private final LockManager lockManager;
    private final CommitManager commitManager;
    private final RollbackManager rollbackManager;
    private final TwoPhaseCommitJournal journal;
    private final TransactionLogRepository transactionLogRepository;
    private final DataSource postgresDataSource;
    private final DataSource mysqlDataSource;

    private final AtomicLong txSequence = new AtomicLong(1000);
    private final Map<String, DistributedTransaction> activeTransactions = new ConcurrentHashMap<>();
    private final List<DistributedTransaction> transactionHistory = new CopyOnWriteArrayList<>();

    public TransactionCoordinator(LockManager lockManager,
                                  CommitManager commitManager,
                                  RollbackManager rollbackManager,
                                  TwoPhaseCommitJournal journal,
                                  TransactionLogRepository transactionLogRepository,
                                  @Qualifier("postgresDataSource") DataSource postgresDataSource,
                                  @Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.lockManager = lockManager;
        this.commitManager = commitManager;
        this.rollbackManager = rollbackManager;
        this.journal = journal;
        this.transactionLogRepository = transactionLogRepository;
        this.postgresDataSource = postgresDataSource;
        this.mysqlDataSource = mysqlDataSource;
    }

    /**
     * Starts a new Distributed Transaction and initializes its TransactionContext.
     */
    public TransactionContext begin(String operationName) {
        String txId = "TX-" + txSequence.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        DistributedTransaction tx = new DistributedTransaction(txId, operationName);
        tx.addEvent("BEGIN", "COORDINATOR", "Started Distributed Transaction [" + txId + "] for " + operationName);

        lockManager.registerTransaction(txId);
        activeTransactions.put(txId, tx);
        transactionHistory.add(0, tx);

        journal.appendRecord(txId, operationName, "BEGIN", Collections.emptyList(), Collections.emptyList());
        transactionLogRepository.saveOrUpdate(tx);

        log.info("BEGIN Distributed Transaction [{}] - Operation: {}", txId, operationName);
        return new TransactionContext(tx, lockManager, postgresDataSource, mysqlDataSource);
    }

    /**
     * Coordinates the 2PC Commit of an active TransactionContext.
     */
    public void commit(TransactionContext context) {
        DistributedTransaction tx = context.getTransaction();
        try {
            commitManager.commit(context);
        } finally {
            activeTransactions.remove(tx.getTransactionId());
            transactionLogRepository.saveOrUpdate(tx);
        }
    }

    /**
     * Coordinates the UndoLog Rollback of an active TransactionContext.
     */
    public void rollback(TransactionContext context, String reason) {
        DistributedTransaction tx = context.getTransaction();
        try {
            if (tx.getStatus() != TransactionStatus.ABORTED && tx.getStatus() != TransactionStatus.COMMITTED) {
                journal.appendRecord(tx.getTransactionId(), tx.getOperationName(), "GLOBAL_ABORT",
                        tx.getUndoLog().getEntries(), Collections.emptyList());
                rollbackManager.rollback(context, reason);
                journal.appendRecord(tx.getTransactionId(), tx.getOperationName(), "ABORTED",
                        Collections.emptyList(), Collections.emptyList());
            }
        } finally {
            activeTransactions.remove(tx.getTransactionId());
            transactionLogRepository.saveOrUpdate(tx);
        }
    }

    /**
     * Generic mechanism to execute a collection of SQL steps across both PostgreSQL and MySQL
     * within a single atomic distributed transaction under Strict 2PL and 2PC.
     */
    public DistributedTransaction executeSqlCollection(String operationName, List<DistributedSqlStep> steps) {
        TransactionContext context = begin(operationName);
        try {
            for (DistributedSqlStep step : steps) {
                // 1. Acquire Strict 2PL lock (S or X)
                if (step.getRequiredLockType() == LockType.SHARED) {
                    context.lockShared(step.getDatabase(), step.getTableName(), step.getRecordId());
                } else {
                    context.lockExclusive(step.getDatabase(), step.getTableName(), step.getRecordId());
                }

                // 2. Record UndoLog entry before executing modifying SQL
                if (!"SELECT".equalsIgnoreCase(step.getOperationType()) && step.getInverseSql() != null) {
                    context.recordUndo(
                            step.getDatabase(),
                            step.getTableName(),
                            step.getRecordId(),
                            step.getModifiedColumn(),
                            step.getOldValue(),
                            step.getNewValue(),
                            step.getOperationType(),
                            step.getInverseSql(),
                            step.getInverseParameters()
                    );
                }

                // 3. Execute the SQL statement on the target database connection
                Connection conn = (step.getDatabase() == DatabaseType.POSTGRESQL)
                        ? context.getPostgresConnection()
                        : context.getMysqlConnection();

                try (PreparedStatement ps = conn.prepareStatement(step.getSql())) {
                    List<Object> params = step.getParameters();
                    if (params != null) {
                        for (int i = 0; i < params.size(); i++) {
                            Object val = params.get(i);
                            if (val instanceof BigDecimal bd) {
                                ps.setBigDecimal(i + 1, bd);
                            } else {
                                ps.setObject(i + 1, val);
                            }
                        }
                    }
                    if ("SELECT".equalsIgnoreCase(step.getOperationType())) {
                        ps.executeQuery().close();
                    } else {
                        ps.executeUpdate();
                    }
                }

                context.recordSql(
                        step.getDatabase(),
                        step.getOperationType(),
                        step.getTableName(),
                        step.getRecordId(),
                        step.getSql(),
                        step.getParameters()
                );
            }

            commit(context);
            return context.getTransaction();
        } catch (Exception e) {
            rollback(context, e.getMessage());
            if (e instanceof DistributedTransactionException dte) {
                throw dte;
            }
            throw new DistributedTransactionException(context.getTransactionId(), e.getMessage(), e);
        }
    }

    public List<DistributedTransaction> getActiveTransactions() {
        return new ArrayList<>(activeTransactions.values());
    }

    public List<DistributedTransaction> getTransactionHistory() {
        return Collections.unmodifiableList(transactionHistory);
    }
}


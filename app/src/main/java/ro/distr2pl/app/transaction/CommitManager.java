package ro.distr2pl.app.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ro.distr2pl.app.concurrency.LockManager;
import ro.distr2pl.app.concurrency.LockType;
import ro.distr2pl.app.exception.TwoPhaseCommitException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * Custom Application-Level Commit Manager implementing the Two-Phase Commit (2PC) protocol
 * across PostgreSQL and MySQL, backed by {@link TwoPhaseCommitJournal}.
 *
 * Protocol Phases:
 * =========================================================================
 * PHASE 1 - PREPARE (Voting Phase):
 * 1. Verifies the transaction is ACTIVE and all executed SQL operations succeeded.
 * 2. Verifies in LockManager that all required Strict 2PL locks (S and X) are still held.
 * 3. Persists PREPARE_PHASE_1 + UndoLog in the durable coordinator journal.
 * 4. Polls Participant 1 (PostgreSQL) for readiness (validates connection & health).
 * 5. Polls Participant 2 (MySQL) for readiness (validates connection & health, checks fault injection).
 * 6. If any participant votes NO or fails validation -> writes GLOBAL_ABORT and invokes RollbackManager.
 * 7. If both vote YES -> records durable GLOBAL_COMMIT decision in TwoPhaseCommitJournal.
 *
 * PHASE 2 - COMMIT (Completion Phase):
 * 1. Confirms commit on Participant 1 (PostgreSQL) and logs POSTGRES_COMMITTED.
 * 2. Confirms commit on Participant 2 (MySQL) and logs MYSQL_COMMITTED.
 *    (If a partial commit failure occurs after PostgreSQL committed, records the state so
 *    TwoPhaseCommitJournal forward recovery can replay/reconcile).
 * 3. Marks the transaction COMMITTED and logs COMPLETED in the journal.
 * 4. Releases all Strict 2PL locks in LockManager.
 */
@Component
public class CommitManager {

    private static final Logger log = LoggerFactory.getLogger(CommitManager.class);

    private final LockManager lockManager;
    private final RollbackManager rollbackManager;
    private final TwoPhaseCommitJournal journal;
    private final DataSource postgresDataSource;
    private final DataSource mysqlDataSource;

    public CommitManager(LockManager lockManager,
                         RollbackManager rollbackManager,
                         TwoPhaseCommitJournal journal,
                         @Qualifier("postgresDataSource") DataSource postgresDataSource,
                         @Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.lockManager = lockManager;
        this.rollbackManager = rollbackManager;
        this.journal = journal;
        this.postgresDataSource = postgresDataSource;
        this.mysqlDataSource = mysqlDataSource;
    }

    /**
     * Executes the Two-Phase Commit (2PC) protocol for the given transaction context.
     */
    public void commit(TransactionContext context) {
        DistributedTransaction tx = context.getTransaction();
        String txId = tx.getTransactionId();

        // =========================================================================
        // PHASE 1: PREPARE (VOTING PHASE)
        // =========================================================================
        tx.setStatus(TransactionStatus.PREPARING);
        tx.setPhase2pc("PREPARE_PHASE_1");
        tx.addEvent("PREPARE_2PC", "COORDINATOR", "Starting 2PC Phase 1 (PREPARE) across PostgreSQL and MySQL");

        journal.appendRecord(txId, tx.getOperationName(), "PREPARE_PHASE_1",
                tx.getUndoLog().getEntries(), Collections.emptyList());

        try {
            // 1. Verify SQL operations were executed
            if (tx.getSqlOperations().isEmpty()) {
                throw new TwoPhaseCommitException(txId, "PREPARE", "Cannot commit empty distributed transaction");
            }

            // 2. Verify all required Strict 2PL locks are still owned by this transaction
            for (Map.Entry<String, LockType> reqLock : context.getRequiredResourceLocks().entrySet()) {
                if (!lockManager.holdsLock(txId, reqLock.getKey(), reqLock.getValue())) {
                    throw new TwoPhaseCommitException(txId, "PREPARE",
                            "Missing required Strict 2PL lock " + reqLock.getValue() + " on " + reqLock.getKey());
                }
            }

            // 3. Prepare Participant 1: PostgreSQL
            boolean pgVote = preparePostgresParticipant(context);
            if (!pgVote) {
                throw new TwoPhaseCommitException(txId, "PREPARE", "Participant POSTGRESQL voted NO during Prepare Phase");
            }
            tx.setPostgresPrepared(true);
            tx.addEvent("PREPARE_2PC", "POSTGRESQL", "Participant POSTGRESQL voted YES (PREPARED)");

            // 4. Prepare Participant 2: MySQL
            if (context.isSimulateMysqlPrepareFailure()) {
                throw new TwoPhaseCommitException(txId, "PREPARE",
                        "Simulated communication/prepare failure on Participant MYSQL during 2PC Phase 1");
            }
            boolean mysqlVote = prepareMysqlParticipant(context);
            if (!mysqlVote) {
                throw new TwoPhaseCommitException(txId, "PREPARE", "Participant MYSQL voted NO during Prepare Phase");
            }
            tx.setMysqlPrepared(true);
            tx.addEvent("PREPARE_2PC", "MYSQL", "Participant MYSQL voted YES (PREPARED)");

            // Both participants voted YES -> Durable GLOBAL_COMMIT decision (Point of No Return)
            tx.setStatus(TransactionStatus.PREPARED);
            tx.setPhase2pc("PREPARED");
            journal.appendRecord(txId, tx.getOperationName(), "GLOBAL_COMMIT",
                    tx.getUndoLog().getEntries(), Collections.emptyList());
            tx.addEvent("PREPARE_2PC", "COORDINATOR",
                    "Both participants prepared. Logged durable GLOBAL_COMMIT decision in 2PC Journal.");

        } catch (Exception prepareEx) {
            log.warn("2PC Phase 1 (PREPARE) failed for tx [{}]: {}. Initiating Global Abort & Rollback.",
                    txId, prepareEx.getMessage());
            journal.appendRecord(txId, tx.getOperationName(), "GLOBAL_ABORT",
                    tx.getUndoLog().getEntries(), Collections.emptyList());
            rollbackManager.rollback(context, "2PC Phase 1 (PREPARE) Failure: " + prepareEx.getMessage());
            journal.appendRecord(txId, tx.getOperationName(), "ABORTED",
                    Collections.emptyList(), Collections.emptyList());
            if (prepareEx instanceof TwoPhaseCommitException tpcEx) {
                throw tpcEx;
            }
            throw new TwoPhaseCommitException(txId, "PREPARE", prepareEx.getMessage(), prepareEx);
        }

        // =========================================================================
        // PHASE 2: COMMIT (COMPLETION PHASE)
        // =========================================================================
        tx.setStatus(TransactionStatus.COMMITTING);
        tx.setPhase2pc("COMMIT_PHASE_2");
        tx.addEvent("COMMIT_2PC", "COORDINATOR", "Starting 2PC Phase 2 (COMMIT)");

        try {
            // 1. Confirm Commit on Participant 1: PostgreSQL
            confirmPostgresCommit(context);
            tx.setPostgresCommitted(true);
            journal.appendRecord(txId, tx.getOperationName(), "POSTGRES_COMMITTED",
                    tx.getUndoLog().getEntries(), Collections.emptyList());
            tx.addEvent("COMMIT_2PC", "POSTGRESQL", "Participant POSTGRESQL confirmed COMMIT");

            // Check if a simulated crash/communication failure happens mid-Phase-2 (Partial Commit scenario)
            if (context.isSimulatePartialCommitFailureOnMysql()) {
                log.error("Tx [{}] simulated Partial Commit interruption after POSTGRES_COMMITTED and before MYSQL_COMMITTED!", txId);
                tx.addEvent("COMMIT_2PC", "MYSQL",
                        "PARTIAL COMMIT INTERRUPTED after PostgreSQL committed! Coordinator journal holds GLOBAL_COMMIT for recovery.");
                throw new TwoPhaseCommitException(txId, "COMMIT_PHASE_2",
                        "Partial Commit failure: PostgreSQL committed, but MySQL communication dropped in Phase 2. Pending recovery.");
            }

            // 2. Confirm Commit on Participant 2: MySQL
            confirmMysqlCommit(context);
            tx.setMysqlCommitted(true);
            journal.appendRecord(txId, tx.getOperationName(), "MYSQL_COMMITTED",
                    Collections.emptyList(), Collections.emptyList());
            tx.addEvent("COMMIT_2PC", "MYSQL", "Participant MYSQL confirmed COMMIT");

            // 3. Mark transaction COMPLETED in journal and COMMITTED in memory
            journal.appendRecord(txId, tx.getOperationName(), "COMPLETED",
                    Collections.emptyList(), Collections.emptyList());
            tx.setStatus(TransactionStatus.COMMITTED);
            tx.setPhase2pc("COMMITTED");
            tx.setCompletedAt(LocalDateTime.now());
            tx.addEvent("COMMIT_2PC", "COORDINATOR",
                    "2PC Phase 2 completed successfully. Transaction is globally COMMITTED.");

        } catch (Exception commitEx) {
            if (commitEx instanceof TwoPhaseCommitException tpcEx) {
                throw tpcEx;
            }
            throw new TwoPhaseCommitException(txId, "COMMIT_PHASE_2", commitEx.getMessage(), commitEx);
        } finally {
            // Close JDBC connections and release all Strict 2PL locks
            context.close();
            lockManager.releaseAllLocks(txId);
            tx.addEvent("LOCK_RELEASE", "COORDINATOR", "Released all Strict 2PL locks after 2PC Commit phase");
        }
    }

    private boolean preparePostgresParticipant(TransactionContext context) {
        try {
            Connection conn = context.getPostgresConnection();
            if (conn == null || conn.isClosed()) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            log.error("PostgreSQL Prepare check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean prepareMysqlParticipant(TransactionContext context) {
        try {
            Connection conn = context.getMysqlConnection();
            if (conn == null || conn.isClosed()) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            log.error("MySQL Prepare check failed: {}", e.getMessage());
            return false;
        }
    }

    private void confirmPostgresCommit(TransactionContext context) throws Exception {
        Connection conn = context.getPostgresConnection();
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }

    private void confirmMysqlCommit(TransactionContext context) throws Exception {
        Connection conn = context.getMysqlConnection();
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }
}

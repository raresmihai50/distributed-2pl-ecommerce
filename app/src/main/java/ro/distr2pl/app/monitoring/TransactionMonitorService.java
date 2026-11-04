package ro.distr2pl.app.monitoring;

import org.springframework.stereotype.Service;
import ro.distr2pl.app.concurrency.DeadlockDetector;
import ro.distr2pl.app.concurrency.LockManager;
import ro.distr2pl.app.repository.mysql.TransactionLogRepository;
import ro.distr2pl.app.transaction.DistributedTransaction;
import ro.distr2pl.app.transaction.TransactionCoordinator;
import ro.distr2pl.app.transaction.TransactionStatus;
import ro.distr2pl.app.transaction.TwoPhaseCommitJournal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates real-time metrics and snapshots from the TransactionCoordinator,
 * LockManager, DeadlockDetector, and TwoPhaseCommitJournal for the monitoring UI.
 */
@Service
public class TransactionMonitorService {

    private final TransactionCoordinator coordinator;
    private final LockManager lockManager;
    private final DeadlockDetector deadlockDetector;
    private final TransactionLogRepository transactionLogRepository;
    private final TwoPhaseCommitJournal journal;

    public TransactionMonitorService(TransactionCoordinator coordinator,
                                     LockManager lockManager,
                                     DeadlockDetector deadlockDetector,
                                     TransactionLogRepository transactionLogRepository,
                                     TwoPhaseCommitJournal journal) {
        this.coordinator = coordinator;
        this.lockManager = lockManager;
        this.deadlockDetector = deadlockDetector;
        this.transactionLogRepository = transactionLogRepository;
        this.journal = journal;
    }

    public Map<String, Object> getFullMonitoringSnapshot() {
        List<DistributedTransaction> history = coordinator.getTransactionHistory();
        List<DistributedTransaction> active = coordinator.getActiveTransactions();

        long committedCount = history.stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.COMMITTED)
                .count();
        long abortedCount = history.stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.ABORTED)
                .count();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("activeTransactionsCount", active.size());
        snapshot.put("committedTransactionsCount", committedCount);
        snapshot.put("abortedTransactionsCount", abortedCount);
        snapshot.put("deadlocksDetectedCount", deadlockDetector.getDeadlockHistory().size());

        snapshot.put("activeTransactions", active);
        snapshot.put("recentTransactions", history);
        snapshot.put("persistedTransactionLogs", transactionLogRepository.findAll());

        snapshot.put("activeLocks", lockManager.getActiveLocksSnapshot());
        snapshot.put("waitingLockRequests", lockManager.getWaitingRequestsSnapshot());
        snapshot.put("waitForGraph", deadlockDetector.getWaitForGraphSnapshot());
        snapshot.put("deadlockEvents", deadlockDetector.getDeadlockHistory());
        snapshot.put("recoveryReports", journal.getRecoveryReports());

        return snapshot;
    }
}


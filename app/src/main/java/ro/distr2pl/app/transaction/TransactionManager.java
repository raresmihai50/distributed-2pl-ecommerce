package ro.distr2pl.app.transaction;

import org.springframework.stereotype.Component;
import ro.distr2pl.app.exception.DistributedTransactionException;

import java.util.List;

/**
 * High-level custom Transaction Manager providing both programmatic callback execution
 * and generic SQL collection execution across PostgreSQL and MySQL.
 *
 * Replaces Spring @Transactional and JTA completely with our manual implementation of:
 * - Strict Two-Phase Locking (Strict 2PL)
 * - Wait-For Graph Deadlock Detection & Victim Selection
 * - Before-Image Undo Log & Rollback Manager
 * - Two-Phase Commit (2PC) with persistent Coordinator Journal
 */
@Component
public class TransactionManager {

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T doInTransaction(TransactionContext context) throws Exception;
    }

    private final TransactionCoordinator coordinator;

    public TransactionManager(TransactionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * Executes a business operation inside a managed Distributed Transaction.
     * Automatically commits via 2PC if all operations succeed, or rolls back via UndoLog on any failure.
     */
    public <T> T execute(String operationName, TransactionCallback<T> callback) {
        TransactionContext context = coordinator.begin(operationName);
        try {
            T result = callback.doInTransaction(context);
            coordinator.commit(context);
            return result;
        } catch (Exception e) {
            coordinator.rollback(context, e.getMessage());
            if (e instanceof DistributedTransactionException dte) {
                throw dte;
            }
            throw new DistributedTransactionException(context.getTransactionId(),
                    "Transaction [" + context.getTransactionId() + "] aborted and rolled back: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a declarative collection of SQL steps across PostgreSQL and MySQL.
     */
    public DistributedTransaction executeSqlSteps(String operationName, List<DistributedSqlStep> steps) {
        return coordinator.executeSqlCollection(operationName, steps);
    }

    public TransactionContext begin(String operationName) {
        return coordinator.begin(operationName);
    }

    public void commit(TransactionContext context) {
        coordinator.commit(context);
    }

    public void rollback(TransactionContext context, String reason) {
        coordinator.rollback(context, reason);
    }
}


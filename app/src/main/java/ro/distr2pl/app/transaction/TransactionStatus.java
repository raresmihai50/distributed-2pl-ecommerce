package ro.distr2pl.app.transaction;

/**
 * Lifecycle states of a Distributed Transaction managed by the application coordinator.
 */
public enum TransactionStatus {
    ACTIVE,
    PREPARING,
    PREPARED,
    COMMITTING,
    COMMITTED,
    ABORTING,
    ABORTED
}


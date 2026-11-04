package ro.distr2pl.app.concurrency;

/**
 * Lock types supported by the Strict Two-Phase Locking (Strict 2PL) protocol.
 *
 * Lock Compatibility Matrix:
 * ---------------------------------
 * Requested \ Held | SHARED | EXCLUSIVE
 * ---------------------------------
 * SHARED (S)       |  YES   |    NO
 * EXCLUSIVE (X)    |  NO    |    NO
 * ---------------------------------
 */
public enum LockType {
    SHARED,
    EXCLUSIVE;

    /**
     * Evaluates the Strict 2PL lock compatibility matrix between this requested lock
     * and an already granted lock held by another transaction.
     */
    public boolean isCompatibleWith(LockType heldLockType) {
        if (this == SHARED && heldLockType == SHARED) {
            return true;
        }
        return false;
    }
}


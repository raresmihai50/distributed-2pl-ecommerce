package ro.distr2pl.app.concurrency;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a pending lock request in the wait queue of a resource.
 */
public class LockRequest {
    private final String transactionId;
    private final String resourceId;
    private final LockType requestedType;
    private final boolean upgradeRequest;
    private final LocalDateTime requestedAt;
    private volatile boolean granted;
    private volatile boolean abortedByDeadlock;
    private volatile List<String> deadlockCycle;

    public LockRequest(String transactionId, String resourceId, LockType requestedType, boolean upgradeRequest) {
        this.transactionId = transactionId;
        this.resourceId = resourceId;
        this.requestedType = requestedType;
        this.upgradeRequest = upgradeRequest;
        this.requestedAt = LocalDateTime.now();
        this.granted = false;
        this.abortedByDeadlock = false;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public LockType getRequestedType() {
        return requestedType;
    }

    public boolean isUpgradeRequest() {
        return upgradeRequest;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    public boolean isAbortedByDeadlock() {
        return abortedByDeadlock;
    }

    public void setAbortedByDeadlock(boolean abortedByDeadlock) {
        this.abortedByDeadlock = abortedByDeadlock;
    }

    public List<String> getDeadlockCycle() {
        return deadlockCycle;
    }

    public void setDeadlockCycle(List<String> deadlockCycle) {
        this.deadlockCycle = deadlockCycle;
    }
}


package ro.distr2pl.app.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.distr2pl.app.exception.DeadlockException;
import ro.distr2pl.app.exception.LockAcquisitionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized Strict Two-Phase Locking (Strict 2PL) Lock Manager.
 *
 * Manages Shared (S) and Exclusive (X) locks across all databases (PostgreSQL and MySQL)
 * using global resource identifiers such as:
 * - POSTGRESQL:products:15
 * - POSTGRESQL:inventory:4
 * - MYSQL:payments:20
 *
 * Strict 2PL Rules Enforced:
 * 1. Growing Phase: Transactions acquire S or X locks (or upgrade S -> X) before reading/writing.
 * 2. Compatibility Matrix: Multiple S locks are compatible; X lock is incompatible with any other S or X lock.
 * 3. Strictness: All locks acquired by a transaction are held until AFTER COMMIT or ROLLBACK completes.
 * 4. Deadlock Integration: Whenever a lock request cannot be granted immediately, Wait-For Graph edges
 *    are registered and the DeadlockDetector is invoked immediately and during wait cycles.
 */
@Component
public class LockManager {

    private static final Logger log = LoggerFactory.getLogger(LockManager.class);

    private final DeadlockDetector deadlockDetector;

    @Value("${app.transaction.lock-timeout-ms:8000}")
    private long lockTimeoutMs = 8000;

    // Monitor object for internal thread synchronization of the Lock Manager tables
    private final Object monitor = new Object();

    // resourceId -> currently active Lock
    private final Map<String, Lock> lockTable = new LinkedHashMap<>();

    // resourceId -> FIFO waiting queue of LockRequests
    private final Map<String, List<LockRequest>> waitQueues = new LinkedHashMap<>();

    // transactionId -> set of resourceIds currently locked by this transaction
    private final Map<String, Set<String>> transactionLocks = new HashMap<>();

    // transactionId -> nanoTime when transaction began (for deadlock victim selection)
    private final Map<String, Long> transactionStartTimes = new HashMap<>();

    public LockManager(DeadlockDetector deadlockDetector) {
        this.deadlockDetector = deadlockDetector;
    }

    /**
     * Registers a transaction start timestamp for deadlock victim selection.
     */
    public void registerTransaction(String transactionId) {
        synchronized (monitor) {
            transactionStartTimes.putIfAbsent(transactionId, System.nanoTime());
            transactionLocks.computeIfAbsent(transactionId, k -> new LinkedHashSet<>());
        }
    }

    /**
     * Acquires a Shared (S) or Exclusive (X) lock on the specified global resourceId
     * for the given transactionId, blocking if necessary and checking for deadlocks.
     */
    public void acquireLock(String transactionId, String resourceId, LockType requestedType) {
        synchronized (monitor) {
            registerTransaction(transactionId);

            // 1. Check if this transaction already holds a lock on resourceId
            Lock existingLock = lockTable.get(resourceId);
            if (existingLock != null && existingLock.isOwnedBy(transactionId)) {
                // Already holds EXCLUSIVE, or holds SHARED and only wants SHARED -> idempotent success
                if (existingLock.getLockType() == LockType.EXCLUSIVE || requestedType == LockType.SHARED) {
                    return;
                }
                // Holds SHARED and requests EXCLUSIVE -> Lock Upgrade (S -> X)
                if (existingLock.isSoleOwner(transactionId)) {
                    existingLock.setLockType(LockType.EXCLUSIVE);
                    log.debug("Tx [{}] upgraded lock S -> X immediately on {}", transactionId, resourceId);
                    return;
                }
            }

            // 2. Check if the lock can be granted immediately
            if (canGrantImmediately(transactionId, resourceId, requestedType)) {
                grantLockInternal(transactionId, resourceId, requestedType);
                log.debug("Tx [{}] acquired {} lock immediately on {}", transactionId, requestedType, resourceId);
                return;
            }

            // 3. Cannot grant immediately -> enqueue LockRequest and update Wait-For Graph
            boolean isUpgrade = existingLock != null && existingLock.isOwnedBy(transactionId)
                    && requestedType == LockType.EXCLUSIVE;
            LockRequest request = new LockRequest(transactionId, resourceId, requestedType, isUpgrade);
            List<LockRequest> queue = waitQueues.computeIfAbsent(resourceId, k -> new ArrayList<>());
            if (isUpgrade) {
                // Priority placement for lock upgrades
                queue.add(0, request);
            } else {
                queue.add(request);
            }

            updateWaitForGraphForResource(resourceId);

            // 4. Run immediate Deadlock Detection upon entering wait state
            checkAndResolveDeadlock(resourceId);

            // If the current transaction was selected as the deadlock victim right away:
            if (request.isAbortedByDeadlock()) {
                queue.remove(request);
                deadlockDetector.getWaitForGraph().removeOutgoingEdges(transactionId);
                throw new DeadlockException(
                        transactionId,
                        request.getDeadlockCycle(),
                        "Deadlock detected by Wait-For Graph on resource " + resourceId
                                + ". Transaction " + transactionId + " was selected as victim."
                );
            }

            // 5. Wait loop until granted, aborted as deadlock victim, or timed out
            long deadline = System.currentTimeMillis() + lockTimeoutMs;
            while (!request.isGranted() && !request.isAbortedByDeadlock()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    queue.remove(request);
                    deadlockDetector.getWaitForGraph().removeOutgoingEdges(transactionId);
                    updateWaitForGraphForResource(resourceId);
                    monitor.notifyAll();
                    throw new LockAcquisitionException(
                            transactionId,
                            resourceId,
                            "Lock acquisition timed out after " + lockTimeoutMs + "ms waiting for "
                                    + requestedType + " lock on " + resourceId
                    );
                }

                try {
                    monitor.wait(Math.min(remaining, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    queue.remove(request);
                    deadlockDetector.getWaitForGraph().removeOutgoingEdges(transactionId);
                    throw new LockAcquisitionException(transactionId, resourceId, "Thread interrupted while waiting for lock");
                }

                if (!request.isGranted() && !request.isAbortedByDeadlock()) {
                    updateWaitForGraphForResource(resourceId);
                    checkAndResolveDeadlock(resourceId);
                }
            }

            if (request.isAbortedByDeadlock()) {
                queue.remove(request);
                deadlockDetector.getWaitForGraph().removeOutgoingEdges(transactionId);
                throw new DeadlockException(
                        transactionId,
                        request.getDeadlockCycle(),
                        "Deadlock detected by Wait-For Graph on resource " + resourceId
                                + ". Transaction " + transactionId + " was selected as victim."
                );
            }

            log.debug("Tx [{}] acquired {} lock after waiting on {}", transactionId, requestedType, resourceId);
        }
    }

    /**
     * Releases ALL locks held by a transaction at the end of COMMIT or ROLLBACK (Strict 2PL).
     */
    public void releaseAllLocks(String transactionId) {
        synchronized (monitor) {
            deadlockDetector.getWaitForGraph().removeTransaction(transactionId);
            Set<String> heldResources = transactionLocks.remove(transactionId);
            transactionStartTimes.remove(transactionId);

            // Also remove any lingering wait requests by this transaction
            for (List<LockRequest> queue : waitQueues.values()) {
                queue.removeIf(req -> req.getTransactionId().equals(transactionId));
            }

            if (heldResources != null) {
                for (String resourceId : new ArrayList<>(heldResources)) {
                    Lock lock = lockTable.get(resourceId);
                    if (lock != null) {
                        lock.removeOwner(transactionId);
                        if (lock.getOwners().isEmpty()) {
                            lockTable.remove(resourceId);
                        }
                    }
                    processWaitQueue(resourceId);
                }
            }

            monitor.notifyAll();
        }
    }

    /**
     * Verifies whether a transaction currently holds the required lock (used in 2PC Prepare phase).
     */
    public boolean holdsLock(String transactionId, String resourceId, LockType minimumType) {
        synchronized (monitor) {
            Lock lock = lockTable.get(resourceId);
            if (lock == null || !lock.isOwnedBy(transactionId)) {
                return false;
            }
            if (minimumType == LockType.EXCLUSIVE) {
                return lock.getLockType() == LockType.EXCLUSIVE;
            }
            return true;
        }
    }

    /**
     * Returns all locks currently held by a specific transaction.
     */
    public List<String> getLocksForTransaction(String transactionId) {
        synchronized (monitor) {
            Set<String> resources = transactionLocks.getOrDefault(transactionId, Collections.emptySet());
            List<String> result = new ArrayList<>();
            for (String res : resources) {
                Lock lock = lockTable.get(res);
                if (lock != null) {
                    result.add(lock.getLockType().name() + "(" + res + ")");
                }
            }
            return result;
        }
    }

    /**
     * Returns a snapshot of all currently held locks for the Lock Manager Monitor UI.
     */
    public List<Lock> getActiveLocksSnapshot() {
        synchronized (monitor) {
            List<Lock> snapshot = new ArrayList<>();
            for (Lock lock : lockTable.values()) {
                snapshot.add(lock.copy());
            }
            return snapshot;
        }
    }

    /**
     * Returns a snapshot of all currently waiting lock requests for the Lock Manager Monitor UI.
     */
    public List<LockRequest> getWaitingRequestsSnapshot() {
        synchronized (monitor) {
            List<LockRequest> snapshot = new ArrayList<>();
            for (List<LockRequest> queue : waitQueues.values()) {
                snapshot.addAll(queue);
            }
            return snapshot;
        }
    }

    private boolean canGrantImmediately(String transactionId, String resourceId, LockType requestedType) {
        Lock currentLock = lockTable.get(resourceId);
        List<LockRequest> queue = waitQueues.getOrDefault(resourceId, Collections.emptyList());

        if (currentLock == null) {
            return queue.isEmpty();
        }

        if (requestedType == LockType.SHARED) {
            if (currentLock.getLockType() == LockType.SHARED) {
                // Compatible with current SHARED holders, provided no EXCLUSIVE request is waiting ahead
                boolean hasWaitingExclusive = queue.stream()
                        .anyMatch(req -> req.getRequestedType() == LockType.EXCLUSIVE
                                && !req.getTransactionId().equals(transactionId));
                return !hasWaitingExclusive;
            }
            return false;
        } else {
            // EXCLUSIVE requested: only possible if currentLock is solely owned by transactionId
            return currentLock.isSoleOwner(transactionId);
        }
    }

    private void grantLockInternal(String transactionId, String resourceId, LockType requestedType) {
        Lock currentLock = lockTable.get(resourceId);
        if (currentLock == null) {
            lockTable.put(resourceId, new Lock(resourceId, requestedType, transactionId));
        } else {
            currentLock.addOwner(transactionId);
            if (requestedType == LockType.EXCLUSIVE) {
                currentLock.setLockType(LockType.EXCLUSIVE);
            }
        }
        transactionLocks.computeIfAbsent(transactionId, k -> new LinkedHashSet<>()).add(resourceId);
    }

    private void processWaitQueue(String resourceId) {
        List<LockRequest> queue = waitQueues.get(resourceId);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        Iterator<LockRequest> it = queue.iterator();
        while (it.hasNext()) {
            LockRequest req = it.next();
            if (req.isAbortedByDeadlock()) {
                it.remove();
                continue;
            }

            Lock currentLock = lockTable.get(resourceId);
            String txId = req.getTransactionId();
            LockType wanted = req.getRequestedType();

            if (currentLock == null) {
                grantLockInternal(txId, resourceId, wanted);
                req.setGranted(true);
                it.remove();
                deadlockDetector.getWaitForGraph().removeOutgoingEdges(txId);
            } else if (wanted == LockType.SHARED && currentLock.getLockType() == LockType.SHARED) {
                grantLockInternal(txId, resourceId, LockType.SHARED);
                req.setGranted(true);
                it.remove();
                deadlockDetector.getWaitForGraph().removeOutgoingEdges(txId);
            } else if (wanted == LockType.EXCLUSIVE && currentLock.isSoleOwner(txId)) {
                // Upgrade S -> X now that all other S owners have released their locks
                currentLock.setLockType(LockType.EXCLUSIVE);
                req.setGranted(true);
                it.remove();
                deadlockDetector.getWaitForGraph().removeOutgoingEdges(txId);
                break; // Exclusive lock granted, no subsequent requests in queue can be granted now
            } else {
                // FIFO blocking: stop scanning if an EXCLUSIVE request cannot be satisfied yet
                break;
            }
        }

        if (queue.isEmpty()) {
            waitQueues.remove(resourceId);
        } else {
            updateWaitForGraphForResource(resourceId);
        }
    }

    private void updateWaitForGraphForResource(String resourceId) {
        Lock currentLock = lockTable.get(resourceId);
        List<LockRequest> queue = waitQueues.getOrDefault(resourceId, Collections.emptyList());

        for (int i = 0; i < queue.size(); i++) {
            LockRequest req = queue.get(i);
            String waiterTx = req.getTransactionId();

            // Waiter depends on all current incompatible lock owners
            if (currentLock != null) {
                for (String ownerTx : currentLock.getOwners()) {
                    if (!ownerTx.equals(waiterTx)) {
                        if (!req.getRequestedType().isCompatibleWith(currentLock.getLockType())) {
                            deadlockDetector.getWaitForGraph().addEdge(waiterTx, ownerTx);
                        }
                    }
                }
            }

            // Waiter also depends on preceding conflicting requests in the FIFO queue
            for (int j = 0; j < i; j++) {
                LockRequest earlier = queue.get(j);
                if (!earlier.getTransactionId().equals(waiterTx)) {
                    if (earlier.getRequestedType() == LockType.EXCLUSIVE
                            || req.getRequestedType() == LockType.EXCLUSIVE) {
                        deadlockDetector.getWaitForGraph().addEdge(waiterTx, earlier.getTransactionId());
                    }
                }
            }
        }
    }

    private void checkAndResolveDeadlock(String resourceId) {
        List<String> cycle = deadlockDetector.detectCycle();
        if (!cycle.isEmpty()) {
            String victimTxId = deadlockDetector.selectVictim(cycle, transactionStartTimes);
            deadlockDetector.recordDeadlock(cycle, victimTxId, resourceId);

            // Mark all waiting requests of victimTxId as aborted by deadlock
            for (List<LockRequest> q : waitQueues.values()) {
                for (LockRequest r : q) {
                    if (r.getTransactionId().equals(victimTxId)) {
                        r.setAbortedByDeadlock(true);
                        r.setDeadlockCycle(cycle);
                    }
                }
            }
            deadlockDetector.getWaitForGraph().removeTransaction(victimTxId);
            monitor.notifyAll();
        }
    }
}


package ro.distr2pl.app.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.distr2pl.app.monitoring.DeadlockEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deadlock Detector based on a Wait-For Graph (WFG).
 *
 * Responsibilities:
 * 1. Maintains the Wait-For Graph (T1 -> T2 when T1 waits for a lock held by T2).
 * 2. Detects directed cycles (T1 -> T2 -> ... -> T1) using DFS.
 * 3. Selects a victim transaction from the cycle using the "Youngest Transaction"
 *    strategy (most recent start timestamp, breaking ties by reverse ID).
 * 4. Records full deadlock diagnostics for the Deadlock Monitor UI.
 */
@Component
public class DeadlockDetector {

    private static final Logger log = LoggerFactory.getLogger(DeadlockDetector.class);

    private final WaitForGraph waitForGraph = new WaitForGraph();
    private final List<DeadlockEvent> deadlockHistory = new CopyOnWriteArrayList<>();

    public WaitForGraph getWaitForGraph() {
        return waitForGraph;
    }

    /**
     * Checks the Wait-For Graph for a cycle.
     *
     * @return List of transactions in the cycle (e.g. [T1, T2, T1]), or empty list if no cycle.
     */
    public synchronized List<String> detectCycle() {
        return waitForGraph.findCycle();
    }

    /**
     * Selects a victim transaction from the detected cycle using the
     * "Youngest Transaction" strategy (the transaction that started most recently).
     *
     * @param cycle                 List of transaction IDs forming the cycle (last element repeats the first)
     * @param transactionStartTimes Map of transactionId -> nanoTime when transaction started
     * @return The transactionId chosen as victim to be aborted and rolled back
     */
    public synchronized String selectVictim(List<String> cycle, Map<String, Long> transactionStartTimes) {
        if (cycle == null || cycle.isEmpty()) {
            return null;
        }
        // Exclude the duplicate closing node at the end of the cycle list
        int uniqueCount = cycle.size() > 1 && cycle.get(0).equals(cycle.get(cycle.size() - 1))
                ? cycle.size() - 1
                : cycle.size();

        String victim = cycle.get(0);
        long youngestStartTime = transactionStartTimes.getOrDefault(victim, 0L);

        for (int i = 1; i < uniqueCount; i++) {
            String candidate = cycle.get(i);
            long candidateStart = transactionStartTimes.getOrDefault(candidate, 0L);
            if (candidateStart > youngestStartTime ||
                    (candidateStart == youngestStartTime && candidate.compareTo(victim) > 0)) {
                youngestStartTime = candidateStart;
                victim = candidate;
            }
        }
        return victim;
    }

    /**
     * Records a deadlock detection and resolution event for monitoring.
     */
    public DeadlockEvent recordDeadlock(List<String> cycle, String victimTxId, String resourceId) {
        String id = "DL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        DeadlockEvent event = new DeadlockEvent(
                id,
                LocalDateTime.now(),
                new ArrayList<>(cycle),
                victimTxId,
                "YOUNGEST_TRANSACTION (Most Recent Timestamp)",
                resourceId,
                "ABORTED & ROLLED BACK victim " + victimTxId + ", released locks, unblocked waiting transactions"
        );
        deadlockHistory.add(0, event);
        log.warn("DEADLOCK DETECTED [{}]: Cycle={} | Resource={} | Victim Selected={}",
                id, event.getCycleFormatted(), resourceId, victimTxId);
        return event;
    }

    public List<DeadlockEvent> getDeadlockHistory() {
        return Collections.unmodifiableList(deadlockHistory);
    }

    public Map<String, Set<String>> getWaitForGraphSnapshot() {
        return waitForGraph.getSnapshot();
    }

    public void clearHistory() {
        deadlockHistory.clear();
        waitForGraph.clear();
    }
}


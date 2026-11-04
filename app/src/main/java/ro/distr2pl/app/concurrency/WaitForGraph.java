package ro.distr2pl.app.concurrency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directed graph (Wait-For Graph / WFG) for deadlock detection.
 *
 * - Nodes represent active transactions.
 * - A directed edge T1 -> T2 means transaction T1 is waiting for a lock currently held by T2.
 * - A directed cycle (e.g. T1 -> T2 -> T1) proves the existence of a deadlock.
 */
public class WaitForGraph {

    private final Map<String, Set<String>> adjacencyList = new LinkedHashMap<>();

    /**
     * Adds a wait-for dependency edge: waiterTxId -> holderTxId.
     */
    public synchronized void addEdge(String waiterTxId, String holderTxId) {
        if (waiterTxId == null || holderTxId == null || waiterTxId.equals(holderTxId)) {
            return;
        }
        adjacencyList.computeIfAbsent(waiterTxId, k -> new LinkedHashSet<>()).add(holderTxId);
        adjacencyList.computeIfAbsent(holderTxId, k -> new LinkedHashSet<>());
    }

    /**
     * Removes all outgoing wait edges from waiterTxId (called when waiterTxId acquires its lock).
     */
    public synchronized void removeOutgoingEdges(String waiterTxId) {
        Set<String> targets = adjacencyList.get(waiterTxId);
        if (targets != null) {
            targets.clear();
        }
    }

    /**
     * Removes a transaction completely from the Wait-For Graph (both outgoing and incoming edges).
     * Called when a transaction commits or aborts.
     */
    public synchronized void removeTransaction(String transactionId) {
        adjacencyList.remove(transactionId);
        for (Set<String> targets : adjacencyList.values()) {
            targets.remove(transactionId);
        }
    }

    /**
     * Detects if a cycle exists in the Wait-For Graph using Depth-First Search (DFS)
     * with three-color marking (unvisited, visiting/on-stack, visited).
     *
     * @return Ordered list of transaction IDs forming the cycle (e.g. [T1, T2, T1]),
     *         or empty list if the graph is acyclic.
     */
    public synchronized List<String> findCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        List<String> currentPath = new ArrayList<>();

        for (String node : adjacencyList.keySet()) {
            if (!visited.contains(node)) {
                List<String> cycle = dfs(node, visited, onStack, currentPath);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        return Collections.emptyList();
    }

    private List<String> dfs(String current, Set<String> visited, Set<String> onStack, List<String> currentPath) {
        visited.add(current);
        onStack.add(current);
        currentPath.add(current);

        Set<String> neighbors = adjacencyList.getOrDefault(current, Collections.emptySet());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                List<String> cycle = dfs(neighbor, visited, onStack, currentPath);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            } else if (onStack.contains(neighbor)) {
                // Cycle found! Extract the cycle slice from neighbor to current + neighbor
                int startIndex = currentPath.indexOf(neighbor);
                List<String> cycle = new ArrayList<>(currentPath.subList(startIndex, currentPath.size()));
                cycle.add(neighbor);
                return cycle;
            }
        }

        currentPath.remove(currentPath.size() - 1);
        onStack.remove(current);
        return Collections.emptyList();
    }

    /**
     * Returns a thread-safe snapshot of the active edges in the Wait-For Graph.
     */
    public synchronized Map<String, Set<String>> getSnapshot() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : adjacencyList.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
        return copy;
    }

    public synchronized void clear() {
        adjacencyList.clear();
    }
}


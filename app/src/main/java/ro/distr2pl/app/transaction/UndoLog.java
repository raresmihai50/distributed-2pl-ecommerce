package ro.distr2pl.app.transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maintains the ordered collection of UndoLogEntry items for a distributed transaction.
 * During rollback, entries are retrieved in strict reverse chronological order (LIFO).
 */
public class UndoLog {

    private final List<UndoLogEntry> entries = new ArrayList<>();

    public synchronized void addEntry(UndoLogEntry entry) {
        entries.add(entry);
    }

    public synchronized List<UndoLogEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Returns the undo log entries in reverse order (from the last executed operation down to the first),
     * ensuring proper LIFO restoration during transaction rollback.
     */
    public synchronized List<UndoLogEntry> getEntriesInReverseOrder() {
        List<UndoLogEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        return reversed;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }
}


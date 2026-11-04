package ro.distr2pl.app.monitoring;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a detected and resolved deadlock incident for the Deadlock Monitor UI and logs.
 */
public class DeadlockEvent {
    private String id;
    private LocalDateTime detectedAt;
    private List<String> cycleTransactions;
    private String cycleFormatted;
    private String victimTransactionId;
    private String selectionStrategy;
    private String resourceInvolved;
    private String resolution;

    public DeadlockEvent() {
    }

    public DeadlockEvent(String id, LocalDateTime detectedAt, List<String> cycleTransactions,
                         String victimTransactionId, String selectionStrategy,
                         String resourceInvolved, String resolution) {
        this.id = id;
        this.detectedAt = detectedAt;
        this.cycleTransactions = cycleTransactions;
        this.cycleFormatted = String.join(" -> ", cycleTransactions);
        this.victimTransactionId = victimTransactionId;
        this.selectionStrategy = selectionStrategy;
        this.resourceInvolved = resourceInvolved;
        this.resolution = resolution;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(LocalDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }

    public List<String> getCycleTransactions() {
        return cycleTransactions;
    }

    public void setCycleTransactions(List<String> cycleTransactions) {
        this.cycleTransactions = cycleTransactions;
    }

    public String getCycleFormatted() {
        return cycleFormatted;
    }

    public void setCycleFormatted(String cycleFormatted) {
        this.cycleFormatted = cycleFormatted;
    }

    public String getVictimTransactionId() {
        return victimTransactionId;
    }

    public void setVictimTransactionId(String victimTransactionId) {
        this.victimTransactionId = victimTransactionId;
    }

    public String getSelectionStrategy() {
        return selectionStrategy;
    }

    public void setSelectionStrategy(String selectionStrategy) {
        this.selectionStrategy = selectionStrategy;
    }

    public String getResourceInvolved() {
        return resourceInvolved;
    }

    public void setResourceInvolved(String resourceInvolved) {
        this.resourceInvolved = resourceInvolved;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }
}


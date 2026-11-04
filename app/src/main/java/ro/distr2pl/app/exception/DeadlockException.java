package ro.distr2pl.app.exception;

import java.util.List;

public class DeadlockException extends DistributedTransactionException {
    private final List<String> cycleTransactions;

    public DeadlockException(String victimTransactionId, List<String> cycleTransactions, String message) {
        super(victimTransactionId, message);
        this.cycleTransactions = cycleTransactions;
    }

    public List<String> getCycleTransactions() {
        return cycleTransactions;
    }
}


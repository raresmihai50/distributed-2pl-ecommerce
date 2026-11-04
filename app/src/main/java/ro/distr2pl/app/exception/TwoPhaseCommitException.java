package ro.distr2pl.app.exception;

public class TwoPhaseCommitException extends DistributedTransactionException {
    private final String phase;

    public TwoPhaseCommitException(String transactionId, String phase, String message) {
        super(transactionId, message);
        this.phase = phase;
    }

    public TwoPhaseCommitException(String transactionId, String phase, String message, Throwable cause) {
        super(transactionId, message, cause);
        this.phase = phase;
    }

    public String getPhase() {
        return phase;
    }
}


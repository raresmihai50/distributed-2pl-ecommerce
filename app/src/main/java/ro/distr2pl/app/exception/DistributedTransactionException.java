package ro.distr2pl.app.exception;

public class DistributedTransactionException extends RuntimeException {
    private final String transactionId;

    public DistributedTransactionException(String transactionId, String message) {
        super(message);
        this.transactionId = transactionId;
    }

    public DistributedTransactionException(String transactionId, String message, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }
}


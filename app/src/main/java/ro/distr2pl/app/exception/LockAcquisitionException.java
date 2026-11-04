package ro.distr2pl.app.exception;

public class LockAcquisitionException extends DistributedTransactionException {
    private final String resourceId;

    public LockAcquisitionException(String transactionId, String resourceId, String message) {
        super(transactionId, message);
        this.resourceId = resourceId;
    }

    public String getResourceId() {
        return resourceId;
    }
}


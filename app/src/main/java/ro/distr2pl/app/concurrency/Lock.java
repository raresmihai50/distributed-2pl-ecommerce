package ro.distr2pl.app.concurrency;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents an active lock on a globally identified resource
 * (e.g. POSTGRESQL:products:15 or MYSQL:payments:20).
 *
 * Multiple transactions can simultaneously be in the {@code owners} set when
 * {@code lockType == LockType.SHARED}. Only a single transaction can be in
 * {@code owners} when {@code lockType == LockType.EXCLUSIVE}.
 */
public class Lock {
    private final String resourceId;
    private LockType lockType;
    private final Set<String> owners = new LinkedHashSet<>();
    private LocalDateTime grantedAt;

    public Lock(String resourceId, LockType lockType, String initialOwner) {
        this.resourceId = resourceId;
        this.lockType = lockType;
        this.owners.add(initialOwner);
        this.grantedAt = LocalDateTime.now();
    }

    public String getResourceId() {
        return resourceId;
    }

    public LockType getLockType() {
        return lockType;
    }

    public void setLockType(LockType lockType) {
        this.lockType = lockType;
        this.grantedAt = LocalDateTime.now();
    }

    public Set<String> getOwners() {
        return owners;
    }

    public void addOwner(String transactionId) {
        this.owners.add(transactionId);
    }

    public void removeOwner(String transactionId) {
        this.owners.remove(transactionId);
    }

    public boolean isOwnedBy(String transactionId) {
        return this.owners.contains(transactionId);
    }

    public boolean isSoleOwner(String transactionId) {
        return this.owners.size() == 1 && this.owners.contains(transactionId);
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public Lock copy() {
        Lock copy = new Lock(this.resourceId, this.lockType, "");
        copy.owners.clear();
        copy.owners.addAll(this.owners);
        copy.grantedAt = this.grantedAt;
        return copy;
    }
}


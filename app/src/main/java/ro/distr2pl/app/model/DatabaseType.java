package ro.distr2pl.app.model;

/**
 * Identifies the target relational database participant in a distributed transaction.
 */
public enum DatabaseType {
    POSTGRESQL,
    MYSQL;

    /**
     * Builds a globally unique resource identifier for the Centralized Lock Manager.
     * Format: DATABASE:table:recordId (e.g. POSTGRESQL:products:15, MYSQL:payments:20)
     */
    public String resourceId(String table, Object recordId) {
        return this.name() + ":" + table + ":" + recordId;
    }
}


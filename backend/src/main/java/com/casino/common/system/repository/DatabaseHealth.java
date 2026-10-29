package com.casino.common.system.repository;

/**
 * Result of a database connectivity check.
 *
 * @param reachable     whether the database could be queried
 * @param schemaVersion version of the latest applied Flyway migration, or {@code null} if unknown
 */
public record DatabaseHealth(boolean reachable, String schemaVersion) {

    public static DatabaseHealth up(String schemaVersion) {
        return new DatabaseHealth(true, schemaVersion);
    }

    public static DatabaseHealth down() {
        return new DatabaseHealth(false, null);
    }
}

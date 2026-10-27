package com.casino.common.system.application;

/**
 * Result of a database connectivity check.
 *
 * @param status        whether the database could be queried
 * @param schemaVersion version of the latest applied Flyway migration, or {@code null} if unknown
 */
public record DatabaseHealth(ComponentStatus status, String schemaVersion) {

    public static DatabaseHealth up(String schemaVersion) {
        return new DatabaseHealth(ComponentStatus.UP, schemaVersion);
    }

    public static DatabaseHealth down() {
        return new DatabaseHealth(ComponentStatus.DOWN, null);
    }
}

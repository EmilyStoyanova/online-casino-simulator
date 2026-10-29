package com.casino.common.system.repository;

/**
 * Database access used by the platform status check. Keeps the service layer independent
 * of the concrete persistence technology.
 */
public interface DatabaseProbe {

    /** Checks the database; implementations must not throw and report failures as unreachable. */
    DatabaseHealth check();
}

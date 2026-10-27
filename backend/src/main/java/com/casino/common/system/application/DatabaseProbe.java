package com.casino.common.system.application;

/**
 * Port for checking database availability. Keeps the application layer independent of
 * the concrete persistence technology (implemented in the infrastructure layer).
 */
public interface DatabaseProbe {

    /** Checks the database; implementations must not throw and report failures as DOWN. */
    DatabaseHealth check();
}

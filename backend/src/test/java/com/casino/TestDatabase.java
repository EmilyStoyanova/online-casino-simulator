package com.casino;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Empties the tables that tests write, in the Testcontainers database of the current test context.
 * A bean of {@link TestcontainersConfiguration}, so it only exists where that container exists.
 *
 * <p>Uses {@code TRUNCATE}: the append-only ledger rejects {@code DELETE} (trigger), and that trigger
 * stays strict. {@code TRUNCATE} erases everything, so every call first passes
 * {@link TestDatabaseGuard} (G1–G5) on the very connection it would truncate. The seeded interest
 * catalogue and the Flyway history are kept.
 */
public final class TestDatabase {

    /** Every table a test can write, except the seeded catalogue {@code interests}. */
    static final String TRUNCATE = "TRUNCATE wallet_transactions, wallets, user_interests, spring_session_attributes, "
            + "spring_session, auth_throttle, users RESTART IDENTITY";

    private final DataSource dataSource;
    private final PostgreSQLContainer container;
    private final Environment environment;

    TestDatabase(DataSource dataSource, PostgreSQLContainer container, Environment environment) {
        this.dataSource = dataSource;
        this.container = container;
        this.environment = environment;
    }

    /** @throws IllegalStateException if a guard refuses; nothing has been changed then */
    public void reset() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            TestDatabaseGuard.check(facts(connection, statement));
            statement.execute(TRUNCATE);
        } catch (SQLException ex) {
            throw new IllegalStateException("Test database cleanup failed", ex);
        }
    }

    private TestDatabaseGuard.Facts facts(Connection connection, Statement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT current_database(), host(inet_server_addr())")) {
            row.next();
            return new TestDatabaseGuard.Facts(connection.getMetaData().getURL(), row.getString(1), row.getString(2),
                    Set.of(environment.getActiveProfiles()), container.isRunning() ? container.getJdbcUrl() : null);
        }
    }
}

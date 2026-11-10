package com.casino;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether {@link TestDatabase#reset()} may empty the tables of a database
 * ({@code docs/plans/week-03.md} §6.5). All checks must pass; any single one stops a reset of a shared
 * database:
 * <ul>
 *   <li>G1 — test sources only: this class and {@link TestDatabase} live in {@code src/test}, so they
 *       are never part of the application jar or image ({@code NoTruncateInMainSourcesTest} checks
 *       that production sources contain no {@code TRUNCATE});</li>
 *   <li>G2 — the database is {@code test}, the Testcontainers default (Docker Compose uses
 *       {@code casino}, Neon {@code neondb});</li>
 *   <li>G3 — the JDBC URL points to the local machine, and the server address is loopback or private
 *       (a container network);</li>
 *   <li>G4 — no Neon host and no {@code neon} or {@code render} profile;</li>
 *   <li>G5 — the data source is the Testcontainers container of this test context.</li>
 * </ul>
 * Pure: it only looks at the facts it is given, so {@code TestDatabaseGuardTest} checks it without a
 * database. Failure messages name the check, never the URL or credentials.
 */
final class TestDatabaseGuard {

    static final String TEST_DATABASE = "test";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> SHARED_PROFILES = Set.of("neon", "render");

    private TestDatabaseGuard() {
    }

    /**
     * What the guard checks.
     *
     * @param jdbcUrl          URL of the connection that would be emptied
     * @param databaseName     {@code current_database()} of that connection
     * @param serverAddress    {@code host(inet_server_addr())}; {@code null} for a Unix socket
     * @param activeProfiles   active Spring profiles of the test context
     * @param containerJdbcUrl JDBC URL of the context's Testcontainers container, or {@code null}
     */
    record Facts(String jdbcUrl, String databaseName, String serverAddress, Set<String> activeProfiles,
                 String containerJdbcUrl) {
    }

    /** @throws IllegalStateException naming the first failed check */
    static void check(Facts facts) {
        if (!TEST_DATABASE.equals(facts.databaseName())) {
            throw refused("G2", "the database is not the Testcontainers database '" + TEST_DATABASE + "'");
        }
        String host = host(facts.jdbcUrl());
        if (host == null || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw refused("G3", "the JDBC URL does not point to the local machine");
        }
        if (!isLocalServerAddress(facts.serverAddress())) {
            throw refused("G3", "the database server address is neither loopback nor private");
        }
        if (host.toLowerCase(Locale.ROOT).endsWith(".neon.tech")) {
            throw refused("G4", "the JDBC URL points to Neon");
        }
        if (facts.activeProfiles().stream().anyMatch(SHARED_PROFILES::contains)) {
            throw refused("G4", "a profile of a shared environment is active (" + SHARED_PROFILES + ")");
        }
        if (facts.containerJdbcUrl() == null || !withoutQuery(facts.containerJdbcUrl()).equals(withoutQuery(facts.jdbcUrl()))) {
            throw refused("G5", "the data source is not the Testcontainers container of this test context");
        }
    }

    private static IllegalStateException refused(String check, String reason) {
        return new IllegalStateException("Test database cleanup refused (" + check + "): " + reason);
    }

    /** Host of a {@code jdbc:postgresql://host:port/db} URL, or {@code null} if it has none. */
    static String host(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isLocalServerAddress(String address) {
        if (address == null) {
            return true;
        }
        // host(inet_server_addr()) is an IP literal; anything else is refused before getByName,
        // which then never performs a name lookup.
        if (!address.matches("[0-9A-Fa-f.:]+")) {
            return false;
        }
        try {
            InetAddress inet = InetAddress.getByName(address);
            byte[] bytes = inet.getAddress();
            boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
            return inet.isLoopbackAddress() || inet.isSiteLocalAddress() || uniqueLocalIpv6;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static String withoutQuery(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    }
}

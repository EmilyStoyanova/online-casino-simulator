package com.casino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The guards of {@link TestDatabase#reset()} (G1–G5, {@code docs/plans/week-03.md} §6.5): each one
 * alone refuses a database that is not this test's container. No database is contacted.
 */
class TestDatabaseGuardTest {

    private static final String CONTAINER_URL = "jdbc:postgresql://localhost:55432/test?loggerLevel=OFF";
    private static final TestDatabaseGuard.Facts TESTCONTAINERS =
            new TestDatabaseGuard.Facts(CONTAINER_URL, "test", "172.17.0.3", Set.of(), CONTAINER_URL);

    @Test
    void aTestcontainersDatabaseMayBeReset() {
        assertThatCode(() -> TestDatabaseGuard.check(TESTCONTAINERS)).doesNotThrowAnyException();
        // Unix socket (no server address), loopback and IPv6 unique-local addresses are local too.
        for (String address : new String[] {null, "127.0.0.1", "::1", "fd00::5", "10.1.2.3", "192.168.65.4"}) {
            assertThatCode(() -> TestDatabaseGuard.check(withServerAddress(address))).as(address).doesNotThrowAnyException();
        }
    }

    @Test
    void g2RefusesEveryOtherDatabaseName() {
        // Docker Compose (the developer's own data) and Neon.
        for (String name : List.of("casino", "neondb", "postgres", "TEST")) {
            assertRefused(new TestDatabaseGuard.Facts(CONTAINER_URL, name, "172.17.0.3", Set.of(), CONTAINER_URL), "G2");
        }
    }

    @Test
    void g3RefusesRemoteHostsAndPublicServerAddresses() {
        String remote = "jdbc:postgresql://db.example.org:5432/test";
        assertRefused(new TestDatabaseGuard.Facts(remote, "test", "172.17.0.3", Set.of(), remote), "G3");
        for (String address : List.of("203.0.113.10", "2001:db8::1", "db.example.org")) {
            assertRefused(withServerAddress(address), "G3");
        }
        assertRefused(new TestDatabaseGuard.Facts("not a url", "test", null, Set.of(), "not a url"), "G3");
    }

    @Test
    void g4RefusesNeonAndTheProfilesOfSharedEnvironments() {
        // A Neon URL is also remote (G3 fires first); the explicit Neon check stays as a second line.
        String neon = "jdbc:postgresql://ep-example-123456.eu-central-1.aws.neon.tech/test?sslmode=verify-full";
        assertRefused(new TestDatabaseGuard.Facts(neon, "test", null, Set.of(), neon), "G3");
        for (String profile : List.of("neon", "render")) {
            assertRefused(new TestDatabaseGuard.Facts(CONTAINER_URL, "test", "172.17.0.3", Set.of("test", profile),
                    CONTAINER_URL), "G4");
        }
    }

    @Test
    void g5RefusesADataSourceThatIsNotThisContextsContainer() {
        assertRefused(new TestDatabaseGuard.Facts(CONTAINER_URL, "test", "172.17.0.3", Set.of(), null), "G5");
        // Another local PostgreSQL that happens to have a database named "test".
        assertRefused(new TestDatabaseGuard.Facts("jdbc:postgresql://localhost:5432/test", "test", "127.0.0.1", Set.of(),
                CONTAINER_URL), "G5");
    }

    @Test
    void refusalsNameTheCheckButNeverTheUrl() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TestDatabaseGuard.check(new TestDatabaseGuard.Facts(
                        "jdbc:postgresql://localhost:5432/test?password=secret", "test", "127.0.0.1", Set.of(), CONTAINER_URL)))
                .withMessageStartingWith("Test database cleanup refused (G5)")
                .withMessageNotContaining("secret")
                .withMessageNotContaining("5432");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java", "src/main/resources"})
    void g1ProductionSourcesNeverTruncate(String root) throws IOException {
        Pattern truncate = Pattern.compile("\\bTRUNCATE\\b", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(Path.of(root))) {
            List<Path> offending = files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".sql"))
                    .filter(file -> truncate.matcher(withoutComments(read(file))).find())
                    .toList();
            assertThat(offending).as("TRUNCATE in production sources").isEmpty();
        }
        // The guarded helper itself is test code.
        assertThat(Path.of("src/test/java/com/casino/TestDatabase.java")).exists();
        assertThat(Path.of("src/main/java/com/casino/TestDatabase.java")).doesNotExist();
    }

    private static TestDatabaseGuard.Facts withServerAddress(String address) {
        return new TestDatabaseGuard.Facts(CONTAINER_URL, "test", address, Set.of(), CONTAINER_URL);
    }

    private static void assertRefused(TestDatabaseGuard.Facts facts, String check) {
        assertThatIllegalStateException().isThrownBy(() -> TestDatabaseGuard.check(facts))
                .withMessageStartingWith("Test database cleanup refused (" + check + ")");
    }

    /** Comments may mention truncation (V6 explains it); code and SQL statements may not use it. */
    private static String withoutComments(String content) {
        return content.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(--|//).*$", "");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

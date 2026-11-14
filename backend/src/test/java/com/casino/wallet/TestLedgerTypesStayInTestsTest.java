package com.casino.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The test-only ledger types {@code TEST_CREDIT}/{@code TEST_DEBIT} (P-W3-16) can never reach a
 * deployed database: the Flyway callback that allows them lives in the test resources only, and no
 * production resource (migration, configuration) names it or the types. Production Java code is
 * covered by ArchUnit R15. Checked on the sources, which is what Maven packages.
 */
class TestLedgerTypesStayInTestsTest {

    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

    @Test
    void theTestCallbackExistsOnlyInTheTestResources() {
        assertThat(Path.of("src", "test", "resources", "db", "test-callbacks", "afterMigrate.sql")).isRegularFile();
        assertThat(MAIN_RESOURCES.resolve("db").resolve("test-callbacks")).doesNotExist();
    }

    @Test
    void noProductionResourceUsesTheCallbackOrTheTestTypes() throws IOException {
        List<Path> offending;
        try (Stream<Path> files = Files.walk(MAIN_RESOURCES)) {
            offending = files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".sql") || file.toString().endsWith(".yml"))
                    .filter(file -> {
                        String content = read(file);
                        return content.contains("test-callbacks") || content.contains("TEST_CREDIT") || content.contains("TEST_DEBIT");
                    })
                    .toList();
        }

        assertThat(offending).isEmpty();
        assertThat(read(MAIN_RESOURCES.resolve("application.yml"))).contains("locations: classpath:db/migration\n");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

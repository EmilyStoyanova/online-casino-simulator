package com.casino;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The executable jar that Docker and Render run, as built by {@code package} before the integration
 * tests (W3-09): it contains the migrations V1–V8 and no test infrastructure. The retired test-only
 * ledger types ({@code TEST_CREDIT}, {@code TEST_DEBIT}, P-W3-16, removed in W4-03 by option D) appear in
 * no packaged class or resource, and neither does the former test callback location; test-scoped
 * libraries are absent.
 */
class ProductionArtifactIT {

    private static final String CLASSES = "BOOT-INF/classes/";
    /** Must never appear in any packaged class or resource of the application (W4-03, option D). */
    private static final List<String> RETIRED = List.of("TEST_CREDIT", "TEST_DEBIT", "test-callbacks");

    @Test
    void theExecutableJarContainsTheMigrationsAndNoTestInfrastructure() throws IOException {
        try (JarFile jar = new JarFile(executableJar().toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();

            // Exactly these migrations, each once. Any order: the jar keeps the build file system's directory
            // order (alphabetical on NTFS, hash order on Linux CI), and Flyway sorts by version anyway.
            assertThat(names).filteredOn(name -> name.startsWith(CLASSES + "db/migration/") && name.endsWith(".sql"))
                    .extracting(name -> name.substring((CLASSES + "db/migration/").length(), name.indexOf("__")))
                    .containsExactlyInAnyOrder("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8");
            assertThat(names).noneMatch(name -> name.contains("test-callbacks") || name.contains("archfixture")
                    || name.endsWith("IT.class") || name.endsWith("Test.class") || name.contains("TestDatabase")
                    || name.contains("TestAccounts") || name.contains("TestcontainersConfiguration"));
            assertThat(names).filteredOn(name -> name.startsWith("BOOT-INF/lib/"))
                    .noneMatch(name -> name.contains("testcontainers") || name.contains("junit") || name.contains("mockito")
                            || name.contains("archunit") || name.contains("assertj"));

            // Every application class and resource, byte for byte: class files keep string constants and
            // enum constant names as UTF-8 in their constant pool.
            List<String> scanned = new java.util.ArrayList<>();
            for (String name : names) {
                if (name.startsWith(CLASSES) && !name.endsWith("/")) {
                    assertThat(retiredNamesIn(bytes(jar, name))).as(name).isEmpty();
                    scanned.add(name);
                }
            }
            assertThat(scanned).contains(CLASSES + "com/casino/wallet/entity/TransactionType.class",
                    CLASSES + "com/casino/wallet/dto/WalletTransactionType.class", CLASSES + "application.yml");
            assertThat(read(jar, CLASSES + "application.yml").replace("\r\n", "\n")).contains("locations: classpath:db/migration\n");
        }
    }

    @Test
    void theScanFindsTheRetiredNamesInACompiledClass() throws IOException {
        // Proves that the scan above bites: this test class itself holds the names as string constants.
        try (InputStream compiled = ProductionArtifactIT.class.getResourceAsStream("ProductionArtifactIT.class")) {
            assertThat(retiredNamesIn(compiled.readAllBytes())).containsExactlyElementsOf(RETIRED);
        }
        assertThat(retiredNamesIn("STARTING_BALANCE ROULETTE_STAKE ROULETTE_PAYOUT".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    /** The retired names that occur in {@code content} (bytes read as Latin-1, so every byte maps to one char). */
    private static List<String> retiredNamesIn(byte[] content) {
        String text = new String(content, StandardCharsets.ISO_8859_1);
        return RETIRED.stream().filter(text::contains).toList();
    }

    private static byte[] bytes(JarFile jar, String name) throws IOException {
        try (InputStream content = jar.getInputStream(jar.getEntry(name))) {
            return content.readAllBytes();
        }
    }

    /** {@code target/<artifact>-<version>.jar}, the repackaged executable jar. */
    private static Path executableJar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            List<Path> jars = files.filter(file -> file.getFileName().toString().matches("casino-simulator-backend-.*[.]jar")).toList();
            assertThat(jars).as("executable jar in target/ (built by package)").hasSize(1);
            return jars.getFirst();
        }
    }

    private static String read(JarFile jar, String name) throws IOException {
        try (InputStream content = jar.getInputStream(jar.getEntry(name))) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

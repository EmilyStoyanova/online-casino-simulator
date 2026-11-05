package com.casino.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.casino.TestcontainersConfiguration;
import com.casino.user.dto.UpdateProfileRequest;
import com.casino.user.exception.ConcurrentModificationException;
import com.casino.user.exception.UnknownInterestException;
import com.casino.user.repository.UserAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UserProfileService} against PostgreSQL: what is written, atomicity and optimistic locking.
 *
 * <p>Overlapping updates are made deterministic: the test loads the account in an outer transaction,
 * lets another transaction commit a change, and only then runs the update inside the outer
 * transaction, so the update works with the version it loaded before the other change.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserProfileServiceIT {

    @Autowired
    private UserProfileService service;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID id;

    @BeforeEach
    void createAccount() {
        id = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, adult_confirmed_at)
                VALUES ('alice@example.com', '{bcrypt}$2a$12$hash', 'Alice', now()) RETURNING id""", UUID.class);
    }

    @AfterEach
    void deleteRows() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void savesTheDisplayNameAndTheSelectionAndIncrementsTheVersionOnce() {
        service.updateProfile(id, new UpdateProfileRequest("Alice B.", List.of("STATISTICS", "PROBABILITY")));

        Map<String, Object> row = account();
        assertThat(row).containsEntry("display_name", "Alice B.").containsEntry("version", 1L);
        assertThat(selection()).containsExactly("PROBABILITY", "STATISTICS");

        // Only the interests: still a new version, so overlapping interest updates are detected too.
        service.updateProfile(id, new UpdateProfileRequest(null, List.of("STATISTICS")));
        assertThat(account()).containsEntry("display_name", "Alice B.").containsEntry("version", 2L);
        assertThat(selection()).containsExactly("STATISTICS");
    }

    @Test
    void keptInterestsKeepTheirRow() {
        service.updateProfile(id, new UpdateProfileRequest(null, List.of("PROBABILITY", "MATHEMATICS")));
        Timestamp selectedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM user_interests WHERE user_id = ? AND interest_code = 'PROBABILITY'", Timestamp.class, id);

        service.updateProfile(id, new UpdateProfileRequest(null, List.of("STATISTICS", "PROBABILITY")));

        assertThat(selection()).containsExactly("PROBABILITY", "STATISTICS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM user_interests WHERE user_id = ? AND interest_code = 'PROBABILITY'", Timestamp.class, id))
                .isEqualTo(selectedAt);
    }

    @Test
    void anUnchangedProfileIsNotWritten() {
        service.updateProfile(id, new UpdateProfileRequest("Alice", List.of("PROBABILITY")));
        Map<String, Object> before = account();

        service.updateProfile(id, new UpdateProfileRequest(null, null));
        service.updateProfile(id, new UpdateProfileRequest("Alice", List.of("PROBABILITY")));

        assertThat(account()).isEqualTo(before);
    }

    @Test
    void anInvalidSelectionSavesNothing() {
        assertThatThrownBy(() -> service.updateProfile(id, new UpdateProfileRequest("Mallory", List.of("PROBABILITY", "ASTROLOGY"))))
                .isInstanceOf(UnknownInterestException.class);

        assertThat(account()).containsEntry("display_name", "Alice").containsEntry("version", 0L);
        assertThat(selection()).isEmpty();
    }

    @Test
    void anOverlappingChangeIsRejectedAndNothingIsSaved() {
        assertThatThrownBy(() -> afterAnotherChange(
                () -> jdbcTemplate.update("UPDATE users SET display_name = 'Other', version = version + 1 WHERE id = ?", id),
                () -> service.updateProfile(id, new UpdateProfileRequest("Mine", List.of("PROBABILITY")))))
                .isInstanceOf(ConcurrentModificationException.class);

        // The other change stays; nothing of the rejected update was saved.
        assertThat(account()).containsEntry("display_name", "Other").containsEntry("version", 1L);
        assertThat(selection()).isEmpty();
    }

    @Test
    void anOverlappingChangeOfTheInterestsOnlyIsRejectedToo() {
        assertThatThrownBy(() -> afterAnotherChange(
                () -> service.updateProfile(id, new UpdateProfileRequest(null, List.of("MATHEMATICS"))),
                () -> service.updateProfile(id, new UpdateProfileRequest(null, List.of("PROBABILITY")))))
                .isInstanceOf(ConcurrentModificationException.class);

        assertThat(selection()).containsExactly("MATHEMATICS");
    }

    @Test
    void aLoginDuringAProfileUpdateKeepsItsLastLoginTime() {
        // A login sets last_login_at without a new version (concurrent logins must not conflict). The
        // profile update loaded the account before; it must not write the old (empty) value back.
        afterAnotherChange(() -> userAccountService.recordLogin(id),
                () -> service.updateProfile(id, new UpdateProfileRequest("Alice B.", null)));

        Map<String, Object> row = account();
        assertThat(row).containsEntry("display_name", "Alice B.").containsEntry("version", 1L);
        assertThat(row.get("last_login_at")).isNotNull();
    }

    /**
     * Loads the account in a transaction, commits {@code otherChange} in a separate transaction, then
     * runs {@code update} in the first transaction, where the account is still the one loaded before.
     */
    private void afterAnotherChange(Runnable otherChange, Runnable update) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(accounts.findById(id)).isPresent();
            separate.executeWithoutResult(inner -> otherChange.run());
            update.run();
        });
    }

    private Map<String, Object> account() {
        return jdbcTemplate.queryForMap("""
                SELECT email, password_hash, display_name, role, status, adult_confirmed_at, last_login_at, updated_at, version
                FROM users WHERE id = ?""", id);
    }

    private List<String> selection() {
        return jdbcTemplate.queryForList(
                "SELECT interest_code FROM user_interests WHERE user_id = ? ORDER BY interest_code", String.class, id);
    }
}

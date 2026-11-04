package com.casino.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.casino.common.exception.FieldViolation;
import com.casino.common.exception.ProblemCode;
import com.casino.user.dto.UpdateProfileRequest;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.entity.Interest;
import com.casino.user.entity.UserAccount;
import com.casino.user.exception.AccountNotFoundException;
import com.casino.user.exception.ConcurrentModificationException;
import com.casino.user.exception.UnknownInterestException;
import com.casino.user.repository.InterestRepository;
import com.casino.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfileServiceTest {

    private static final UUID ID = UUID.fromString("0b8f0c2e-5f3a-4c55-9d8e-2f6c1a7e9b10");
    private static final Instant CREATED = Instant.parse("2026-11-02T09:15:00Z");
    private static final String HASH = "{bcrypt}$2a$12$abcdefghijklmnopqrstuuJ0b0t3Zl0Q1zU0z9eXv7m8e3a1b2c3d4";

    /** A small catalogue; HISTORY is no longer offered. */
    private static final Map<String, Interest> CATALOGUE = Map.of(
            "PROBABILITY", interest("PROBABILITY", 10, true),
            "MATHEMATICS", interest("MATHEMATICS", 20, true),
            "STATISTICS", interest("STATISTICS", 30, true),
            "HISTORY", interest("HISTORY", 70, false));

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final InterestRepository interests = mock(InterestRepository.class);
    private final UserProfileService service = new UserProfileService(accounts, interests);

    private UserAccount account;

    @BeforeEach
    void setUp() {
        account = new UserAccount("alice@example.com", HASH, "Alice", CREATED);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "createdAt", CREATED);
        when(accounts.findById(ID)).thenReturn(Optional.of(account));
        when(interests.findAllById(any())).thenAnswer(invocation -> {
            List<Interest> found = new ArrayList<>();
            for (String code : invocation.<Iterable<String>>getArgument(0)) {
                if (CATALOGUE.containsKey(code)) {
                    found.add(CATALOGUE.get(code));
                }
            }
            return found;
        });
    }

    @Test
    void returnsTheProfileOfTheUser() {
        account.replaceInterests(List.of(CATALOGUE.get("STATISTICS"), CATALOGUE.get("PROBABILITY")));

        UserProfileResponse profile = service.getProfile(ID);

        // Interests in catalogue order, never the hash.
        assertThat(profile).isEqualTo(new UserProfileResponse(ID, "alice@example.com", "Alice", UserRole.PLAYER,
                List.of("PROBABILITY", "STATISTICS"), CREATED, CREATED));
    }

    @Test
    void listsOnlyActiveInterests() {
        // HISTORY was selected while it was still offered.
        account.replaceInterests(List.of(CATALOGUE.get("HISTORY"), CATALOGUE.get("MATHEMATICS")));

        assertThat(service.getProfile(ID).interests()).containsExactly("MATHEMATICS");
    }

    @Test
    void anAccountThatNoLongerExistsIsUnauthenticated() {
        UUID unknown = UUID.randomUUID();
        when(accounts.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(unknown)).isInstanceOf(AccountNotFoundException.class)
                .extracting("code").isEqualTo(ProblemCode.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.updateProfile(unknown, new UpdateProfileRequest("Bob", null)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void updatesOnlyTheGivenProperties() {
        account.replaceInterests(List.of(CATALOGUE.get("PROBABILITY")));

        UserProfileResponse renamed = service.updateProfile(ID, new UpdateProfileRequest("Alice B.", null));

        assertThat(renamed.displayName()).isEqualTo("Alice B.");
        assertThat(renamed.interests()).containsExactly("PROBABILITY");
        verify(interests, never()).findAllById(any());

        UserProfileResponse reselected = service.updateProfile(ID, new UpdateProfileRequest(null, List.of("STATISTICS", "MATHEMATICS")));

        assertThat(reselected.displayName()).isEqualTo("Alice B.");
        assertThat(reselected.interests()).containsExactly("MATHEMATICS", "STATISTICS");
        // Protected properties are untouched.
        assertThat(account.getEmail()).isEqualTo("alice@example.com");
        assertThat(account.getPasswordHash()).isEqualTo(HASH);
        assertThat(account.getAdultConfirmedAt()).isEqualTo(CREATED);
        verify(accounts, times(2)).flush();
    }

    @Test
    void anEmptySelectionRemovesAllInterests() {
        account.replaceInterests(List.of(CATALOGUE.get("PROBABILITY")));

        assertThat(service.updateProfile(ID, new UpdateProfileRequest(null, List.of())).interests()).isEmpty();
        assertThat(account.getInterests()).isEmpty();
    }

    @Test
    void anEmptyRequestChangesNothing() {
        account.replaceInterests(List.of(CATALOGUE.get("PROBABILITY")));

        UserProfileResponse profile = service.updateProfile(ID, new UpdateProfileRequest(null, null));

        assertThat(profile).isEqualTo(service.getProfile(ID));
        assertThat(profile.displayName()).isEqualTo("Alice");
        assertThat(profile.interests()).containsExactly("PROBABILITY");
    }

    @Test
    void rejectsUnknownAndInactiveInterestsByPositionWithoutChangingAnything() {
        account.replaceInterests(List.of(CATALOGUE.get("PROBABILITY")));

        assertThatThrownBy(() -> service.updateProfile(ID,
                new UpdateProfileRequest("Mallory", List.of("MATHEMATICS", "UNKNOWN", "STATISTICS", "HISTORY"))))
                .isInstanceOfSatisfying(UnknownInterestException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                    // The codes themselves are not echoed.
                    assertThat(ex.violations()).containsExactly(
                            new FieldViolation("interests[1]", "must be the code of an active interest"),
                            new FieldViolation("interests[3]", "must be the code of an active interest"));
                });
        // The transaction is rolled back; the entity in memory was not changed either.
        assertThat(account.getDisplayName()).isEqualTo("Alice");
        assertThat(account.getInterests()).extracting(Interest::getCode).containsExactly("PROBABILITY");
        verify(accounts, never()).flush();
    }

    @Test
    void anOverlappingChangeIsAConcurrentModification() {
        doThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, ID)).when(accounts).flush();

        assertThatThrownBy(() -> service.updateProfile(ID, new UpdateProfileRequest("Alice B.", null)))
                .isInstanceOfSatisfying(ConcurrentModificationException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ProblemCode.CONCURRENT_MODIFICATION);
                    assertThat(ex.getMessage()).isEqualTo("The profile was changed by another request. Reload it and try again.");
                });
    }

    private static Interest interest(String code, int sortOrder, boolean active) {
        Interest interest = BeanUtils.instantiateClass(Interest.class);
        ReflectionTestUtils.setField(interest, "code", code);
        ReflectionTestUtils.setField(interest, "name", code);
        ReflectionTestUtils.setField(interest, "description", code);
        ReflectionTestUtils.setField(interest, "sortOrder", (short) sortOrder);
        ReflectionTestUtils.setField(interest, "active", active);
        return interest;
    }
}

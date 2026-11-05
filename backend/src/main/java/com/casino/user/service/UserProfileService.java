package com.casino.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.casino.user.dto.UpdateProfileRequest;
import com.casino.user.dto.UserProfileResponse;
import com.casino.user.entity.Interest;
import com.casino.user.entity.UserAccount;
import com.casino.user.exception.AccountNotFoundException;
import com.casino.user.exception.ConcurrentModificationException;
import com.casino.user.exception.UnknownInterestException;
import com.casino.user.repository.InterestRepository;
import com.casino.user.repository.UserAccountRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's own profile ({@code GET/PATCH /api/v1/users/me}).
 *
 * <p>The user id always comes from the session (the caller passes the principal's id), never from
 * the request (AC-9.3). Only the display name and the interests can change (AC-7.3); the request
 * type has no other properties.
 */
@Service
public class UserProfileService {

    private final UserAccountRepository accounts;
    private final InterestRepository interests;

    public UserProfileService(UserAccountRepository accounts, InterestRepository interests) {
        this.accounts = accounts;
        this.interests = interests;
    }

    /**
     * @param userId the session's user id
     * @throws AccountNotFoundException if the account no longer exists
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        return UserProfiles.of(load(userId));
    }

    /**
     * Applies a partial update in one transaction: either every change is saved or none. Omitted
     * properties stay unchanged; the interests replace the whole selection. A request that changes
     * nothing writes nothing.
     *
     * @param userId  the session's user id
     * @param request already validated by Bean Validation (format, at most 8, no duplicates)
     * @throws UnknownInterestException        if an interest code is unknown or inactive
     * @throws ConcurrentModificationException if another change of the profile overlapped (AC-7.4)
     * @throws AccountNotFoundException        if the account no longer exists
     */
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        UserAccount account = load(userId);
        if (request.interests() != null) {
            account.replaceInterests(activeInterests(request.interests()));
        }
        if (request.displayName() != null) {
            account.changeDisplayName(request.displayName());
        }
        try {
            // Flushed here, so a version conflict is reported by this method and not at commit.
            accounts.flush();
        } catch (OptimisticLockingFailureException ex) {
            throw new ConcurrentModificationException();
        }
        return UserProfiles.of(account);
    }

    private UserAccount load(UUID userId) {
        return accounts.findById(userId).orElseThrow(AccountNotFoundException::new);
    }

    /** The interests with these codes, in request order; reports every unknown or inactive code by position. */
    private List<Interest> activeInterests(List<String> codes) {
        Map<String, Interest> found = interests.findAllById(codes).stream()
                .collect(Collectors.toMap(Interest::getCode, Function.identity()));
        List<Integer> invalid = new ArrayList<>();
        List<Interest> selection = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            Interest interest = found.get(codes.get(i));
            if (interest == null || !interest.isActive()) {
                invalid.add(i);
            } else {
                selection.add(interest);
            }
        }
        if (!invalid.isEmpty()) {
            throw new UnknownInterestException(invalid);
        }
        return selection;
    }
}

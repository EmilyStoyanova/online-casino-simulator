package com.casino.user.service;

import java.util.Comparator;

import com.casino.user.dto.UserProfileResponse;
import com.casino.user.dto.UserRole;
import com.casino.user.entity.Interest;
import com.casino.user.entity.UserAccount;

/** Maps accounts to the API profile; shared by registration, login and the profile operations. */
final class UserProfiles {

    /** The order of {@code GET /api/v1/interests}. */
    private static final Comparator<Interest> CATALOGUE_ORDER =
            Comparator.comparing(Interest::getSortOrder).thenComparing(Interest::getCode);

    private UserProfiles() {
    }

    /**
     * The profile of {@code account}. Must run inside the transaction that loaded the account, which
     * loads the interests. Only active interests are listed, in catalogue order, so a client can
     * send the listed selection back unchanged.
     */
    static UserProfileResponse of(UserAccount account) {
        return new UserProfileResponse(account.getId(), account.getEmail(), account.getDisplayName(),
                role(account),
                account.getInterests().stream().filter(Interest::isActive).sorted(CATALOGUE_ORDER).map(Interest::getCode).toList(),
                account.getAdultConfirmedAt(), account.getCreatedAt());
    }

    static UserRole role(UserAccount account) {
        return UserRole.valueOf(account.getRole().name());
    }
}

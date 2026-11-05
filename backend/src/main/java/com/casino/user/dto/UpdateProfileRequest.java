package com.casino.user.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * Body of {@code PATCH /api/v1/users/me}: a partial update. A {@code null} component means that the
 * property stays unchanged: it was omitted or sent as JSON {@code null} (a record cannot tell the two
 * apart). {@code null} never clears a property; {@code "interests": []} removes all interests.
 * Properties not listed here (e.g. {@code email} or {@code role}) are ignored.
 *
 * <p>Contract: {@code UpdateProfileRequest} schema in {@code openapi.yaml}. The display name is
 * trimmed here, so validation checks the value that is stored, with the rules of registration.
 * Whether the interest codes exist and are active is checked by {@code UserProfileService}.
 *
 * @param displayName new public name, or {@code null} to keep it
 * @param interests   the complete new selection, or {@code null} to keep it
 */
@Schema(name = "UpdateProfileRequest")
public record UpdateProfileRequest(
        @Size(min = 3, max = 30, message = "must be 3 to 30 characters long")
        @Pattern(regexp = "\\P{Cc}*", message = "must not contain control characters")
        String displayName,

        @Size(max = 8, message = "must contain at most 8 interests")
        @UniqueElements(message = "must not contain the same interest twice")
        List<@NotNull(message = "must not be null")
            @Size(max = 40, message = "must be at most 40 characters long")
            @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "must be an interest code") String> interests) {

    public UpdateProfileRequest {
        displayName = displayName == null ? null : displayName.strip();
        interests = interests == null ? null : Collections.unmodifiableList(new ArrayList<>(interests));
    }
}

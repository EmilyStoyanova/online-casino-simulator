package com.casino.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Role of a user as shown by the API; other modules use it instead of the entity's {@code Role}. */
@Schema(name = "UserRole", enumAsRef = true)
public enum UserRole {
    PLAYER,
    ADMIN
}

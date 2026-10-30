package archfixture.user.dto;

import archfixture.user.entity.UserRole;

/** Enums from an entity package are allowed in DTOs (R7). */
public record UserDto(String name, UserRole role) {
}

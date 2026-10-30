package archfixture.user.dto;

import archfixture.user.entity.UserEntity;

/** Violates R7. */
public record EntityWrappingDto(UserEntity entity) {
}

/** Mirrors the `UserProfile`, `UserRole` and `UpdateProfileRequest` schemas of `openapi.yaml`. */
export type UserRole = 'PLAYER' | 'ADMIN'

export interface UserProfile {
  id: string
  email: string
  displayName: string
  role: UserRole
  /** Codes of the selected active interests, in catalogue order (`GET /api/v1/interests`). */
  interests: string[]
  adultConfirmedAt: string
  createdAt: string
}

/**
 * Partial update (`PATCH /api/v1/users/me`): omitted properties stay unchanged. `interests` replaces
 * the whole selection (0–8 distinct codes; `[]` removes all). Email, role and the 18+ confirmation
 * cannot be changed.
 */
export interface UpdateProfileRequest {
  displayName?: string
  interests?: string[]
}

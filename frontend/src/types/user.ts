/** Mirrors the `UserProfile` and `UserRole` schemas of `openapi.yaml`. */
export type UserRole = 'PLAYER' | 'ADMIN'

export interface UserProfile {
  id: string
  email: string
  displayName: string
  role: UserRole
  /** Codes of the selected interests (`GET /api/v1/interests`); empty until profiles can be edited. */
  interests: string[]
  adultConfirmedAt: string
  createdAt: string
}

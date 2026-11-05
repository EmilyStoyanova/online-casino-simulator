/** Mirrors the `RegisterRequest` and `LoginRequest` schemas of `openapi.yaml`. */
export interface RegisterRequest {
  email: string
  password: string
  displayName: string
  adultConfirmed: boolean
}

export interface LoginRequest {
  email: string
  password: string
}

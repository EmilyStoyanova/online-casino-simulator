import type { UpdateProfileRequest, UserProfile } from '../types/user'
import { apiGet, apiPatch, type RequestOptions } from './apiClient'

const CURRENT_USER_PATH = '/api/v1/users/me'

/**
 * The profile of the signed-in user (`Users` tag of `openapi.yaml`). The server identifies the user
 * by the `HttpOnly` session cookie; `401` means that there is no valid session.
 */
export function getCurrentUser(options: RequestOptions = {}): Promise<UserProfile> {
  return apiGet<UserProfile>(CURRENT_USER_PATH, options)
}

/**
 * Changes the display name and/or the interests of the signed-in user and returns the saved profile.
 * Needs the CSRF token like every unsafe request. `409 CONCURRENT_MODIFICATION` when another change of
 * the same profile overlapped.
 */
export function updateCurrentUser(request: UpdateProfileRequest, options: RequestOptions = {}): Promise<UserProfile> {
  return apiPatch<UserProfile>(CURRENT_USER_PATH, request, options)
}

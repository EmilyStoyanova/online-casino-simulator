import type { UserProfile } from '../types/user'
import { apiGet, type RequestOptions } from './apiClient'

const CURRENT_USER_PATH = '/api/v1/users/me'

/**
 * The profile of the signed-in user (`Users` tag of `openapi.yaml`). The server identifies the user
 * by the `HttpOnly` session cookie; `401` means that there is no valid session.
 */
export function getCurrentUser(options: RequestOptions = {}): Promise<UserProfile> {
  return apiGet<UserProfile>(CURRENT_USER_PATH, options)
}

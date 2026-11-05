import type { Interest } from '../types/interest'
import { apiGet, type RequestOptions } from './apiClient'

const INTERESTS_PATH = '/api/v1/interests'

/** The active interests in display order (`Interests` tag of `openapi.yaml`); public. */
export function listInterests(options: RequestOptions = {}): Promise<Interest[]> {
  return apiGet<Interest[]>(INTERESTS_PATH, options)
}

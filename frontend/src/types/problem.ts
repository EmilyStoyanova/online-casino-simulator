/** Mirrors the `ProblemDetail` and `FieldError` schemas of `openapi.yaml` (RFC 9457). */
export type ProblemCode =
  | 'VALIDATION_FAILED'
  | 'MALFORMED_REQUEST'
  | 'AUTHENTICATION_REQUIRED'
  | 'SESSION_EXPIRED'
  | 'INVALID_CREDENTIALS'
  | 'ACCESS_DENIED'
  | 'CSRF_TOKEN_INVALID'
  | 'REGISTRATION_DISABLED'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'NOT_ACCEPTABLE'
  | 'CONFLICT'
  | 'EMAIL_ALREADY_REGISTERED'
  | 'CONCURRENT_MODIFICATION'
  | 'INSUFFICIENT_FUNDS'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'BALANCE_LIMIT_EXCEEDED'
  | 'CONTENT_TOO_LARGE'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'TOO_MANY_ATTEMPTS'
  | 'INTERNAL_ERROR'
  | 'SERVICE_UNAVAILABLE'
  | 'CLIENT_ERROR'
  | 'SERVER_ERROR'

export interface FieldError {
  field: string
  message: string
}

export interface ProblemDetail {
  type?: string
  title: string
  status: number
  detail?: string
  instance?: string
  /** Clients decide on this value, never on `title` or `detail`. Unknown future codes are possible. */
  code: ProblemCode | (string & {})
  /** Only for `VALIDATION_FAILED`. */
  errors?: FieldError[]
}

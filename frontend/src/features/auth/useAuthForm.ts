import { useCallback, useEffect, useRef, useState } from 'react'
import { describeAuthFailure, type FormFailure } from './authErrors'
import { hasErrors, type FieldErrors } from './validation'

/**
 * Submission state shared by the login and registration forms: field errors (client or server), the
 * alert message, the pending request, focus on the first invalid field, and the lock after
 * `429 TOO_MANY_ATTEMPTS` until `Retry-After` has passed.
 */
export function useAuthForm(action: 'login' | 'register') {
  const formRef = useRef<HTMLFormElement>(null)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [failure, setFailure] = useState<FormFailure | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [lockedUntil, setLockedUntil] = useState<number | null>(null)
  const [focusRequest, setFocusRequest] = useState(0)

  useEffect(() => {
    if (focusRequest > 0) {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    }
  }, [focusRequest])

  useEffect(() => {
    if (lockedUntil === null) return
    const timer = setTimeout(() => setLockedUntil(null), Math.max(0, lockedUntil - Date.now()))
    return () => clearTimeout(timer)
  }, [lockedUntil])

  /** Shows `clientErrors` if there are any; otherwise runs `send` and shows its failure, if any. */
  const submit = useCallback(
    async (clientErrors: FieldErrors, send: () => Promise<void>) => {
      if (hasErrors(clientErrors)) {
        setFieldErrors(clientErrors)
        setFailure(null)
        setFocusRequest((request) => request + 1)
        return
      }
      setFieldErrors({})
      setFailure(null)
      setSubmitting(true)
      try {
        await send()
      } catch (error) {
        const described = describeAuthFailure(error, action)
        setFieldErrors(described.fieldErrors)
        setFailure(described)
        if (described.retryAfterSeconds !== undefined && described.retryAfterSeconds > 0) {
          setLockedUntil(Date.now() + described.retryAfterSeconds * 1000)
        }
        if (hasErrors(described.fieldErrors)) {
          setFocusRequest((request) => request + 1)
        }
        setSubmitting(false)
      }
    },
    [action],
  )

  /** Removes the error of a field once the user edits it. */
  const clearFieldError = useCallback((field: string) => {
    setFieldErrors((errors) => (errors[field] === undefined ? errors : { ...errors, [field]: undefined }))
  }, [])

  return {
    formRef,
    fieldErrors,
    message: failure?.message ?? null,
    failureCode: failure?.code,
    submitting,
    locked: lockedUntil !== null,
    submit,
    clearFieldError,
  }
}

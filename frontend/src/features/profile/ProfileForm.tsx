import { useEffect, useRef, useState, type FormEvent } from 'react'
import { FormAlert } from '../../components/form/FormAlert'
import { TextField } from '../../components/form/TextField'
import { primaryButton, secondaryButton } from '../../components/buttonStyles'
import { msg, type Message } from '../../i18n/translate'
import { useI18n } from '../../i18n/useI18n'
import type { UserProfile } from '../../types/user'
import { useAuth } from '../auth/useAuth'
import { hasErrors, validateDisplayName, type FieldErrors } from '../auth/validation'
import { InterestPicker } from './InterestPicker'
import { describeSaveFailure, MAX_INTERESTS, profileChanges, sameSelection } from './profileChanges'
import type { InterestCatalog } from './useInterests'

type Notice =
  | { kind: 'saved' }
  | { kind: 'conflict' }
  | { kind: 'reloaded' }
  | { kind: 'reload-failed' }
  | { kind: 'error'; message: Message; retry: boolean }

/**
 * Edits the display name and the interests (`PATCH /api/v1/users/me`); email, role and the 18+
 * confirmation are not editable. `user` is the saved profile: the form starts from it, sends only
 * what differs and takes the server's answer as the new saved state (also for the header).
 *
 * After a failure the edits stay in the form. A `409 CONCURRENT_MODIFICATION` is never retried
 * automatically: the user can load the latest saved version first. Loading it updates the saved
 * profile shown on the page and every field the user did not edit; the edited ones stay as typed.
 */
export function ProfileForm({
  user,
  catalog,
  onRetryCatalog,
}: {
  user: UserProfile
  catalog: InterestCatalog
  onRetryCatalog: () => void
}) {
  const { updateProfile, reloadProfile } = useAuth()
  const { t } = useI18n()
  const formRef = useRef<HTMLFormElement>(null)
  // Set synchronously, so a second click before the next render cannot send a second request.
  const savingRef = useRef(false)
  const [displayName, setDisplayName] = useState(user.displayName)
  const [interests, setInterests] = useState<string[]>(user.interests)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [notice, setNotice] = useState<Notice | null>(null)
  const [saving, setSaving] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [focusRequest, setFocusRequest] = useState(0)

  const changes = profileChanges(user, { displayName, interests })
  const dirty = Object.keys(changes).length > 0
  const busy = saving || reloading
  const fieldError = (field: string) => {
    const error = fieldErrors[field]
    return error && t(error)
  }

  useEffect(() => {
    if (focusRequest > 0) {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    }
  }, [focusRequest])

  function edited(field: 'displayName' | 'interests') {
    setFieldErrors((errors) => (errors[field] === undefined ? errors : { ...errors, [field]: undefined }))
    setNotice((current) => (current?.kind === 'saved' ? null : current))
  }

  async function save() {
    if (savingRef.current || !dirty) return
    const clientErrors: FieldErrors = {}
    if (changes.displayName !== undefined) clientErrors.displayName = validateDisplayName(displayName)
    if (interests.length > MAX_INTERESTS) clientErrors.interests = msg('profile.maxInterests', { max: MAX_INTERESTS })
    if (hasErrors(clientErrors)) {
      setFieldErrors(clientErrors)
      setNotice(null)
      setFocusRequest((request) => request + 1)
      return
    }

    savingRef.current = true
    setSaving(true)
    setFieldErrors({})
    setNotice(null)
    try {
      const saved = await updateProfile(changes)
      setDisplayName(saved.displayName)
      setInterests(saved.interests)
      setNotice({ kind: 'saved' })
    } catch (error) {
      const failure = describeSaveFailure(error)
      if (failure.kind === 'invalid') {
        setFieldErrors(failure.fieldErrors)
        setNotice({ kind: 'error', message: msg('error.correctFields'), retry: false })
        setFocusRequest((request) => request + 1)
      } else {
        setNotice(failure)
      }
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  async function loadLatest() {
    const before = user
    setReloading(true)
    try {
      const latest = await reloadProfile()
      // Fields the user did not edit follow the latest version; otherwise saving the edits would
      // also write back the old value of an untouched field and undo the other change.
      if (displayName.trim() === before.displayName) setDisplayName(latest.displayName)
      if (sameSelection(interests, before.interests)) setInterests(latest.interests)
      setNotice({ kind: 'reloaded' })
    } catch {
      setNotice({ kind: 'reload-failed' })
    } finally {
      setReloading(false)
    }
  }

  function discard() {
    setDisplayName(user.displayName)
    setInterests(user.interests)
    setFieldErrors({})
    setNotice(null)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void save()
  }

  return (
    <form ref={formRef} onSubmit={handleSubmit} noValidate aria-busy={busy} className="space-y-6">
      {notice?.kind === 'saved' && <FormAlert tone="success">{t('profile.saved')}</FormAlert>}
      {notice?.kind === 'error' && (
        <FormAlert tone="error">
          <p>{t(notice.message)}</p>
          {notice.retry && (
            <button type="button" onClick={() => void save()} disabled={busy} className={`${secondaryButton} mt-3`}>
              {t('common.tryAgain')}
            </button>
          )}
        </FormAlert>
      )}
      {(notice?.kind === 'conflict' || notice?.kind === 'reload-failed') && (
        <FormAlert tone="error">
          <p>{notice.kind === 'conflict' ? t('profile.conflict') : t('profile.reloadFailed')}</p>
          <button type="button" onClick={() => void loadLatest()} disabled={busy} className={`${secondaryButton} mt-3`}>
            {reloading ? t('common.loading') : t('profile.loadLatest')}
          </button>
        </FormAlert>
      )}
      {notice?.kind === 'reloaded' && (
        <FormAlert tone="info">{dirty ? t('profile.reloadedDirty') : t('profile.reloadedClean')}</FormAlert>
      )}

      <TextField
        id="profile-display-name"
        label={t('common.displayName')}
        autoComplete="nickname"
        maxLength={30}
        hint={t('common.displayNameHint')}
        value={displayName}
        onChange={(event) => {
          setDisplayName(event.target.value)
          edited('displayName')
        }}
        error={fieldError('displayName')}
        disabled={busy}
      />

      <InterestPicker
        id="profile-interests"
        catalog={catalog}
        onRetryCatalog={onRetryCatalog}
        selected={interests}
        onChange={(codes) => {
          setInterests(codes)
          edited('interests')
        }}
        error={fieldError('interests')}
        disabled={busy}
      />

      <div className="flex flex-wrap items-center gap-3 border-t border-zinc-800 pt-5">
        <button type="submit" disabled={!dirty || busy} className={primaryButton}>
          {saving ? t('common.saving') : t('profile.save')}
        </button>
        <button type="button" onClick={discard} disabled={!dirty || busy} className={secondaryButton}>
          {t('profile.discard')}
        </button>
        <p aria-live="polite" className="text-sm text-zinc-400">
          {dirty && !saving ? t('profile.unsaved') : ''}
        </p>
      </div>
    </form>
  )
}

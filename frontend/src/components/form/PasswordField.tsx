import { useState } from 'react'
import { useI18n } from '../../i18n/useI18n'
import { TextField, type TextFieldProps } from './TextField'

/** A {@link TextField} for passwords with a show/hide toggle. The value lives only in component state. */
export function PasswordField(props: Omit<TextFieldProps, 'type' | 'trailing'>) {
  const [visible, setVisible] = useState(false)
  const { t } = useI18n()

  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      spellCheck={false}
      trailing={
        <button
          type="button"
          onClick={() => setVisible((value) => !value)}
          aria-label={visible ? t('password.hideLabel') : t('password.showLabel')}
          aria-controls={props.id}
          className="rounded-md px-2.5 py-1 text-xs font-medium text-zinc-400 transition hover:bg-zinc-800 hover:text-zinc-100 focus-visible:outline-2 focus-visible:outline-emerald-400"
        >
          {visible ? t('password.hide') : t('password.show')}
        </button>
      }
    />
  )
}

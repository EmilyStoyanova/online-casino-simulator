import { useState } from 'react'
import { TextField, type TextFieldProps } from './TextField'

/** A {@link TextField} for passwords with a show/hide toggle. The value lives only in component state. */
export function PasswordField(props: Omit<TextFieldProps, 'type' | 'trailing'>) {
  const [visible, setVisible] = useState(false)

  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      spellCheck={false}
      trailing={
        <button
          type="button"
          onClick={() => setVisible((value) => !value)}
          aria-label={visible ? 'Hide password' : 'Show password'}
          aria-controls={props.id}
          className="rounded-md px-2.5 py-1 text-xs font-medium text-zinc-400 transition hover:bg-zinc-800 hover:text-zinc-100 focus-visible:outline-2 focus-visible:outline-emerald-400"
        >
          {visible ? 'Hide' : 'Show'}
        </button>
      }
    />
  )
}

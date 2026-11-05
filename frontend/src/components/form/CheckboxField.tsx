import type { InputHTMLAttributes, ReactNode, Ref } from 'react'

export interface CheckboxFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'type' | 'className'> {
  id: string
  label: ReactNode
  error?: string
  ref?: Ref<HTMLInputElement>
}

export function CheckboxField({ id, label, error, ref, ...inputProps }: CheckboxFieldProps) {
  const errorId = error ? `${id}-error` : undefined

  return (
    <div className="space-y-1.5">
      <div className="flex items-start gap-3">
        <input
          ref={ref}
          id={id}
          type="checkbox"
          aria-invalid={error ? true : undefined}
          aria-describedby={errorId}
          className="mt-0.5 size-4 shrink-0 cursor-pointer rounded border-zinc-600 bg-zinc-950 accent-emerald-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400"
          {...inputProps}
        />
        <label htmlFor={id} className="cursor-pointer text-sm leading-snug text-zinc-300">
          {label}
        </label>
      </div>
      {error && (
        <p id={errorId} className="pl-7 text-xs font-medium text-rose-300">
          {error}
        </p>
      )}
    </div>
  )
}

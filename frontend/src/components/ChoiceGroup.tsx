import { useId } from 'react'
import type { FieldViolationReason } from '../api/client'
import FieldError from './FieldError'

interface Props<T extends string> {
  label: string
  options: readonly { value: T; label: string }[]
  value: T | ''
  onChange: (value: T) => void
  required?: boolean
  disabled?: boolean
  error?: FieldViolationReason
}

export default function ChoiceGroup<T extends string>({ label, options, value, onChange, required, disabled, error }: Props<T>) {
  const id = useId()
  return <fieldset role="radiogroup" aria-labelledby={`${id}-label`} aria-required={required} aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-error` : undefined} disabled={disabled} className="min-w-0 space-y-3">
    <legend id={`${id}-label`} className="font-bold">{label}<span aria-hidden="true" className="ml-2 text-small font-normal text-text-secondary">{required ? '필수' : '선택'}</span></legend>
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      {options.map((option) => <label key={option.value} className="min-w-0">
        <input type="radio" name={id} value={option.value} checked={value === option.value} onChange={() => onChange(option.value)} required={required} disabled={disabled} aria-describedby={error ? `${id}-error` : undefined} className="peer sr-only" />
        <span className="flex min-h-13 cursor-pointer items-center justify-center gap-2 rounded-control border border-border bg-surface px-4 py-3 text-small font-semibold peer-checked:border-primary peer-checked:bg-surface-soft peer-checked:text-primary peer-checked:ring-2 peer-checked:ring-primary peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-primary peer-disabled:cursor-not-allowed peer-disabled:bg-surface-muted peer-disabled:text-text-secondary hover:bg-surface-soft">
          <span aria-hidden="true">{value === option.value ? '●' : '○'}</span>{option.label}
        </span>
      </label>)}
    </div>
    {error && <FieldError id={`${id}-error`} reason={error} />}
  </fieldset>
}

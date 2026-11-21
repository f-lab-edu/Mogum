import { useId } from 'react'
import type { FieldViolationReason } from '../api/client'
import FieldError from './FieldError'

interface Props {
  label: string
  value: string
  onChange: (value: string) => void
  required?: boolean
  disabled?: boolean
  multiline?: boolean
  maxCodePoints?: number
  error?: FieldViolationReason
}

export default function TextField({ label, value, onChange, required, disabled, multiline, maxCodePoints, error }: Props) {
  const id = useId()
  const length = Array.from(value).length
  const reason = maxCodePoints !== undefined && length > maxCodePoints ? 'TOO_LONG' : error
  const describedBy = [maxCodePoints !== undefined && `${id}-length`, reason && `${id}-error`].filter(Boolean).join(' ')
  const props = {
    id, value, required, disabled,
    'aria-invalid': reason ? true : undefined,
    'aria-describedby': describedBy || undefined,
    className: `w-full min-h-13 rounded-control border bg-surface px-4 py-3 text-body text-text placeholder:text-text-secondary disabled:bg-surface-muted disabled:text-text-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${reason ? 'border-error' : 'border-border'}`,
  }
  return <div className="space-y-2">
    <label htmlFor={id} className="block font-bold">{label}<span aria-hidden="true" className="ml-2 text-small font-normal text-text-secondary">{required ? '필수' : '선택'}</span></label>
    {multiline
      ? <textarea {...props} rows={4} onChange={(event) => onChange(event.target.value)} />
      : <input {...props} type="text" onChange={(event) => onChange(event.target.value)} />}
    {maxCodePoints !== undefined && <p id={`${id}-length`} className="text-small text-text-secondary">{length} / {maxCodePoints}자</p>}
    {reason && <FieldError id={`${id}-error`} reason={reason} />}
  </div>
}

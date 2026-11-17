import { useId } from 'react'
import type { ReactNode } from 'react'

export default function CheckboxField({ label, checked, onChange, disabled, description }: {
  label: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  description?: ReactNode
}) {
  const id = useId()
  return <div className="space-y-3 rounded-control border border-border bg-surface-soft p-4">
    <label className="flex min-h-11 cursor-pointer items-center gap-3 font-bold">
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} disabled={disabled} aria-describedby={description ? `${id}-description` : undefined} className="size-6 shrink-0 cursor-pointer accent-primary disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary" />
      {label}
    </label>
    {description && <div id={`${id}-description`} className="space-y-3 text-small text-text-secondary">{description}</div>}
  </div>
}

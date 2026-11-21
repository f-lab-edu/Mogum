import { useId } from 'react'

export default function ProgressBar({ label, completed, total }: { label: string; completed: number; total: number }) {
  const id = useId()
  return <div className="space-y-3">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span id={id} className="font-bold">{label}</span>
      <span className="font-bold text-primary">{completed} / {total}</span>
    </div>
    <div role="progressbar" aria-labelledby={id} aria-valuemin={0} aria-valuenow={completed} aria-valuemax={total} className="h-3 overflow-hidden rounded-control bg-surface-muted">
      <div className="h-full rounded-control bg-primary" style={{ width: `${total === 0 ? 0 : completed / total * 100}%` }} />
    </div>
  </div>
}

import type { ReactNode } from 'react'

export default function FailureNotice({ title, message, children }: {
  title: string
  message: string
  children?: ReactNode
}) {
  return (
    <div role="alert" className="space-y-3 rounded-control border border-border bg-surface-soft p-4">
      <p className="font-bold text-error">{title}</p>
      <p className="text-small">{message}</p>
      {children}
    </div>
  )
}

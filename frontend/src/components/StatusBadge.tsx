import type { ReactNode } from 'react'

export default function StatusBadge({ children }: { children: ReactNode }) {
  return <span className="inline-flex items-center rounded-control bg-surface-soft px-3 py-1 text-small font-bold text-primary">{children}</span>
}

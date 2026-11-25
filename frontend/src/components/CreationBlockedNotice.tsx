import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'

// Keep the shared classification with its notice; this module also exports a non-component helper.
// eslint-disable-next-line react-refresh/only-export-components
export function isCreationBlockedError(error: unknown): error is ApiError {
  return error instanceof ApiError
    && (error.code === 'CREDIT_INSUFFICIENT' || error.code === 'TOO_MANY_IN_PROGRESS')
}

export default function CreationBlockedNotice({ error, children }: {
  error: ApiError
  children?: ReactNode
}) {
  return <div role="status" className="space-y-3 rounded-control border border-border bg-surface-soft p-4">
    <p className="font-bold text-text">지금은 만들 수 없어요</p>
    <p className="text-small">{error.message}</p>
    {error.code === 'CREDIT_INSUFFICIENT' && <Link to="/profile" className="inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">남은 크레딧 보기</Link>}
    {children && <div>{children}</div>}
  </div>
}

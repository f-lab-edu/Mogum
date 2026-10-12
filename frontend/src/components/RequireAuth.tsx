import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useMe } from '../api/useMe'
import { takeReturnPath } from '../lib/authNavigation'
import { messages } from '../lib/messages'

export default function RequireAuth() {
  const me = useMe()
  const { pathname } = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    if (me.isSuccess && pathname === '/') {
      const path = takeReturnPath()
      if (path) void navigate(path, { replace: true })
    }
  }, [me.isSuccess, pathname, navigate])

  if (me.isPending || (me.error instanceof ApiError && me.error.status === 401)) {
    return (
      <main className="min-h-screen bg-paper px-4 py-8">
        <div role="status" aria-label="로그인 확인 중" aria-busy="true" className="mx-auto max-w-3xl space-y-4">
          <span className="sr-only">로그인 확인 중</span>
          <div aria-hidden="true" className="h-8 w-32 rounded-md bg-muted motion-safe:animate-pulse" />
          <div aria-hidden="true" className="h-4 w-full rounded-md bg-muted motion-safe:animate-pulse" />
          <div aria-hidden="true" className="h-4 w-2/3 rounded-md bg-muted motion-safe:animate-pulse" />
        </div>
      </main>
    )
  }

  if (me.isError) {
    const message = me.error instanceof ApiError && me.error.code === 'RATE_LIMITED'
      ? messages.rateLimited : messages.loadFailed

    return (
      <main className="min-h-screen bg-paper px-4 py-8">
        <div className="mx-auto max-w-3xl space-y-4">
          <p role="status" aria-live="polite" className="text-sm text-danger">{message}</p>
          <button disabled={me.isFetching} onClick={() => void me.refetch()} className="h-11 rounded-md border border-line bg-surface px-4 text-ink hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">다시 시도</button>
        </div>
      </main>
    )
  }

  return <Outlet />
}

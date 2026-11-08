import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useMe } from '../api/useMe'
import { takeReturnPath } from '../lib/authNavigation'
import { messages } from '../lib/messages'
import AccountLayout from './AccountLayout'
import Button from './Button'

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

  if (me.isPending || (me.error instanceof ApiError && me.error.code === 'UNAUTHORIZED')) {
    return (
      <AccountLayout>
        <div role="status" aria-label="로그인 확인 중" aria-busy="true" className="space-y-4">
          <span className="sr-only">로그인 확인 중</span>
          <div aria-hidden="true" className="h-8 w-32 rounded-control bg-surface-muted motion-safe:animate-pulse" />
          <div aria-hidden="true" className="h-4 w-full rounded-control bg-surface-muted motion-safe:animate-pulse" />
          <div aria-hidden="true" className="h-4 w-2/3 rounded-control bg-surface-muted motion-safe:animate-pulse" />
        </div>
      </AccountLayout>
    )
  }

  if (me.isError) {
    const message = me.error instanceof ApiError && me.error.code === 'RATE_LIMITED'
      ? messages.rateLimited : messages.loadFailed

    return (
      <AccountLayout>
        <div className="space-y-4">
          <p role="status" aria-live="polite" className="text-small text-error">{message}</p>
          <Button disabled={me.isFetching} onClick={() => void me.refetch()}>다시 시도</Button>
        </div>
      </AccountLayout>
    )
  }

  return <Outlet />
}

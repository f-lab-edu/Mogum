import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router-dom'
import { ApiError, apiFetch, CONNECTION_ERROR_MESSAGE } from '../api/client'
import type { Me } from '../api/useMe'
import AccountIdentity from '../components/AccountIdentity'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'

export default function ProfilePage() {
  const me = useOutletContext<Me>()
  const client = useQueryClient()
  const navigate = useNavigate()
  const heading = useRef<HTMLHeadingElement>(null)
  const logout = useMutation({
    mutationFn: () => apiFetch<void>('/auth/logout', { method: 'POST' }),
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: ['me'], exact: true })
      client.removeQueries({ queryKey: ['me'], exact: true })
      void navigate('/login', { replace: true })
    },
  })

  useEffect(() => {
    document.title = '내 정보 · 머금'
    heading.current?.focus()
    void client.invalidateQueries({ queryKey: ['me'], exact: true })
  }, [client])

  const submitLogout = () => {
    if (!logout.isPending) logout.mutate()
  }
  const date = me.credit?.nextRefillDate.split('-')

  return <div className="space-y-6">
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">내 정보</h1>
    <div className="space-y-6 rounded-panel border border-border bg-surface p-6 sm:p-8">
      <AccountIdentity name={me.name} email={me.email} />
      {me.credit && date && <section aria-label="크레딧" className="space-y-2">
        <p className="text-title font-extrabold text-primary">남은 크레딧 {me.credit.balance}</p>
        <p className="text-text-secondary"><time dateTime={me.credit.nextRefillDate}>{Number(date[1])}월 {Number(date[2])}일에 채워져요</time></p>
      </section>}
    </div>
    <div className="flex flex-col items-start gap-4">
      <Button variant="secondary" disabled={logout.isPending} onClick={submitLogout}>로그아웃</Button>
      <Link to="/privacy" className="inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">처리방침 보기</Link>
    </div>
    {logout.isError && <FailureNotice title="로그아웃하지 못했어요." message={logout.error instanceof ApiError ? logout.error.message : CONNECTION_ERROR_MESSAGE}>
      <Button disabled={logout.isPending} onClick={submitLogout}>다시 시도</Button>
    </FailureNotice>}
  </div>
}

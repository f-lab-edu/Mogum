import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router-dom'
import { ApiError, apiFetch, CONNECTION_ERROR_MESSAGE } from '../api/client'
import type { Me } from '../api/useMe'
import AccountIdentity from '../components/AccountIdentity'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'
import InlineConfirmation from '../components/InlineConfirmation'

export default function ProfilePage() {
  const me = useOutletContext<Me>()
  const client = useQueryClient()
  const navigate = useNavigate()
  const heading = useRef<HTMLHeadingElement>(null)
  const [confirming, setConfirming] = useState(false)
  const submitting = useRef(false)
  const logout = useMutation({
    mutationFn: () => apiFetch<void>('/auth/logout', { method: 'POST' }),
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: ['me'], exact: true })
      client.removeQueries({ queryKey: ['me'], exact: true })
      void navigate('/login', { replace: true })
    },
    onSettled: () => { submitting.current = false },
  })
  const withdrawal = useMutation({
    mutationFn: () => apiFetch<void>('/account/withdraw', { method: 'POST' }),
    onSuccess: async () => {
      await client.cancelQueries()
      client.removeQueries()
      void navigate('/login', { replace: true })
    },
    onSettled: () => { submitting.current = false },
  })
  const busy = logout.isPending || withdrawal.isPending

  useEffect(() => {
    document.title = '내 정보 · 머금'
    heading.current?.focus()
    void client.invalidateQueries({ queryKey: ['me'], exact: true })
  }, [client])

  const submitLogout = () => {
    if (submitting.current) return
    submitting.current = true
    logout.mutate()
  }
  const date = me.credit?.nextRefillDate.split('-')

  return <div className="space-y-6">
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">내 정보</h1>
    <div className="space-y-6 rounded-panel border border-border bg-surface p-6 sm:p-8">
      <AccountIdentity name={me.name} email={me.email} title="구글 계정" />
      {me.credit && date && <section aria-label="크레딧" className="space-y-2">
        <p className="text-title font-extrabold text-primary">남은 크레딧 {me.credit.balance}</p>
        <p className="text-text-secondary"><time dateTime={me.credit.nextRefillDate}>{Number(date[1])}월 {Number(date[2])}일에 채워져요</time></p>
      </section>}
    </div>
    <div className="flex flex-col items-start gap-4">
      <Button variant="secondary" disabled={busy} onClick={submitLogout}>로그아웃</Button>
      <InlineConfirmation open={confirming} onOpenChange={setConfirming} disabled={busy}
        triggerLabel="탈퇴" title="탈퇴할까요?" confirmLabel="탈퇴하기"
        message="코스 · 로드맵 · 카드 · 복습 기록 · 크레딧이 모두 지워지고 되돌릴 수 없어요. 다시 쓰려면 새로 신청하고 승인을 받아야 해요."
        onConfirm={() => {
          if (submitting.current) return
          submitting.current = true
          withdrawal.mutate()
        }} />
      <Link to="/privacy" className="inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">처리방침 보기</Link>
    </div>
    {logout.isError && <FailureNotice title="로그아웃하지 못했어요." message={logout.error instanceof ApiError ? logout.error.message : CONNECTION_ERROR_MESSAGE}>
      <Button disabled={busy} onClick={submitLogout}>다시 시도</Button>
    </FailureNotice>}
    {withdrawal.isError && <FailureNotice title="탈퇴하지 못했어요." message={withdrawal.error instanceof ApiError ? withdrawal.error.message : CONNECTION_ERROR_MESSAGE}>
      <p className="text-small">안내를 확인한 뒤 탈퇴하기를 다시 눌러 주세요.</p>
    </FailureNotice>}
  </div>
}

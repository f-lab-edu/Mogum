import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { Link, Navigate, useLocation, useNavigate, useOutletContext } from 'react-router-dom'
import { ApiError, apiFetch } from '../api/client'
import type { Me } from '../api/useMe'
import AccountIdentity from '../components/AccountIdentity'
import AccountLayout from '../components/AccountLayout'
import Button from '../components/Button'
import Mogumi from '../components/Mogumi'
import { openGoogleLogin } from '../lib/authNavigation'
import PlaceholderPage from './PlaceholderPage'

const screens: Record<string, { path: string; title: string }> = {
  NOT_APPLIED: { path: '/apply', title: '머금 이용 신청' },
  APPLIED: { path: '/waiting-approval', title: '가입 승인을 기다리고 있어요' },
  REJECTED: { path: '/rejected', title: '이번에는 함께하지 못하게 되었어요' },
  APPROVED: { path: '/', title: '머금' },
}

type Action = 'apply' | 'switch' | 'logout'

export default function AccountPage() {
  const me = useOutletContext<Me>()
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const client = useQueryClient()
  const heading = useRef<HTMLHeadingElement>(null)
  const screen = screens[me.status]
  const application = me.status === 'NOT_APPLIED'
  const mutation = useMutation({
    mutationFn: async (action: Action) => action === 'apply'
      ? apiFetch<Me>('/account/apply', { method: 'POST' })
      : apiFetch<void>('/auth/logout', { method: 'POST' }),
    onSuccess: async (result, action) => {
      await client.cancelQueries({ queryKey: ['me'], exact: true })
      if (action === 'apply') {
        client.setQueryData(['me'], result)
      } else {
        client.removeQueries({ queryKey: ['me'], exact: true })
        if (action === 'switch') openGoogleLogin()
        else void navigate('/login', { replace: true })
      }
    },
  })

  useEffect(() => {
    document.title = `${screen.title} · 머금`
    heading.current?.focus()
  }, [screen.title, pathname])

  if (pathname !== screen.path) return <Navigate to={screen.path} replace />
  if (me.status === 'APPROVED') return <PlaceholderPage />

  const submit = (action: Action) => {
    if (!mutation.isPending) mutation.mutate(action)
  }

  return (
    <AccountLayout>
      <div className="text-center">
        <Mogumi />
        <h1 ref={heading} tabIndex={-1} className="mt-6 text-title font-extrabold text-balance">{screen.title}</h1>
      </div>
      {me.status === 'APPLIED' && (
        <p className="mt-6 rounded-control border border-border bg-surface-soft p-4 text-small">
          승인돼도 따로 알리지 않아요. 나중에 다시 들어와 확인해 주세요.
        </p>
      )}
      <div className="mt-6"><AccountIdentity name={me.name} email={me.email} /></div>
      <div className="mt-4 rounded-control bg-surface-soft p-4 text-small text-text-secondary">
        {application ? <p>신청하면 구글 이메일 · 이름을 저장해요</p>
          : me.status === 'APPLIED'
            ? <p>신청을 지우려면 처리방침에 적힌 방법으로 운영자에게 요청해 주세요.</p>
            : <p>신청 정보(구글 이메일 · 이름)가 남아 있어요. 지우려면 처리방침에 적힌 방법으로 운영자에게 요청해 주세요.</p>}
        <Link to="/privacy" className="mt-3 inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">처리방침 보기</Link>
      </div>
      <div className="mt-6 flex flex-col gap-4">
        {application && <Button disabled={mutation.isPending} onClick={() => submit('apply')}>신청</Button>}
        <Button variant="secondary" disabled={mutation.isPending} onClick={() => submit(application ? 'switch' : 'logout')}>
          {application ? '다른 계정으로 로그인' : '로그아웃'}
        </Button>
      </div>
      {mutation.isError && (
        <div className="mt-6 space-y-3">
          <div role="alert" className="text-small text-error">
            <p>{mutation.variables === 'apply' ? '신청하지 못했어요.' : '로그아웃하지 못했어요.'}</p>
            <p>{mutation.error instanceof ApiError ? mutation.error.message : '연결하지 못했어요. 다시 시도해 주세요.'}</p>
          </div>
          <Button className="w-full" onClick={() => { if (mutation.variables) submit(mutation.variables) }}>다시 시도</Button>
        </div>
      )}
    </AccountLayout>
  )
}

import '@fontsource/roboto/500.css'
import { useEffect, useRef } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { googleLoginUrl } from '../api/client'
import AccountLayout from '../components/AccountLayout'
import Mogumi from '../components/Mogumi'
import { messages } from '../lib/messages'

export default function LoginPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const heading = useRef<HTMLHeadingElement>(null)
  const notice = useRef<HTMLParagraphElement>(null)
  const sessionExpired = location.state?.sessionExpired === true
  const message = params.has('error')
    ? messages.loginFailed
    : sessionExpired ? messages.sessionExpired : ''

  useEffect(() => {
    document.title = '로그인 · 머금'
    // A callback page may mount with its notice already populated; focus also
    // announces that initial text rather than relying only on a live update.
    if (message) notice.current?.focus()
    else heading.current?.focus()
  }, [message])

  return (
    <AccountLayout>
      <div className="text-center">
        <Mogumi />
        <div className="mt-6 space-y-3">
          <p className="text-small font-bold text-primary">머금</p>
          <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold text-balance">배운 걸 잊지 않게, 복습이 다음 강의를 바꾸는 학습</h1>
          <p className="text-small text-pretty text-text-secondary">주제만 정하면 AI가 강의를 짜고 만들어요. 복습 결과를 보고 이미 아는 건 짧게, 흔들리는 건 자세히 다음 강의를 만들어요.</p>
        </div>
        <div className="mt-8 rounded-control border border-border bg-surface-soft p-4 text-left text-small">
          <p className="font-bold">정원제로 운영해요</p>
          <p className="mt-2 text-text-secondary">구글로 로그인한 뒤 신청하면, 운영자가 확인하고 승인해요.</p>
        </div>
        <div className="mt-6">
          <a href={googleLoginUrl()} className="flex h-11 w-full items-center justify-center gap-2.5 rounded-google border border-google-border bg-surface px-3 font-google text-google font-medium tracking-google text-google-text focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
            <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true" className="shrink-0">
              <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
              <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
              <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
              <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
            </svg>
            <span>Google 계정으로 계속하기</span>
          </a>
        </div>
        <p ref={notice} tabIndex={-1} role="status" aria-live="polite" aria-atomic="true" className={message ? 'mt-6 rounded-control border border-border bg-surface-soft px-4 py-3 text-body text-text' : undefined}>{message}</p>
      </div>
    </AccountLayout>
  )
}

import { useEffect, useRef } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { googleLoginUrl } from '../api/client'
import { messages } from '../lib/messages'

export default function LoginPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const heading = useRef<HTMLHeadingElement>(null)
  const notice = useRef<HTMLParagraphElement>(null)
  const sessionExpired = location.state?.sessionExpired === true
  const message = params.has('error')
    ? params.get('error') === 'not_allowed' ? messages.notAllowed : messages.loginFailed
    : sessionExpired ? messages.sessionExpired : ''

  useEffect(() => {
    document.title = '로그인 · 머금'
    // A callback page may mount with its notice already populated; focus also
    // announces that initial text rather than relying only on a live update.
    if (message) notice.current?.focus()
    else heading.current?.focus()
  }, [message])

  return (
    <main className="min-h-screen bg-paper px-4 py-8 font-sans text-ink-body">
      <div className="mx-auto max-w-3xl space-y-8">
        <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">머금</h1>
        <a href={googleLoginUrl()} className="inline-flex h-11 items-center rounded-md bg-accent px-4 text-base text-white hover:bg-accent-strong focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">Google 계정으로 계속하기</a>
        <p ref={notice} tabIndex={-1} role="status" aria-live="polite" aria-atomic="true" className="text-sm text-danger focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">{message}</p>
      </div>
    </main>
  )
}

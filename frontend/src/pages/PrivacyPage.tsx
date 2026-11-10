import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'

export default function PrivacyPage() {
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    document.title = '개인정보처리방침 · 머금'
    heading.current?.focus()
  }, [])

  return (
    <AccountLayout>
      <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">개인정보처리방침</h1>
      <p className="mt-6 text-text-secondary">준비 중이에요.</p>
      <Link to="/" className="mt-6 inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">돌아가기</Link>
    </AccountLayout>
  )
}

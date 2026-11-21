import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'

export default function PlaceholderPage({ title = '머금' }: { title?: string }) {
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    document.title = `${title === '머금' ? '홈' : title} · 머금`
    heading.current?.focus()
  }, [title])

  return (
    <div className="space-y-6">
      <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">{title}</h1>
      {title === '머금' && <Link to="/new" className="inline-flex min-h-13 items-center rounded-control bg-primary px-6 py-3 font-bold text-on-primary shadow-button hover:bg-primary-hover active:translate-y-button-press active:shadow-button-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">새로 배우기</Link>}
    </div>
  )
}

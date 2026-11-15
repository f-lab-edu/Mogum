import { useEffect, useRef } from 'react'

export default function PlaceholderPage({ title = '머금' }: { title?: string }) {
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    document.title = `${title === '머금' ? '홈' : title} · 머금`
    heading.current?.focus()
  }, [title])

  return (
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">{title}</h1>
  )
}

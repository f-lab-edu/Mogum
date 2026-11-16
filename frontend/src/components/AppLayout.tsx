import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { Navigate, NavLink, useLocation, useNavigate, useOutletContext } from 'react-router-dom'
import type { Me } from '../api/useMe'
import Mogumi from './Mogumi'

const tabs = [
  {
    label: '홈', path: '/',
    icon: <><path d="M3 10.5L12 3l9 7.5v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9z" /><polyline points="9 21 9 12 15 12 15 21" /></>,
  },
  {
    label: '코스', path: '/courses',
    icon: <><path d="M22 10v6M2 10l10-5 10 5-10 5z" /><path d="M6 12v5c3 3 9 3 12 0v-5" /></>,
  },
  {
    label: '로드맵', path: '/roadmaps',
    icon: <><circle cx="6" cy="19" r="3" /><path d="M9 19h8.5a4.5 4.5 0 0 0 4.5-4.5v-5A4.5 4.5 0 0 0 17.5 5H9" /><circle cx="6" cy="5" r="3" /></>,
  },
  {
    label: '복습', path: '/review',
    icon: <><rect x="3" y="5" width="14" height="15" rx="2" /><path d="M7 2.5h12a2 2 0 0 1 2 2v13" /></>,
  },
  {
    label: '내 정보', path: '/profile',
    icon: <><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></>,
  },
]

function Brand({ className = '' }: { className?: string }) {
  return <div className={`flex items-center gap-2 ${className}`}>
    <Mogumi size="logo" />
    <span className="text-title font-extrabold text-primary">머금</span>
  </div>
}

function TabLinks({ vertical = false }: { vertical?: boolean }) {
  return tabs.map(({ label, path, icon }) => (
    <NavLink
      key={path}
      to={path}
      end={path === '/'}
      className={({ isActive }) => `flex min-h-11 min-w-0 items-center rounded-control text-small focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${vertical ? 'justify-start gap-3 px-4 py-2' : 'flex-1 flex-col justify-center gap-1 px-2 py-1'} ${isActive ? 'bg-surface-soft font-bold text-primary' : 'font-semibold text-text-secondary hover:bg-surface-soft'}`}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" focusable="false" className="size-6 shrink-0">{icon}</svg>
      <span>{label}</span>
    </NavLink>
  ))
}

export default function AppLayout({ children, title }: { children: ReactNode; title?: string }) {
  const me = useOutletContext<Me>()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (me.status === 'APPROVED' && title) {
      document.title = `${title} · 머금`
      heading.current?.focus()
    }
  }, [me.status, title, pathname])

  if (me.status !== 'APPROVED') return <Navigate to="/" replace />

  return (
    <div className="min-h-dvh bg-canvas font-sans text-body text-text">
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-64 flex-col gap-6 border-r border-border bg-surface p-4 lg:flex">
        <Brand />
        <nav aria-label="주요 화면 (데스크톱)" className="flex flex-col gap-2"><TabLinks vertical /></nav>
      </aside>
      <div className="lg:ml-64">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b border-border bg-surface px-4 lg:px-8">
          {title ? <>
            <button type="button" aria-label="뒤로 가기" onClick={() => void navigate(-1)} className="flex min-h-11 min-w-11 shrink-0 cursor-pointer items-center justify-center rounded-control text-text-secondary hover:bg-surface-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" focusable="false" className="size-6"><path d="m15 18-6-6 6-6" /></svg>
            </button>
            <h1 ref={heading} tabIndex={-1} className="min-w-0 truncate text-body font-bold">{title}</h1>
          </> : <Brand className="lg:hidden" />}
        </header>
        <main className="px-4 pt-6 pb-24 lg:px-8 lg:pb-8">
          <div className="mx-auto max-w-content">{children}</div>
        </main>
      </div>
      <nav aria-label="주요 화면 (모바일)" className="fixed inset-x-0 bottom-0 z-40 flex h-20 border-t border-border bg-surface p-2 lg:hidden">
        <TabLinks />
      </nav>
    </div>
  )
}

import type { ReactNode } from 'react'

export default function AccountLayout({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-canvas px-4 py-8 font-sans text-body text-text">
      <div className="w-full max-w-account rounded-panel border border-border bg-surface p-6 shadow-account sm:p-8">
        {children}
      </div>
    </main>
  )
}

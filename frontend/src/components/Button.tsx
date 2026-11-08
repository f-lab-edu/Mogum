import type { ComponentProps } from 'react'

export default function Button({ type = 'button', className = '', ...props }: ComponentProps<'button'>) {
  return (
    <button
      {...props}
      type={type}
      className={`min-h-13 cursor-pointer rounded-control bg-primary px-6 py-3 text-body font-bold text-on-primary shadow-button enabled:hover:bg-primary-hover enabled:active:translate-y-button-press enabled:active:shadow-button-pressed disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-text-secondary disabled:shadow-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${className}`}
    />
  )
}

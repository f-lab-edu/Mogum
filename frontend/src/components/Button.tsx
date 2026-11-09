import type { ComponentProps } from 'react'

type Props = ComponentProps<'button'> & { variant?: 'primary' | 'secondary' }

export default function Button({ type = 'button', variant = 'primary', className = '', ...props }: Props) {
  const appearance = variant === 'primary'
    ? 'bg-primary text-on-primary shadow-button enabled:hover:bg-primary-hover enabled:active:translate-y-button-press enabled:active:shadow-button-pressed'
    : 'border border-border bg-surface-soft text-text-secondary enabled:hover:bg-surface-muted'
  return (
    <button
      {...props}
      type={type}
      className={`min-h-13 cursor-pointer rounded-control px-6 py-3 text-body font-bold disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-text-secondary disabled:shadow-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${appearance} ${className}`}
    />
  )
}

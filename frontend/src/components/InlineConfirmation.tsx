import { useEffect, useId, useRef } from 'react'
import Button from './Button'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
  disabled?: boolean
  triggerLabel: string
  title: string
  message: string
  confirmLabel: string
}

export default function InlineConfirmation({ open, onOpenChange, onConfirm, disabled = false, triggerLabel, title, message, confirmLabel }: Props) {
  const id = useId()
  const trigger = useRef<HTMLButtonElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)
  useEffect(() => { if (open) cancel.current?.focus() }, [open])

  return <div className="space-y-4">
    <Button ref={trigger} variant="secondary" disabled={disabled} aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => onOpenChange(true)}>{triggerLabel}</Button>
    {open && <section id={id} aria-labelledby={`${id}-title`} aria-describedby={`${id}-message`} className="space-y-4 rounded-control border border-border bg-surface-soft p-4">
      <h3 id={`${id}-title`} className="font-bold">{title}</h3>
      <p id={`${id}-message`} className="text-small">{message}</p>
      <div className="flex flex-wrap gap-3">
        <Button disabled={disabled} onClick={onConfirm}>{confirmLabel}</Button>
        <Button ref={cancel} variant="secondary" disabled={disabled} onClick={() => { onOpenChange(false); trigger.current?.focus() }}>취소</Button>
      </div>
    </section>}
  </div>
}

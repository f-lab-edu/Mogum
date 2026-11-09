import { useId } from 'react'
import type { Me } from '../api/useMe'

export default function AccountIdentity({ name, email }: Pick<Me, 'name' | 'email'>) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="rounded-control border border-border bg-surface-soft p-4 text-left">
      <h2 id={headingId} className="text-small font-bold text-primary">신청 계정</h2>
      <dl className="mt-3 space-y-2">
        <div>
          <dt className="text-small text-text-secondary">이름</dt>
          <dd className="font-bold">{name}</dd>
        </div>
        <div>
          <dt className="text-small text-text-secondary">구글 이메일</dt>
          <dd>{email}</dd>
        </div>
      </dl>
    </section>
  )
}

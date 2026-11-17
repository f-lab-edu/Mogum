import type { FieldViolationReason } from '../api/client'

const explanations: Record<string, string> = {
  REQUIRED: '필수 입력이에요.',
  TOO_LONG: '글자 수 상한을 넘었어요.',
  INVALID_VALUE: '보기에서 골라 주세요.',
  NOT_ALLOWED: '이 칸은 비워 주세요.',
}

export default function FieldError({ id, reason }: { id: string; reason: FieldViolationReason }) {
  return <p id={id} role="alert" className="text-small text-error">
    {explanations[reason] ?? '입력을 확인해 주세요.'}
  </p>
}

export function formatReviewInterval(seconds: number): string {
  const day = 86400
  const [unit, label, carryAt, nextLabel] = seconds < 3600 ? [60, '분', 60, '시간'] as const
    : seconds < day ? [3600, '시간', 24, '일'] as const
      : seconds < 30 * day ? [day, '일', 30, '달'] as const
        : seconds < 365 * day ? [30 * day, '달', 12, '년'] as const
          : [365 * day, '년', null, null] as const
  const rounded = Math.max(1, Math.round(seconds / unit))
  return carryAt !== null && rounded >= carryAt ? `1${nextLabel}` : `${rounded}${label}`
}

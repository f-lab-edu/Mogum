export function formatReviewInterval(seconds: number): string {
  const day = 86400
  const [unit, label] = seconds < 3600 ? [60, '분'] as const
    : seconds < day ? [3600, '시간'] as const
      : seconds < 30 * day ? [day, '일'] as const
        : seconds < 365 * day ? [30 * day, '달'] as const
          : [365 * day, '년'] as const
  return `${Math.max(1, Math.round(seconds / unit))}${label}`
}

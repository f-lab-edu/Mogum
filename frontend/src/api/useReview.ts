import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client'
import { coursesKey } from './queryKeys'

export type Rating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'

export interface ReviewCard {
  id: number
  question: string
  answer: string
  nextIntervalsSeconds: Record<Rating, number>
}

export interface TodayReviewResponse {
  cards: ReviewCard[]
  remainingCount: number
  courseTitle?: string
}

export function useTodayReview(courseId: string | null) {
  return useQuery({
    queryKey: ['reviews', 'today', courseId],
    queryFn: ({ signal }) => apiFetch<TodayReviewResponse>(`/reviews/today${courseId === null ? '' : `?courseId=${encodeURIComponent(courseId)}`}`, { signal }),
    gcTime: 0,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })
}

type ReviewAction = { type: 'review'; cardId: number; rating: Rating } | { type: 'delete'; cardId: number }

export function useReviewAction() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (action: ReviewAction) => action.type === 'review'
      ? apiFetch<void>(`/cards/${action.cardId}/reviews`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rating: action.rating }) })
      : apiFetch<void>(`/cards/${action.cardId}`, { method: 'DELETE' }),
    retry: false,
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: coursesKey })
      await client.invalidateQueries({ queryKey: coursesKey, refetchType: 'none' })
    },
  })
}

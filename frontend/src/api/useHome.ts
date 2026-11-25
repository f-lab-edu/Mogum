import { useQuery } from '@tanstack/react-query'
import { apiFetch } from './client'
import type { JobResponse } from './useJob'

export interface HomeReviewsResponse {
  remainingCount: number
}

export interface HomeStreakResponse {
  days: number
  filledToday: boolean
}

export interface HomeContinueLearningResponse {
  course?: { id: number; title: string; nextLesson?: { id: number; title: string } }
}

export interface HomeTodosResponse {
  items: {
    type: 'COURSE_REVIEW' | 'ROADMAP_REVIEW' | 'ROADMAP_NEXT_COURSE' | (string & Record<never, never>)
    targetId: number
    title: string
  }[]
}

export interface HomeJobsResponse {
  jobs: JobResponse[]
}

function useHomeQuery<T>(section: string) {
  return useQuery({
    queryKey: ['home', section],
    queryFn: ({ signal }) => apiFetch<T>(`/home/${section}`, { signal }),
    gcTime: 0,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })
}

export function useHomeReviews() {
  return useHomeQuery<HomeReviewsResponse>('reviews')
}

export function useHomeStreak() {
  return useHomeQuery<HomeStreakResponse>('streak')
}

export function useHomeContinueLearning() {
  return useHomeQuery<HomeContinueLearningResponse>('continue-learning')
}

export function useHomeTodos() {
  return useHomeQuery<HomeTodosResponse>('todos')
}

export function useHomeJobs() {
  return useHomeQuery<HomeJobsResponse>('jobs')
}

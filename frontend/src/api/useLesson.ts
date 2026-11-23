import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client'
import { coursesKey } from './useCourse'
import type { JobResponse } from './useJob'

export interface LessonResponse {
  id: number
  courseId: number
  courseTitle: string
  position: number
  title: string
  completed: boolean
  material?: { body: string }
  job?: { id: number; status: JobResponse['status'] }
}

interface LessonCompletionResponse {
  courseId: number
  courseCompleted: boolean
}

export const lessonKey = (lessonId: string) => ['lessons', lessonId] as const

export function readLesson(lessonId: string, signal?: AbortSignal) {
  return apiFetch<LessonResponse>(`/lessons/${encodeURIComponent(lessonId)}`, { signal })
}

export function useLesson(lessonId: string) {
  return useQuery({
    queryKey: lessonKey(lessonId),
    queryFn: ({ signal }) => readLesson(lessonId, signal),
    enabled: lessonId.length > 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

export function useLessonCompletion(lessonId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<LessonCompletionResponse>(`/lessons/${encodeURIComponent(lessonId)}/complete`, { method: 'POST' }),
    retry: false,
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: lessonKey(lessonId), exact: true })
      await client.cancelQueries({ queryKey: coursesKey })
      client.removeQueries({ queryKey: lessonKey(lessonId), exact: true })
      await client.invalidateQueries({ queryKey: coursesKey, refetchType: 'none' })
    },
  })
}

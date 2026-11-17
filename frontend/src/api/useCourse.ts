import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client'

export interface CourseResponse {
  id: number
  status: 'NOT_PLANNED' | 'REVIEWING' | 'LEARNING' | 'COMPLETED' | (string & Record<never, never>)
  title: string
  roadmapId?: number
  lessons: {
    id: number
    position: number
    title: string
    description: string
    concepts: { name: string; definition: string }[]
    completed: boolean
  }[]
  progress: { completedLessons: number; totalLessons: number }
  nextLessonId?: number
  completedAt?: string
}

export const courseKey = (courseId: string) => ['courses', courseId] as const

export function readCourse(courseId: string, signal?: AbortSignal) {
  return apiFetch<CourseResponse>(`/courses/${encodeURIComponent(courseId)}`, { signal })
}

export function startCourse(courseId: string) {
  return apiFetch<CourseResponse>(`/courses/${encodeURIComponent(courseId)}/start`, { method: 'POST' })
}

export function updateCourseTitle(courseId: string, title: string) {
  return apiFetch<CourseResponse>(`/courses/${encodeURIComponent(courseId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
}

export function useCourse(courseId: string) {
  return useQuery({
    queryKey: courseKey(courseId),
    queryFn: ({ signal }) => readCourse(courseId, signal),
    enabled: courseId.length > 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

export function useCourseAction(courseId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationKey: courseKey(courseId),
    mutationFn: (action: { type: 'start' } | { type: 'title'; title: string }) => action.type === 'start'
      ? startCourse(courseId) : updateCourseTitle(courseId, action.title),
    retry: false,
    onSuccess: async (result) => {
      await client.cancelQueries({ queryKey: courseKey(courseId), exact: true })
      client.setQueryData(courseKey(courseId), result)
    },
  })
}

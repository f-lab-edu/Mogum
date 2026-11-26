import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client'
import { roadmapKey } from './useRoadmap'
import type { RoadmapResponse } from './useRoadmap'

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
  todayReviewCount: number
  masteryReport?: { shortened: number; revisited: number }
}

export const courseKey = (courseId: string) => ['courses', courseId] as const
export const coursesKey = ['courses'] as const

export interface CoursesResponse {
  courses: { id: number; title: string; status: CourseResponse['status'] }[]
}

export function readCourses(signal?: AbortSignal) {
  return apiFetch<CoursesResponse>('/courses', { signal })
}

export function useCourses() {
  return useQuery({
    queryKey: coursesKey,
    queryFn: ({ signal }) => readCourses(signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

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
      await client.cancelQueries({ queryKey: coursesKey, exact: true })
      if (result.roadmapId !== undefined) {
        await client.cancelQueries({ queryKey: roadmapKey(String(result.roadmapId)), exact: true })
        client.setQueryData<RoadmapResponse>(roadmapKey(String(result.roadmapId)), (previous) => previous && ({
          ...previous,
          courses: previous.courses.map((course) => course.id === result.id
            ? { ...course, title: result.title, status: result.status } : course),
        }))
      }
      client.setQueryData(courseKey(courseId), result)
      client.setQueryData<CoursesResponse>(coursesKey, (previous) => previous && ({
        ...previous,
        courses: previous.courses.map((course) => course.id === result.id
          ? { ...course, title: result.title, status: result.status } : course),
      }))
    },
  })
}

export function useCourseDeletion(courseId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<void>(`/courses/${encodeURIComponent(courseId)}`, { method: 'DELETE' }),
    retry: false,
    onSuccess: async () => {
      await client.cancelQueries({ queryKey: courseKey(courseId), exact: true })
      await client.cancelQueries({ queryKey: coursesKey, exact: true })
      await client.cancelQueries({ queryKey: ['home'] })
      client.removeQueries({ queryKey: courseKey(courseId), exact: true })
      await client.invalidateQueries({ queryKey: coursesKey, exact: true, refetchType: 'none' })
      await client.invalidateQueries({ queryKey: ['home'], refetchType: 'none' })
    },
  })
}

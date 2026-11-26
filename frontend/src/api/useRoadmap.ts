import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client'
import { courseKey, coursesKey } from './useCourse'
import type { CourseResponse } from './useCourse'

type RoadmapStatus = 'REVIEWING' | 'IN_PROGRESS' | 'COMPLETED' | (string & Record<never, never>)

export interface RoadmapResponse {
  id: number
  title: string
  description: string
  status: RoadmapStatus
  courses: {
    id: number
    position: number
    title: string
    description: string
    concepts: { name: string; definition: string }[]
    status: CourseResponse['status']
    progress: 'NOT_PLANNED' | 'GENERATING' | 'FAILED' | 'GENERATED' | (string & Record<never, never>)
    jobId?: number
  }[]
  nextCourse?: { courseId: number; enabled: boolean }
}

export interface RoadmapsResponse {
  roadmaps: {
    id: number
    title: string
    description: string
    status: RoadmapStatus
    completedCourses: number
    totalCourses: number
  }[]
}

export const roadmapKey = (roadmapId: string) => ['roadmaps', roadmapId] as const
export const roadmapsKey = ['roadmaps'] as const

export function readRoadmap(roadmapId: string, signal?: AbortSignal) {
  return apiFetch<RoadmapResponse>(`/roadmaps/${encodeURIComponent(roadmapId)}`, { signal })
}

export function readRoadmaps(signal?: AbortSignal) {
  return apiFetch<RoadmapsResponse>('/roadmaps', { signal })
}

export function useRoadmap(roadmapId: string) {
  return useQuery({
    queryKey: roadmapKey(roadmapId),
    queryFn: ({ signal }) => readRoadmap(roadmapId, signal),
    enabled: roadmapId.length > 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

export function useRoadmaps() {
  return useQuery({
    queryKey: roadmapsKey,
    queryFn: ({ signal }) => readRoadmaps(signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

export function useRoadmapDeletion(roadmapId: string) {
  const client = useQueryClient()
  return useMutation<void, Error, number[]>({
    mutationFn: () => apiFetch<void>(`/roadmaps/${encodeURIComponent(roadmapId)}`, { method: 'DELETE' }),
    retry: false,
    onSuccess: async (_result, courseIds) => {
      await client.cancelQueries({ queryKey: roadmapKey(roadmapId), exact: true })
      await client.cancelQueries({ queryKey: roadmapsKey, exact: true })
      await client.cancelQueries({ queryKey: coursesKey, exact: true })
      await client.cancelQueries({ queryKey: ['home'] })
      for (const courseId of courseIds) {
        await client.cancelQueries({ queryKey: courseKey(String(courseId)), exact: true })
      }
      client.removeQueries({ queryKey: roadmapKey(roadmapId), exact: true })
      for (const courseId of courseIds) {
        client.removeQueries({ queryKey: courseKey(String(courseId)), exact: true })
      }
      await client.invalidateQueries({ queryKey: roadmapsKey, exact: true, refetchType: 'none' })
      await client.invalidateQueries({ queryKey: coursesKey, exact: true, refetchType: 'none' })
      await client.invalidateQueries({ queryKey: ['home'], refetchType: 'none' })
    },
  })
}

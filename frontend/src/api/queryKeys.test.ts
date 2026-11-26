import { expect, test } from 'vitest'
import { courseKey, coursesKey, roadmapKey, roadmapsKey } from './queryKeys'

test('코스 상세 키는 코스 ID를 그대로 담는다', () => {
  expect(courseKey('31/a')).toEqual(['courses', '31/a'])
})

test('코스 목록 키는 courses 한 칸이다', () => {
  expect(coursesKey).toEqual(['courses'])
})

test('로드맵 상세 키는 로드맵 ID를 그대로 담는다', () => {
  expect(roadmapKey('41/a')).toEqual(['roadmaps', '41/a'])
})

test('로드맵 목록 키는 roadmaps 한 칸이다', () => {
  expect(roadmapsKey).toEqual(['roadmaps'])
})

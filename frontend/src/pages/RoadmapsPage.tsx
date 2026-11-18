import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useRoadmaps } from '../api/useRoadmap'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'

export default function RoadmapsPage() {
  const roadmaps = useRoadmaps()
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    document.title = '로드맵 · 머금'
    heading.current?.focus()
  }, [])

  return <div className="space-y-6">
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">로드맵</h1>
    {roadmaps.isPending ? <p role="status" className="text-text-secondary">로드맵 목록을 불러오고 있어요.</p>
      : roadmaps.isError || !roadmaps.data ? <FailureNotice title="로드맵 목록을 불러오지 못했어요." message={roadmaps.error instanceof ApiError ? roadmaps.error.message : CONNECTION_ERROR_MESSAGE}>
        <Button disabled={roadmaps.isFetching} onClick={() => void roadmaps.refetch()}>다시 시도</Button>
      </FailureNotice>
        : roadmaps.data.roadmaps.length === 0 ? <p className="text-text-secondary">아직 만든 로드맵이 없어요</p>
          : <ul aria-label="로드맵 목록" className="space-y-4">
            {roadmaps.data.roadmaps.map((roadmap) => <li key={roadmap.id}>
              <Link to={`/roadmaps/${roadmap.id}`} className="block space-y-3 rounded-panel border border-border bg-surface p-6 hover:border-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
                <h2 className="font-bold">{roadmap.title}</h2>
                <p className="text-text-secondary">{roadmap.description}</p>
                <p className="text-small font-bold text-primary">{roadmap.status === 'COMPLETED' ? '다 마침' : `코스 ${roadmap.totalCourses}개 중 ${roadmap.completedCourses}개 마침`}</p>
              </Link>
            </li>)}
          </ul>}
  </div>
}

import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useCourseAction } from '../api/useCourse'
import { receiveJob } from '../api/useJob'
import type { PlanRoadmapCourseRequest, ReplanRoadmapRequest } from '../api/useJob'
import { useRoadmap, useRoadmapDeletion } from '../api/useRoadmap'
import Button from '../components/Button'
import CreationBlockedNotice, { isCreationBlockedError } from '../components/CreationBlockedNotice'
import FailureNotice from '../components/FailureNotice'
import InlineConfirmation from '../components/InlineConfirmation'
import Mogumi from '../components/Mogumi'
import ProgressBar from '../components/ProgressBar'
import StatusBadge from '../components/StatusBadge'
import TextField from '../components/TextField'

const statuses = new Map([['REVIEWING', '검토 중'], ['IN_PROGRESS', '진행 중'], ['COMPLETED', '다 마침']])
const progressLabels = new Map([['NOT_PLANNED', '짜기 전'], ['GENERATING', '만드는 중'], ['FAILED', '실패'], ['GENERATED', '만들어짐']])
const courseStatuses = new Map([['REVIEWING', '검토 중'], ['LEARNING', '학습 중'], ['COMPLETED', '다 마침']])
const linkClass = 'inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary'

function message(error: unknown) {
  return error instanceof ApiError ? error.message : CONNECTION_ERROR_MESSAGE
}

function fieldError(error: unknown, field: string) {
  return error instanceof ApiError && error.code === 'FIELD_INVALID'
    ? error.fields?.find((violation) => violation.field === field)?.reason : undefined
}

export default function RoadmapPage() {
  const { roadmapId = '' } = useParams()
  const navigate = useNavigate()
  const roadmap = useRoadmap(roadmapId)
  const deletion = useRoadmapDeletion(roadmapId)
  const [confirming, setConfirming] = useState(false)
  const [editing, setEditing] = useState<{ courseId: number; title: string } | null>(null)
  const titleAction = useCourseAction(editing ? String(editing.courseId) : '')
  const titleForm = useRef<HTMLFormElement>(null)
  const titleButton = useRef<HTMLButtonElement>(null)
  const [replanOpen, setReplanOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const replanForm = useRef<HTMLFormElement>(null)
  const replanButton = useRef<HTMLButtonElement>(null)
  const submitting = useRef(false)
  const jobAction = useMutation({
    mutationFn: (request: PlanRoadmapCourseRequest | ReplanRoadmapRequest) => receiveJob(request),
    retry: false,
    onSuccess: (job) => { void navigate(`/jobs/${job.id}`) },
    onSettled: () => { submitting.current = false },
  })
  const busy = titleAction.isPending || jobAction.isPending || deletion.isPending
  const data = roadmap.data
  const status = data && statuses.get(data.status)
  const editingId = editing?.courseId

  useEffect(() => {
    if (editingId !== undefined) titleForm.current?.querySelector('input')?.focus()
  }, [editingId])
  useEffect(() => {
    if (replanOpen) replanForm.current?.querySelector('textarea')?.focus()
  }, [replanOpen])

  const closeTitle = () => {
    setEditing(null)
    titleButton.current?.focus()
  }
  const sendJob = (request: PlanRoadmapCourseRequest | ReplanRoadmapRequest) => {
    if (submitting.current) return
    submitting.current = true
    jobAction.mutate(request)
  }

  if (roadmap.isPending && roadmapId) return <p role="status" className="text-text-secondary">로드맵을 불러오고 있어요.</p>
  if (roadmap.isError || !data || !status) return <FailureNotice title="로드맵을 불러오지 못했어요." message={roadmap.isError ? message(roadmap.error) : '다시 불러와 주세요.'}>
    <div className="flex flex-wrap items-center gap-4">
      <Button disabled={roadmap.isFetching} onClick={() => void roadmap.refetch()}>다시 시도</Button>
      <Link to="/" className={linkClass}>홈으로</Link>
    </div>
  </FailureNotice>

  const reviewing = data.status === 'REVIEWING'
  const completed = data.status === 'COMPLETED'
  const firstCourse = data.courses.find((course) => course.position === 1)
  const titleValid = editing !== null && editing.title.trim().length > 0 && Array.from(editing.title).length <= 100

  return <div className="space-y-6">
    <section aria-label="로드맵 정보" className="space-y-6 rounded-panel border border-border bg-surface p-6 sm:p-8">
      <div className="flex flex-col gap-6 sm:flex-row sm:items-start">
        <div className="min-w-0 flex-1 space-y-4">
          <StatusBadge>{status}</StatusBadge>
          <h2 className="text-title font-extrabold">{data.title}</h2>
          <p className="text-text-secondary">{data.description}</p>
          {completed && <p>이 로드맵의 모든 코스를 다 마쳤어요.</p>}
        </div>
        <Mogumi />
      </div>
      {!reviewing && <ProgressBar label="마친 코스" completed={data.courses.filter((course) => course.status === 'COMPLETED').length} total={data.courses.length} />}
    </section>

    {reviewing && <section aria-label="로드맵 검토" className="space-y-4 rounded-panel border border-border bg-surface p-6 sm:p-8">
      <div className="flex flex-col gap-3 sm:flex-row sm:justify-end">
        <Button ref={replanButton} variant="secondary" disabled={busy} aria-expanded={replanOpen} aria-controls={replanOpen ? 'roadmap-replan' : undefined} onClick={() => {
          setReplanOpen(!replanOpen)
          jobAction.reset()
        }}>다시 짜기</Button>
        <Button disabled={busy || !firstCourse} onClick={() => {
          if (firstCourse) sendJob({ type: 'PLAN_ROADMAP_COURSE', courseId: firstCourse.id })
        }}>이대로 시작</Button>
      </div>
      {replanOpen && <form id="roadmap-replan" ref={replanForm} noValidate className="space-y-4 rounded-control bg-surface-soft p-4" onSubmit={(event) => {
        event.preventDefault()
        if (Array.from(feedback).length <= 500) sendJob({ type: 'REPLAN_ROADMAP', roadmapId: data.id, ...(feedback.trim() ? { feedback } : {}) })
      }}>
        <p className="text-small text-text-secondary">로드맵 계획에서 바꾸고 싶은 점을 알려 주세요.</p>
        <TextField label="고칠 점" value={feedback} onChange={(value) => { setFeedback(value); jobAction.reset() }} multiline maxCodePoints={500} disabled={busy} error={fieldError(jobAction.error, 'feedback')} />
        <div className="flex flex-wrap gap-3">
          <Button type="submit" disabled={busy || Array.from(feedback).length > 500}>다시 짜기 요청</Button>
          <Button variant="secondary" disabled={busy} onClick={() => { setReplanOpen(false); replanButton.current?.focus() }}>다시 짜기 취소</Button>
        </div>
      </form>}
    </section>}

    {jobAction.isError && (isCreationBlockedError(jobAction.error) ? <CreationBlockedNotice error={jobAction.error} /> : <FailureNotice title={jobAction.variables?.type === 'REPLAN_ROADMAP' ? '다시 짜기를 요청하지 못했어요.' : '코스 만들기를 요청하지 못했어요.'} message={message(jobAction.error)}>
      <p className="text-small">안내를 확인한 뒤 같은 버튼을 다시 눌러 주세요.</p>
    </FailureNotice>)}
    {titleAction.isError && <FailureNotice title="제목을 바꾸지 못했어요." message={message(titleAction.error)}>
      <p className="text-small">안내를 확인한 뒤 제목 저장을 다시 눌러 주세요.</p>
    </FailureNotice>}

    <section aria-labelledby="roadmap-plan-heading" className="space-y-4">
      <h2 id="roadmap-plan-heading" className="font-extrabold">로드맵 계획</h2>
      <ol aria-label="로드맵 계획" className="space-y-4 border-l-2 border-border pl-4 sm:pl-6">
        {data.courses.map((course) => {
          const courseStatus = courseStatuses.get(course.status)
          const next = !reviewing && !completed && data.nextCourse?.courseId === course.id ? data.nextCourse : undefined
          const href = course.progress === 'GENERATED' ? `/courses/${course.id}`
            : (course.progress === 'GENERATING' || course.progress === 'FAILED') && course.jobId !== undefined ? `/jobs/${course.jobId}` : undefined
          return <li key={course.id} className={`space-y-4 rounded-panel border bg-surface p-6 sm:p-8 ${next || course.status === 'LEARNING' ? 'border-primary' : 'border-border'}`}>
            {!reviewing && <div className="flex flex-wrap items-center gap-3">
              {progressLabels.has(course.progress) && <StatusBadge>{progressLabels.get(course.progress)}</StatusBadge>}
              {course.progress === 'GENERATED' && courseStatus && <span className="text-small font-bold text-primary">{courseStatus}</span>}
            </div>}
            <h3 className="font-bold">{course.position}코스 · {course.title}</h3>
            <p className="text-text-secondary">{course.description}</p>
            <dl className="flex flex-wrap gap-3">
              {course.concepts.map((concept, index) => <div key={index} className="rounded-control bg-surface-soft px-3 py-2 text-small">
                <dt className="font-bold text-primary">{concept.name}</dt>
                <dd className="text-text-secondary">{concept.definition}</dd>
              </div>)}
            </dl>
            {reviewing && <>
              <Button variant="secondary" disabled={busy} onClick={(event) => {
                if (editingId === course.id) return
                titleButton.current = event.currentTarget
                titleAction.reset()
                setEditing({ courseId: course.id, title: course.title })
              }}>코스 제목 고치기</Button>
              {editingId === course.id && editing && <form ref={titleForm} noValidate className="space-y-4" onSubmit={(event) => {
                event.preventDefault()
                if (!titleValid || submitting.current) return
                submitting.current = true
                titleAction.mutate({ type: 'title', title: editing.title }, {
                  onSuccess: closeTitle,
                  onSettled: () => { submitting.current = false },
                })
              }}>
                <p className="text-small text-text-secondary">보이는 이름만 바뀌어요. 계획의 내용은 그대로예요.</p>
                <TextField label="코스 제목" value={editing.title} onChange={(title) => { setEditing({ ...editing, title }); titleAction.reset() }} required maxCodePoints={100} disabled={busy} error={fieldError(titleAction.error, 'title')} />
                <div className="flex flex-wrap gap-3">
                  <Button type="submit" disabled={busy || !titleValid}>제목 저장</Button>
                  <Button variant="secondary" disabled={busy} onClick={closeTitle}>제목 수정 취소</Button>
                </div>
              </form>}
            </>}
            {!reviewing && href && <Link to={href} className={linkClass}>{course.progress === 'GENERATED' ? '코스 열기' : '기다리는 화면으로 가기'}</Link>}
            {next && <div className="space-y-3">
              {!next.enabled && <p id="next-course-locked" className="text-small text-text-secondary">앞 코스를 다 마치면 만들 수 있어요</p>}
              <Button disabled={busy || !next.enabled} aria-describedby={!next.enabled ? 'next-course-locked' : undefined} onClick={() => sendJob({ type: 'PLAN_ROADMAP_COURSE', courseId: next.courseId })}>다음 코스 만들기</Button>
            </div>}
          </li>
        })}
      </ol>
    </section>

    <InlineConfirmation open={confirming} onOpenChange={setConfirming} disabled={busy}
      triggerLabel="로드맵 지우기" title="로드맵을 지울까요?" confirmLabel="지우기"
      message={reviewing ? '로드맵 계획이 사라져요. 되돌릴 수 없어요.' : '로드맵과 그 안의 코스 · 강의가 모두 사라지고, 카드는 복습에 나오지 않아요. 그동안의 카드와 복습 기록은 다음 강의를 내 수준에 맞추는 데 계속 쓰여요.'}
      onConfirm={() => {
        if (submitting.current) return
        submitting.current = true
        deletion.mutate(data.courses.map((course) => course.id), {
          onSuccess: () => { void navigate('/roadmaps', { replace: true }) },
          onSettled: () => { submitting.current = false },
        })
      }} />
    {deletion.isError && <FailureNotice title="로드맵을 지우지 못했어요." message={message(deletion.error)}>
      <p className="text-small">안내를 확인한 뒤 지우기를 다시 눌러 주세요.</p>
    </FailureNotice>}
  </div>
}

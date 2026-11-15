import { Link, Navigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useJob, useJobAction } from '../api/useJob'
import type { JobResponse } from '../api/useJob'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'
import Mogumi from '../components/Mogumi'

const kinds = new Map([
  ['NEW_COURSE', '강의 목록'],
  ['PLAN_ROADMAP_COURSE', '강의 목록'],
  ['REPLAN_COURSE', '강의 목록'],
  ['NEW_ROADMAP', '로드맵 계획'],
  ['REPLAN_ROADMAP', '로드맵 계획'],
  ['MATERIAL', '강의 자료'],
])
const levels = new Map([['BEGINNER', '처음 배워요'], ['BASICS', '기초는 알아요'], ['FAMILIAR', '익숙해요']])
const purposes = new Map([['WORK', '업무에 쓰려고요'], ['INTERVIEW', '면접 준비'], ['GENERAL', '교양 · 관심']])
const connectionMessage = '연결하지 못했어요. 다시 시도해 주세요.'

function errorMessage(error: unknown) {
  return error instanceof ApiError && error.status >= 400 ? error.message : connectionMessage
}

function resultPath(result: JobResponse['result']) {
  if (!result) return undefined
  switch (result.type) {
    case 'COURSE': return `/courses/${result.id}`
    case 'ROADMAP': return `/roadmaps/${result.id}`
    case 'LESSON': return result.courseId === undefined ? undefined : `/courses/${result.courseId}/lessons/${result.id}`
    default: return undefined
  }
}

function HomeLink() {
  return <Link to="/" className="inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">홈으로</Link>
}

export default function WaitingPage() {
  const { jobId = '' } = useParams()
  const job = useJob(jobId)
  const action = useJobAction(jobId)
  const data = job.data
  const kind = data && kinds.get(data.type)
  const path = data?.status === 'SUCCEEDED' ? resultPath(data.result) : undefined

  if (action.isSuccess && action.variables === 'discard') return <Navigate to="/" replace />
  if (path) return <Navigate to={path} replace />

  if (job.isPending && jobId) return <p role="status" className="text-text-secondary">작업을 불러오고 있어요.</p>

  if (job.isError || !data || !kind || !['QUEUED', 'RUNNING', 'FAILED'].includes(data.status)) {
    return <FailureNotice title="작업을 불러오지 못했어요." message={job.isError ? errorMessage(job.error) : '다시 불러와 주세요.'}>
      <div className="flex flex-wrap items-center gap-4">
        <Button disabled={job.isFetching} onClick={() => void job.refetch()}>다시 시도</Button>
        <HomeLink />
      </div>
    </FailureNotice>
  }

  const subject = data.subject
  const newLearning = data.type === 'NEW_COURSE' || data.type === 'NEW_ROADMAP'
  const level = subject.level === 'OTHER' ? subject.levelOther : levels.get(subject.level ?? '')
  const purpose = subject.purpose === 'OTHER' ? subject.purposeOther : purposes.get(subject.purpose ?? '')
  const failed = data.status === 'FAILED'
  const canRetry = data.error?.code === 'JOB_TEMPORARY_FAILURE'
  const canDiscard = canRetry || data.error?.code === 'JOB_INPUT_FAILURE'
  const submit = (next: 'retry' | 'discard') => {
    if (!action.isPending) action.mutate(next)
  }

  return (
    <div className="mx-auto max-w-account space-y-6">
      <div className="rounded-panel border border-border bg-surface p-6 text-center sm:p-8">
        <Mogumi />
        <h2 className="mt-6 text-title font-extrabold text-balance">{newLearning ? subject.topic : subject.title}</h2>
        {newLearning && <dl className="mt-4 flex flex-wrap justify-center gap-3 text-small text-text-secondary">
          {level && <div><dt className="sr-only">수준</dt><dd>{level}</dd></div>}
          {purpose && <div><dt className="sr-only">목적</dt><dd>{purpose}</dd></div>}
        </dl>}
        {!failed && <p role="status" className="mt-6 font-bold text-primary">
          {data.status === 'QUEUED' ? '차례를 기다리고 있어요'
            : data.type === 'MATERIAL' ? '강의 자료를 만들고 있어요' : `${kind}을 짜고 있어요`}
        </p>}
        {failed && <div className="mt-6 text-left">
          <FailureNotice title={`${kind}${data.type === 'MATERIAL' ? '를' : '을'} 만들지 못했어요.`} message={data.error?.message ?? '다시 불러와 주세요.'}>
            <div className="flex flex-wrap gap-4">
              {canRetry && <Button disabled={action.isPending} onClick={() => submit('retry')}>다시 시도</Button>}
              {canDiscard && <Button variant="secondary" disabled={action.isPending} onClick={() => submit('discard')}>버리기</Button>}
              {!canDiscard && <HomeLink />}
            </div>
          </FailureNotice>
        </div>}
      </div>
      {!failed && <p className="rounded-control bg-surface-soft p-4 text-small text-text-secondary">
        이 화면을 떠나도 계속 만들어요. 홈에서 다시 열 수 있어요.
      </p>}
      {action.isError && <FailureNotice title={action.variables === 'retry' ? '다시 시도하지 못했어요.' : '버리지 못했어요.'} message={errorMessage(action.error)} />}
    </div>
  )
}

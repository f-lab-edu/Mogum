import { useMutation } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { receiveJob } from '../api/useJob'
import type { NewLearningRequest } from '../api/useJob'
import Button from '../components/Button'
import CheckboxField from '../components/CheckboxField'
import ChoiceGroup from '../components/ChoiceGroup'
import CreationBlockedNotice, { isCreationBlockedError } from '../components/CreationBlockedNotice'
import FailureNotice from '../components/FailureNotice'
import Mogumi from '../components/Mogumi'
import TextField from '../components/TextField'

const levels = [
  { value: 'BEGINNER', label: '처음 배워요' },
  { value: 'BASICS', label: '기초는 알아요' },
  { value: 'FAMILIAR', label: '익숙해요' },
  { value: 'OTHER', label: '기타' },
] as const
const purposes = [
  { value: 'WORK', label: '업무에 쓰려고요' },
  { value: 'INTERVIEW', label: '면접 준비' },
  { value: 'GENERAL', label: '교양 · 관심' },
  { value: 'OTHER', label: '기타' },
] as const

function validText(value: string, max: number, required = true) {
  return (!required || value.trim().length > 0) && Array.from(value).length <= max
}

export default function NewLearningPage() {
  const navigate = useNavigate()
  const submitting = useRef(false)
  const [topic, setTopic] = useState('')
  const [level, setLevel] = useState<NewLearningRequest['level'] | ''>('')
  const [levelOther, setLevelOther] = useState('')
  const [purpose, setPurpose] = useState<NewLearningRequest['purpose'] | ''>('')
  const [purposeOther, setPurposeOther] = useState('')
  const [memo, setMemo] = useState('')
  const [roadmap, setRoadmap] = useState(false)
  const mutation = useMutation({
    mutationFn: receiveJob,
    retry: false,
    onSuccess: (job) => { void navigate(`/jobs/${job.id}`) },
    onSettled: () => { submitting.current = false },
  })
  const fields = mutation.error instanceof ApiError && mutation.error.code === 'FIELD_INVALID' ? mutation.error.fields : undefined
  const error = (field: string) => fields?.find((violation) => violation.field === field)?.reason
  const valid = validText(topic, 100) && level !== '' && purpose !== ''
    && (level !== 'OTHER' || validText(levelOther, 50))
    && (purpose !== 'OTHER' || validText(purposeOther, 50)) && validText(memo, 500, false)

  return <div className="space-y-6">
    <div className="flex flex-col items-center gap-4 rounded-panel bg-surface-soft p-6 sm:flex-row sm:p-8">
      <p className="flex-1 text-text-secondary">배우고 싶은 주제와 지금 수준, 목적을 알려 주세요. 나에게 맞는 강의 목록을 만들어요.</p>
      <Mogumi />
    </div>
    <form noValidate className="space-y-8 rounded-panel border border-border bg-surface p-6 sm:p-8" onSubmit={(event) => {
      event.preventDefault()
      if (!valid || !level || !purpose || submitting.current) return
      submitting.current = true
      mutation.mutate({
        type: roadmap ? 'NEW_ROADMAP' : 'NEW_COURSE', topic, level, purpose,
        ...(level === 'OTHER' ? { levelOther } : {}),
        ...(purpose === 'OTHER' ? { purposeOther } : {}),
        ...(memo.trim() ? { memo } : {}),
      })
    }}>
      <TextField label="주제" value={topic} onChange={setTopic} required maxCodePoints={100} error={error('topic')} disabled={mutation.isPending} />
      <div className="space-y-4">
        <ChoiceGroup label="지금 수준" options={levels} value={level} onChange={setLevel} required error={error('level')} disabled={mutation.isPending} />
        {level === 'OTHER' && <TextField label="기타 수준" value={levelOther} onChange={setLevelOther} required maxCodePoints={50} error={error('levelOther')} disabled={mutation.isPending} />}
      </div>
      <div className="space-y-4">
        <ChoiceGroup label="목적" options={purposes} value={purpose} onChange={setPurpose} required error={error('purpose')} disabled={mutation.isPending} />
        {purpose === 'OTHER' && <TextField label="기타 목적" value={purposeOther} onChange={setPurposeOther} required maxCodePoints={50} error={error('purposeOther')} disabled={mutation.isPending} />}
      </div>
      <TextField label="메모" value={memo} onChange={setMemo} multiline maxCodePoints={500} error={error('memo')} disabled={mutation.isPending} />
      <CheckboxField label="로드맵으로 만들기" checked={roadmap} onChange={setRoadmap} disabled={mutation.isPending} description={<>
        <p>넓은 주제를 여러 코스로 나눠 차례로 배워요. 한 코스를 마치면 그때까지의 복습 결과를 반영해 다음 코스를 만들어요.</p>
        <p>이럴 때 좋아요 — 한 번에 배우기 어려운 넓은 주제(예: Spring 백엔드 · 컴퓨터 네트워크) · 기초부터 실무까지 길게 배우고 싶을 때</p>
        <p>체크하지 않으면 한 주제를 강의 목록 하나로 만들어요(예: JPA 연관관계 · 트랜잭션 격리 수준).</p>
      </>} />
      {mutation.isError && (isCreationBlockedError(mutation.error) ? <CreationBlockedNotice error={mutation.error} /> : <FailureNotice title="만들기를 요청하지 못했어요." message={mutation.error instanceof ApiError ? mutation.error.message : CONNECTION_ERROR_MESSAGE}>
        <p className="text-small">안내를 확인한 뒤 만들기를 다시 눌러 주세요.</p>
      </FailureNotice>)}
      <Button type="submit" disabled={!valid || mutation.isPending} className="w-full">만들기</Button>
    </form>
  </div>
}

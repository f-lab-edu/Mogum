import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useCourse } from '../api/useCourse'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'
import Mogumi from '../components/Mogumi'

export default function CelebrationPage() {
  const { courseId = '' } = useParams()
  const navigate = useNavigate()
  const course = useCourse(courseId)
  const home = <Button onClick={() => void navigate('/')}>홈으로</Button>

  if (course.isPending) return <p role="status" className="text-text-secondary">코스를 불러오고 있어요.</p>
  if (course.isError || !course.data) return <FailureNotice title="코스를 불러오지 못했어요." message={course.error instanceof ApiError ? course.error.message : CONNECTION_ERROR_MESSAGE}>{home}</FailureNotice>

  return <div className="mx-auto max-w-account space-y-6 rounded-panel border border-border bg-surface p-6 text-center sm:p-8">
    <Mogumi />
    <h2 className="text-title font-extrabold text-balance">{course.data.title} 코스를 마쳤어요. 고생했어요!</h2>
    {home}
  </div>
}

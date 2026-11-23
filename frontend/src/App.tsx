import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { createAuthQueryClient } from './api/queryClient'
import RequireAuth from './components/RequireAuth'
import AppLayout from './components/AppLayout'
import { redirectToLogin } from './lib/authNavigation'
import LoginPage from './pages/LoginPage'
import AccountPage from './pages/AccountPage'
import PrivacyPage from './pages/PrivacyPage'
import PlaceholderPage from './pages/PlaceholderPage'
import WaitingPage from './pages/WaitingPage'
import NewLearningPage from './pages/NewLearningPage'
import CoursePage from './pages/CoursePage'
import RoadmapPage from './pages/RoadmapPage'
import CoursesPage from './pages/CoursesPage'
import RoadmapsPage from './pages/RoadmapsPage'
import LessonPage from './pages/LessonPage'
import CelebrationPage from './pages/CelebrationPage'

function ApplicationRoutes() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [queryClient] = useState(() => createAuthQueryClient(() => redirectToLogin(navigate)))

  return (
    <QueryClientProvider client={queryClient}>
      <Routes>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<AccountPage />} />
          <Route path="/apply" element={<AccountPage />} />
          <Route path="/waiting-approval" element={<AccountPage />} />
          <Route path="/rejected" element={<AccountPage />} />
          <Route path="/courses" element={<AppLayout><CoursesPage /></AppLayout>} />
          <Route path="/roadmaps" element={<AppLayout><RoadmapsPage /></AppLayout>} />
          <Route path="/review" element={<AppLayout><PlaceholderPage title="복습" /></AppLayout>} />
          <Route path="/profile" element={<AppLayout><PlaceholderPage title="내 정보" /></AppLayout>} />
          <Route path="/new" element={<AppLayout title="새로 배우기"><NewLearningPage /></AppLayout>} />
          <Route path="/jobs/:jobId" element={<AppLayout title="기다리는 화면"><WaitingPage key={pathname} /></AppLayout>} />
          <Route path="/courses/:courseId" element={<AppLayout title="코스"><CoursePage key={pathname} /></AppLayout>} />
          <Route path="/roadmaps/:roadmapId" element={<AppLayout title="로드맵"><RoadmapPage key={pathname} /></AppLayout>} />
          <Route path="/courses/:courseId/done" element={<AppLayout title="축하 화면"><CelebrationPage key={pathname} /></AppLayout>} />
          <Route path="/courses/:courseId/lessons/:lessonId" element={<AppLayout title="강의"><LessonPage key={pathname} /></AppLayout>} />
        </Route>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/privacy" element={<PrivacyPage />} />
      </Routes>
    </QueryClientProvider>
  )
}

export default function App() {
  return <BrowserRouter><ApplicationRoutes /></BrowserRouter>
}

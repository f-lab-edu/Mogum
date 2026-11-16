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
          <Route path="/courses" element={<AppLayout><PlaceholderPage title="코스" /></AppLayout>} />
          <Route path="/roadmaps" element={<AppLayout><PlaceholderPage title="로드맵" /></AppLayout>} />
          <Route path="/review" element={<AppLayout><PlaceholderPage title="복습" /></AppLayout>} />
          <Route path="/profile" element={<AppLayout><PlaceholderPage title="내 정보" /></AppLayout>} />
          <Route path="/jobs/:jobId" element={<AppLayout title="기다리는 화면"><WaitingPage key={pathname} /></AppLayout>} />
          <Route path="/courses/:courseId" element={<AppLayout title="코스">{null}</AppLayout>} />
          <Route path="/roadmaps/:roadmapId" element={<AppLayout title="로드맵">{null}</AppLayout>} />
          <Route path="/courses/:courseId/lessons/:lessonId" element={<AppLayout title="강의">{null}</AppLayout>} />
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

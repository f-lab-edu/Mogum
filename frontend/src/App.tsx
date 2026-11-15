import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { createAuthQueryClient } from './api/queryClient'
import RequireAuth from './components/RequireAuth'
import AppLayout from './components/AppLayout'
import { redirectToLogin } from './lib/authNavigation'
import LoginPage from './pages/LoginPage'
import AccountPage from './pages/AccountPage'
import PrivacyPage from './pages/PrivacyPage'
import PlaceholderPage from './pages/PlaceholderPage'

function ApplicationRoutes() {
  const navigate = useNavigate()
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

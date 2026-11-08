import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { createAuthQueryClient } from './api/queryClient'
import RequireAuth from './components/RequireAuth'
import { redirectToLogin } from './lib/authNavigation'
import LoginPage from './pages/LoginPage'
import AccountPage from './pages/AccountPage'
import PrivacyPage from './pages/PrivacyPage'

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

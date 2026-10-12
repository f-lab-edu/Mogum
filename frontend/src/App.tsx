import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { createAuthQueryClient } from './api/queryClient'
import RequireAuth from './components/RequireAuth'
import { redirectToLogin } from './lib/authNavigation'
import LoginPage from './pages/LoginPage'
import PlaceholderPage from './pages/PlaceholderPage'

function ApplicationRoutes() {
  const navigate = useNavigate()
  const [queryClient] = useState(() => createAuthQueryClient(() => redirectToLogin(navigate)))

  return (
    <QueryClientProvider client={queryClient}>
      <Routes>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<PlaceholderPage />} />
        </Route>
        <Route path="/login" element={<LoginPage />} />
      </Routes>
    </QueryClientProvider>
  )
}

export default function App() {
  return <BrowserRouter><ApplicationRoutes /></BrowserRouter>
}

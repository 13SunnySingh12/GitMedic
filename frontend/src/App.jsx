import { Suspense, lazy } from 'react';
import { Route, Routes } from 'react-router';
import { MotionConfig } from 'motion/react';
import { AppShell, NotFoundPage } from './components/Layout.jsx';
import IssueDetail from './pages/IssueDetail.jsx';
import Issues from './pages/Issues.jsx';
import Login from './pages/Login.jsx';
import Pulls from './pages/Pulls.jsx';
import RepoList from './pages/RepoList.jsx';
import { ReportDetail, Reports } from './pages/Reports.jsx';

// The dashboard is the heaviest page; load it only when a dashboard is opened.
const Dashboard = lazy(() => import('./pages/Dashboard.jsx'));

export default function App() {
  return (
    // Users who ask for reduced motion get no transform or layout animation.
    <MotionConfig reducedMotion="user" transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<AppShell />}>
          <Route index element={<RepoList />} />
          <Route path="/repos" element={<RepoList />} />
          <Route
            path="/repos/:repoId"
            element={
              <Suspense fallback={<span className="skeleton" style={{ minHeight: 320 }} />}>
                <Dashboard />
              </Suspense>
            }
          />
          <Route path="/repos/:repoId/issues" element={<Issues />} />
          <Route path="/repos/:repoId/issues/:number" element={<IssueDetail />} />
          <Route path="/repos/:repoId/pulls" element={<Pulls />} />
          <Route path="/repos/:repoId/reports" element={<Reports />} />
          <Route path="/repos/:repoId/reports/:reportId" element={<ReportDetail />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </MotionConfig>
  );
}

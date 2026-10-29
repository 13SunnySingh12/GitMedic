import { Component, useEffect } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useMatch, useNavigate } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { motion } from 'motion/react';
import { useLogout, useMe } from '../api/hooks.js';
import { api, safeNext } from '../api/client.js';
import { IconIssues, IconPull, IconReport, IconRepos, IconSignOut, IconTrend } from './Icons.jsx';

export const NEXT_KEY = 'gitmedic.next';

export function Brand() {
  return (
    <>
      <span className="brand-mark" aria-hidden="true">
        <svg width="16" height="16" viewBox="0 0 16 16">
          <path d="M6 2h4v4h4v4h-4v4H6v-4H2V6h4z" fill="currentColor" />
        </svg>
      </span>
      GitMedic
    </>
  );
}

function NavItem({ to, end, icon, children }) {
  return (
    <li>
      <NavLink to={to} end={end} className="nav-link">
        {({ isActive }) => (
          <>
            {/* One shared pill slides to the active item (reduced motion: it just appears). */}
            {isActive && <motion.span layoutId="nav-pill" className="nav-pill" />}
            {icon}
            <span>{children}</span>
          </>
        )}
      </NavLink>
    </li>
  );
}

/** The repo's name from the already-loaded repository list; never fetches on its own. */
function useRepoName(repoId) {
  const repos = useQuery({
    queryKey: ['repos'],
    queryFn: () => api('/api/v1/repos'),
    enabled: false,
  });
  return repos.data?.find?.((r) => String(r.id) === repoId)?.fullName;
}

function Sidebar({ me, onSignOut, signingOut }) {
  const repoMatch = useMatch('/repos/:repoId/*');
  const repoId = isId(repoMatch?.params.repoId) ? repoMatch.params.repoId : undefined;
  const repoName = useRepoName(repoId);
  const base = `/repos/${repoId}`;
  return (
    <aside className="sidebar">
      <NavLink to="/repos" className="brand">
        <Brand />
      </NavLink>
      <nav className="sidebar-nav" aria-label="Main">
        <div className="nav-group">
          <ul className="nav-list">
            <NavItem to="/repos" end icon={<IconRepos />}>
              Repositories
            </NavItem>
          </ul>
        </div>
        {repoId && (
          <div className="nav-group">
            <p className="nav-label">
              Repository
              {repoName && <span>{repoName}</span>}
            </p>
            <ul className="nav-list">
              <NavItem to={base} end icon={<IconTrend />}>
                Dashboard
              </NavItem>
              <NavItem to={`${base}/issues`} icon={<IconIssues />}>
                Issues
              </NavItem>
              <NavItem to={`${base}/pulls`} icon={<IconPull />}>
                Pull requests
              </NavItem>
              <NavItem to={`${base}/reports`} icon={<IconReport />}>
                Reports
              </NavItem>
            </ul>
          </div>
        )}
      </nav>
      <div className="sidebar-foot">
        <div className="user">
          {me.avatarUrl ? (
            <img className="avatar" src={me.avatarUrl} alt="" />
          ) : (
            <span className="avatar" aria-hidden="true">
              {me.login.slice(0, 1).toUpperCase()}
            </span>
          )}
          <span className="stack" style={{ gap: 0, minWidth: 0 }}>
            <span className="user-name">{me.name || me.login}</span>
            {me.name && <span className="muted small">@{me.login}</span>}
          </span>
        </div>
        <button
          type="button"
          className="btn btn-small btn-ghost"
          style={{ justifySelf: 'start', paddingLeft: 0 }}
          onClick={onSignOut}
          disabled={signingOut}
        >
          <IconSignOut />
          Sign out
        </button>
      </div>
    </aside>
  );
}

const isId = (s) => /^\d+$/.test(s ?? '');

/** True when a repo, issue or report id in the path is not a number (no request is made). */
function hasBadId(pathname) {
  const m = pathname.match(/^\/repos\/([^/]+)(?:\/(?:issues|reports)\/([^/]+))?/);
  return !!m && m.slice(1).some((s) => s !== undefined && !isId(s));
}

/** The not-found page, also used for ids that do not exist or are not the user's. */
export function NotFoundPage({
  title = 'Page not found',
  text = 'There is nothing at this address.',
}) {
  return (
    <>
      <PageHeader title={title} />
      <div className="card empty">
        <p>{text}</p>
        <p style={{ marginTop: 12 }}>
          <Link to="/repos">Go to your repositories</Link>
        </p>
      </div>
    </>
  );
}

/** A rendering error shows a message for this page only; the sidebar stays usable. */
class PageErrorBoundary extends Component {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error) {
    console.error('Page failed to render', error); // details stay in the console, not the UI
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="notice notice-error" role="alert">
        This page could not be shown. <a href={window.location.href}>Reload the page</a> or go back
        to <Link to="/repos">your repositories</Link>.
      </div>
    );
  }
}

/** Signed-in shell: sidebar plus content; renders nothing protected until /me succeeds. */
export function AppShell() {
  const me = useMe();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const logout = useLogout();
  const signedIn = me.isSuccess;
  const here = location.pathname + location.search;

  // After OAuth the backend lands on /repos; continue to the page the user first asked for.
  // This runs in an effect, not during render: StrictMode renders twice and would lose it.
  useEffect(() => {
    if (!signedIn) return;
    const next = safeNext(sessionStorage.getItem(NEXT_KEY));
    sessionStorage.removeItem(NEXT_KEY);
    if (next && next !== here) navigate(next, { replace: true });
  }, [signedIn, here, navigate]);

  if (me.isPending) {
    return (
      <p className="login muted" aria-busy="true">
        Loading
      </p>
    );
  }
  if (me.isError) {
    if (me.error.status === 401) {
      return (
        <Navigate
          to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
          replace
        />
      );
    }
    return (
      <div className="login">
        <div className="notice notice-error" role="alert">
          The GitMedic server is not reachable. Check that the backend is running, then reload.
        </div>
      </div>
    );
  }

  async function signOut() {
    try {
      await logout.mutateAsync();
    } finally {
      qc.clear();
      navigate('/login', { replace: true });
    }
  }

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <div className="app">
        <Sidebar me={me.data} onSignOut={signOut} signingOut={logout.isPending} />
        <main id="main" className="content">
          {/* A short fade-and-rise as each page arrives; query-string changes keep the page. */}
          <motion.div
            key={location.pathname}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
          >
            <PageErrorBoundary key={location.pathname}>
              {hasBadId(location.pathname) ? <NotFoundPage /> : <Outlet />}
            </PageErrorBoundary>
          </motion.div>
        </main>
      </div>
    </>
  );
}

/** Figma page header: 24px title, optional meta line, actions on the right, divider below. */
export function PageHeader({ title, meta, actions, crumb }) {
  return (
    <header className="page-header">
      <div className="page-header-text">
        {crumb && <p className="crumb">{crumb}</p>}
        <h1>{title}</h1>
        {meta && <div className="page-meta">{meta}</div>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

/** External GitHub links open in a new tab without opener access (UX-03). */
export function GitHubLink({ href, children }) {
  if (!/^https?:\/\//i.test(href ?? '')) {
    return <span>{children}</span>;
  }
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <span className="visually-hidden"> (opens GitHub in a new tab)</span>
    </a>
  );
}

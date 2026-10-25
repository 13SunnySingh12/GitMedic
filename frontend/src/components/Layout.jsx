import { NavLink, Navigate, Outlet, useLocation, useNavigate, useParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useLogout, useMe } from '../api/hooks.js';
import { safeNext } from '../api/client.js';

export const NEXT_KEY = 'gitmedic.next';

/** Signed-in shell: header with user menu; renders nothing protected until /me succeeds. */
export function AppShell() {
  const me = useMe();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const logout = useLogout();

  if (me.isPending) {
    return (
      <p className="main muted" aria-busy="true">
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
      <div className="main">
        <div className="notice notice-error" role="alert">
          The GitMedic server is not reachable. Check that the backend is running, then reload.
        </div>
      </div>
    );
  }

  // After OAuth the backend lands on /repos; continue to the page the user originally asked for.
  const next = safeNext(sessionStorage.getItem(NEXT_KEY));
  if (next) {
    sessionStorage.removeItem(NEXT_KEY);
    if (next !== location.pathname + location.search) {
      return <Navigate to={next} replace />;
    }
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
      <header className="header">
        <div className="header-inner">
          <NavLink to="/repos" className="brand">
            GitMedic
          </NavLink>
          <span className="header-spacer" />
          <span className="user">
            {me.data.avatarUrl && <img src={me.data.avatarUrl} alt="" />}
            <span>{me.data.login}</span>
          </span>
          <button
            type="button"
            className="btn btn-small"
            onClick={signOut}
            disabled={logout.isPending}
          >
            Sign out
          </button>
        </div>
      </header>
      <main id="main" className="main">
        <Outlet />
      </main>
    </>
  );
}

/** Shared sub-navigation for repo-scoped pages (§3.1). */
export function RepoNav() {
  const { repoId } = useParams();
  const base = `/repos/${repoId}`;
  const links = [
    [base, 'Dashboard', true],
    [`${base}/issues`, 'Issues'],
    [`${base}/pulls`, 'Pull requests'],
    [`${base}/reports`, 'Reports'],
  ];
  return (
    <nav className="subnav" aria-label="Repository">
      {links.map(([to, label, end]) => (
        <NavLink key={to} to={to} end={end}>
          {label}
        </NavLink>
      ))}
    </nav>
  );
}

/** External GitHub links open in a new tab without opener access (UX-03). */
export function GitHubLink({ href, children }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <span className="visually-hidden"> (opens GitHub in a new tab)</span>
    </a>
  );
}

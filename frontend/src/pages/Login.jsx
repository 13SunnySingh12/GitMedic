import { useSearchParams } from 'react-router';
import { safeNext } from '../api/client.js';
import { NEXT_KEY } from '../components/Layout.jsx';

const ERRORS = {
  oauth_failed: 'Sign-in was cancelled or could not be verified. Please try again.',
  github_unavailable: 'GitHub could not be reached. Please try again in a moment.',
};

export default function Login() {
  const [params] = useSearchParams();
  const error = params.get('error');
  const next = safeNext(params.get('next'));

  function remember() {
    if (next) sessionStorage.setItem(NEXT_KEY, next);
  }

  return (
    <main className="login">
      <div className="login-card">
        <h1>GitMedic</h1>
        <p className="muted">
          Health checks for your GitHub repositories: possible duplicate issues, predicted labels
          and merge times, abandonment risk, and a weekly report.
        </p>
        {error && (
          <div className="notice notice-error" role="alert">
            {ERRORS[error] ?? 'Sign-in failed. Please try again.'}
          </div>
        )}
        <p>
          {/* A full-page navigation: Spring Security runs the OAuth flow (via the Vite proxy). */}
          <a className="btn btn-primary" href="/oauth2/authorization/github" onClick={remember}>
            Sign in with GitHub
          </a>
        </p>
        <p className="muted small">
          GitMedic reads repository data only; it never writes to GitHub.
        </p>
      </div>
    </main>
  );
}

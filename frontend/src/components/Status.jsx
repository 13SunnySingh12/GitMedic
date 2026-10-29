import { LEVEL_TEXT, SYNC_TEXT, dateTime, relative } from '../utils/format.js';

/** UX-01: level text and numeric score, never colour alone. */
export function RiskBadge({ risk }) {
  if (!risk || risk.score == null) {
    return <span className="badge">Risk not scored</span>;
  }
  const level = LEVEL_TEXT[risk.level] ?? risk.level;
  return (
    <span className={`badge badge-${risk.level.toLowerCase()}`}>
      {level} risk · {risk.score}
    </span>
  );
}

export function SyncStatus({ status, error, lastSyncedAt }) {
  return (
    <span className="stack" style={{ gap: 2 }}>
      <span>
        {SYNC_TEXT[status] ?? status}
        {status === 'DONE' && lastSyncedAt && (
          <span className="muted small" title={dateTime(lastSyncedAt)}>
            {' '}
            {relative(lastSyncedAt)}
          </span>
        )}
      </span>
      {error && (status === 'FAILED' || status === 'RATE_LIMITED') && (
        <span className={`small ${status === 'FAILED' ? 'error-text' : 'muted'}`}>{error}</span>
      )}
    </span>
  );
}

export function errorMessage(error) {
  if (!error) return '';
  if (error.status === 503 && error.code === 'ML_UNAVAILABLE') {
    return 'The ML service is not reachable right now. Try again once it is running.';
  }
  if (error.status === 404) {
    return 'Not found. It may have been removed, or your account cannot access it.';
  }
  return error.message || 'Something went wrong.';
}

/** Client errors (bad id, not found, validation) do not change on retry; 408/429 may. */
const retryable = (e) => !(e?.status >= 400 && e.status < 500) || [408, 429].includes(e.status);

/**
 * UX-04: every data widget handles loading, error and empty on its own.
 * `skeleton` sizes the loading placeholder to the final content.
 */
export function QueryState({ query, empty, isEmpty, skeleton = 120, children }) {
  if (query.isPending) {
    return (
      <span className="skeleton" style={{ minHeight: skeleton }} aria-busy="true">
        <span className="visually-hidden">Loading</span>
      </span>
    );
  }
  if (query.isError) {
    return (
      <div className="notice notice-error" role="alert">
        <p>{errorMessage(query.error)}</p>
        {retryable(query.error) && (
          <button type="button" className="link-button small" onClick={() => query.refetch()}>
            Try again
          </button>
        )}
      </div>
    );
  }
  if (isEmpty?.(query.data)) {
    return <div className="empty">{empty}</div>;
  }
  return children(query.data);
}

/** Labels model output as a prediction (UX-02). */
export function Prediction({ label, confidence }) {
  if (!label) return <span className="muted">Not predicted</span>;
  return (
    <span className="row" style={{ gap: 6 }} title="Predicted by this repository's label model">
      <span className="badge badge-lavender">{label}</span>
      <span className="muted small">(predicted, {Math.round(confidence * 100)}%)</span>
    </span>
  );
}

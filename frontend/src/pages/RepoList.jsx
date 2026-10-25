import { useDeferredValue, useState } from 'react';
import { Link } from 'react-router';
import { useGithubRepos, useRepos, useSync, useTrack, useUntrack } from '../api/hooks.js';
import { Dialog } from '../components/Dialog.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { QueryState, RiskBadge, SyncStatus, errorMessage } from '../components/Status.jsx';

const busy = (s) => s === 'PENDING' || s === 'RUNNING';

function AddRepoDialog({ open, onClose }) {
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const search = useDeferredValue(q);
  const repos = useGithubRepos({ q: search, page, size: 20 }, open);
  const track = useTrack();

  return (
    <Dialog open={open} onClose={onClose} title="Add repository">
      <div className="field">
        <label htmlFor="repo-search">Search your GitHub repositories</label>
        <input
          id="repo-search"
          type="search"
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setPage(0);
          }}
          autoComplete="off"
        />
      </div>
      {track.isError && (
        <p className="error-text small" role="alert">
          {track.error.code === 'REPO_ALREADY_TRACKED'
            ? 'That repository is already tracked.'
            : errorMessage(track.error)}
        </p>
      )}
      <QueryState
        query={repos}
        skeleton={200}
        isEmpty={(d) => d.totalItems === 0}
        empty={
          search
            ? `No repositories match "${search}".`
            : 'No repositories found on your GitHub account.'
        }
      >
        {(data) => (
          <>
            <ul className="picker">
              {data.items.map((r) => (
                <li key={r.githubRepoId}>
                  <span className="name">
                    {r.fullName} {r.isPrivate && <span className="badge">Private</span>}
                  </span>
                  {r.tracked ? (
                    <span className="muted small">Tracked</span>
                  ) : (
                    <button
                      type="button"
                      className="btn btn-small btn-primary"
                      disabled={track.isPending}
                      onClick={() =>
                        track.mutate(
                          { githubRepoId: r.githubRepoId, fullName: r.fullName },
                          { onSuccess: onClose },
                        )
                      }
                    >
                      Track
                    </button>
                  )}
                </li>
              ))}
            </ul>
            <Pagination {...data} onPage={setPage} label={['repository', 'repositories']} />
          </>
        )}
      </QueryState>
    </Dialog>
  );
}

function UntrackDialog({ repo, onClose }) {
  const untrack = useUntrack();
  return (
    <Dialog open={!!repo} onClose={onClose} title="Stop tracking repository?">
      {repo && (
        <div className="stack">
          <p>
            GitMedic will delete everything it stored for <strong>{repo.fullName}</strong>: synced
            issues, pull requests, commits, trained models, risk history and reports. Nothing
            changes on GitHub.
          </p>
          {untrack.isError && (
            <p className="error-text" role="alert">
              {errorMessage(untrack.error)}
            </p>
          )}
          <div className="row">
            <button
              type="button"
              className="btn btn-danger"
              disabled={untrack.isPending}
              onClick={() => untrack.mutate(repo.id, { onSuccess: onClose })}
            >
              {untrack.isPending ? 'Removing' : 'Stop tracking'}
            </button>
            <button type="button" className="btn" onClick={onClose}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </Dialog>
  );
}

export default function RepoList() {
  const repos = useRepos();
  const sync = useSync();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState(null);
  const [notice, setNotice] = useState('');

  function syncNow(repo) {
    setNotice('');
    sync.mutate(repo.id, {
      onError: (e) =>
        setNotice(
          e.code === 'SYNC_ALREADY_RUNNING' || e.code === 'JOB_RUNNING'
            ? `${repo.fullName}: a sync or another job is already running.`
            : `${repo.fullName}: ${errorMessage(e)}`,
        ),
    });
  }

  return (
    <>
      <div className="page-head">
        <h1>Repositories</h1>
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          Add repository
        </button>
      </div>
      <p aria-live="polite" className="error-text small" style={{ minHeight: '1.5em' }}>
        {notice}
      </p>
      <QueryState
        query={repos}
        skeleton={160}
        isEmpty={(d) => d.length === 0}
        empty={
          <>
            <p>Track your first repository to see its health.</p>
            <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
              Add repository
            </button>
          </>
        }
      >
        {(list) => (
          <div className="table-wrap">
            <table className="stacked">
              <caption className="visually-hidden">Tracked repositories</caption>
              <thead>
                <tr>
                  <th scope="col">Repository</th>
                  <th scope="col">Risk</th>
                  <th scope="col">Sync</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody aria-live="polite">
                {list.map((r) => (
                  <tr key={r.id}>
                    <td className="title-cell">
                      <Link to={`/repos/${r.id}`}>{r.fullName}</Link>{' '}
                      <span className="muted small">{r.isPrivate ? 'Private' : 'Public'}</span>
                    </td>
                    <td data-label="Risk">
                      <RiskBadge risk={r.risk} />
                    </td>
                    <td data-label="Sync">
                      <SyncStatus
                        status={r.syncStatus}
                        error={r.syncError}
                        lastSyncedAt={r.lastSyncedAt}
                      />
                    </td>
                    <td data-label="Actions">
                      <span className="row" style={{ justifyContent: 'flex-end' }}>
                        <button
                          type="button"
                          className="btn btn-small"
                          disabled={
                            busy(r.syncStatus) || (sync.isPending && sync.variables === r.id)
                          }
                          onClick={() => syncNow(r)}
                        >
                          {busy(r.syncStatus)
                            ? 'Syncing'
                            : r.syncStatus === 'FAILED'
                              ? 'Retry sync'
                              : 'Sync now'}
                        </button>
                        <button
                          type="button"
                          className="btn btn-small btn-danger"
                          onClick={() => setRemoving(r)}
                        >
                          Untrack
                        </button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
      <AddRepoDialog open={adding} onClose={() => setAdding(false)} />
      <UntrackDialog repo={removing} onClose={() => setRemoving(null)} />
    </>
  );
}

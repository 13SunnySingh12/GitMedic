import { Link, useParams, useSearchParams } from 'react-router';
import { useDashboard, usePulls } from '../api/hooks.js';
import { FilterSelect } from '../components/FilterSelect.jsx';
import { GitHubLink, PageHeader } from '../components/Layout.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { QueryState } from '../components/Status.jsx';
import { days, relative } from '../utils/format.js';

export default function Pulls() {
  const { repoId } = useParams();
  const [params, setParams] = useSearchParams();
  const state = params.get('state') ?? 'open';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const pulls = usePulls(repoId, { state, page, size: 25 });
  const dashboard = useDashboard(repoId);
  const model = dashboard.data?.models?.mergeTime;

  function set(key, value) {
    const next = new URLSearchParams(params);
    next.set(key, String(value));
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: key !== 'page' });
  }

  return (
    <>
      <PageHeader title="Pull requests" />
      {model && !model.trained && (
        <p className="notice" style={{ marginBottom: 16 }}>
          Merge-time estimates appear once the merge-time model is trained ({model.have} of{' '}
          {model.need} merged pull requests available).{' '}
          <Link to={`/repos/${repoId}`}>Open model status</Link>
        </p>
      )}
      <div className="filters">
        <FilterSelect
          label="State"
          value={state}
          onChange={(v) => set('state', v)}
          options={[
            ['open', 'Open'],
            ['closed', 'Closed'],
            ['all', 'All'],
          ]}
        />
      </div>
      <QueryState
        query={pulls}
        skeleton={300}
        isEmpty={(d) => d.totalItems === 0}
        empty={state === 'open' ? 'No open pull requests.' : 'No pull requests match.'}
      >
        {(data) => (
          <>
            <div className="card table-card">
              <table className="stacked">
                <caption className="visually-hidden">Pull requests</caption>
                <thead>
                  <tr>
                    <th scope="col" className="num">
                      #
                    </th>
                    <th scope="col">Title</th>
                    <th scope="col">Author</th>
                    <th scope="col">Size</th>
                    <th scope="col" className="num">
                      Reviews
                    </th>
                    <th scope="col">Opened</th>
                    <th scope="col">Merge time</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((p) => (
                    <tr key={p.number}>
                      <td data-label="#" className="num">
                        {p.number}
                      </td>
                      <td className="title-cell">
                        <GitHubLink href={p.htmlUrl}>{p.title}</GitHubLink>
                      </td>
                      <td data-label="Author">{p.authorLogin ?? 'unknown'}</td>
                      <td data-label="Size">
                        <span title={`${p.additions} lines added, ${p.deletions} removed`}>
                          +{p.additions} / -{p.deletions}
                        </span>{' '}
                        <span className="muted small">{p.changedFiles} files</span>
                      </td>
                      <td data-label="Reviews" className="num">
                        {p.reviewCount}
                      </td>
                      <td data-label="Opened">{relative(p.createdAt)}</td>
                      <td data-label="Merge time">
                        {p.mergedAt ? (
                          <span className="badge badge-mint">merged</span>
                        ) : p.predictedMergeDays != null ? (
                          <span className="row" style={{ gap: 6 }}>
                            <span className="badge badge-lavender">
                              about {days(p.predictedMergeDays)}
                            </span>
                            <span className="muted small">(predicted)</span>
                          </span>
                        ) : (
                          <span className="muted">No estimate</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              {...data}
              onPage={(n) => set('page', n)}
              label={['pull request', 'pull requests']}
            />
          </>
        )}
      </QueryState>
    </>
  );
}

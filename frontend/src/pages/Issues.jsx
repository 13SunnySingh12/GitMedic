import { Link, useParams, useSearchParams } from 'react-router';
import { useDashboard, useIssues } from '../api/hooks.js';
import { FilterSelect } from '../components/FilterSelect.jsx';
import { PageHeader } from '../components/Layout.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { Prediction, QueryState } from '../components/Status.jsx';
import { date } from '../utils/format.js';

/** Filters live in the URL (FR-025 / UX-07) so views are shareable and survive reloads. */
export default function Issues() {
  const { repoId } = useParams();
  const [params, setParams] = useSearchParams();
  const state = params.get('state') ?? 'open';
  const label = params.get('label') ?? '';
  const duplicate = params.get('duplicate') === 'true';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const issues = useIssues(repoId, {
    state,
    label,
    duplicate: duplicate ? 'true' : undefined,
    page,
    size: 25,
  });
  const dashboard = useDashboard(repoId);

  function set(key, value) {
    const next = new URLSearchParams(params);
    if (value === '' || value === undefined || value === null) next.delete(key);
    else next.set(key, String(value));
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: key !== 'page' });
  }

  const labelModel = dashboard.data?.models?.label;

  return (
    <>
      <PageHeader title="Issues" />
      {dashboard.data?.mlStatus === 'UNAVAILABLE' && (
        <p className="notice notice-warn" style={{ marginBottom: 16 }}>
          The ML service is unavailable. Labels and duplicate flags may be out of date.
        </p>
      )}
      {labelModel && !labelModel.trained && (
        <p className="notice" style={{ marginBottom: 16 }}>
          Predicted labels appear once the label model is trained.{' '}
          <Link to={`/repos/${repoId}`}>Open model status on the dashboard</Link>
        </p>
      )}
      <form className="filters" onSubmit={(e) => e.preventDefault()} aria-label="Issue filters">
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
        <FilterSelect
          label="Predicted label"
          value={label}
          onChange={(v) => set('label', v)}
          options={[
            ['', 'Any'],
            ['bug', 'bug'],
            ['feature', 'feature'],
            ['question', 'question'],
          ]}
        />
        <label className="pill">
          <input
            type="checkbox"
            checked={duplicate}
            onChange={(e) => set('duplicate', e.target.checked ? 'true' : '')}
          />
          Possible duplicates only
        </label>
      </form>
      <p className="muted small" style={{ marginBottom: 16 }}>
        Labels and duplicate flags are predictions from this repository&apos;s own history, not
        facts. &ldquo;Possible duplicate&rdquo; means another issue here reads very similarly (score
        0 to 1, higher is closer); check it before closing anything.
      </p>
      <QueryState
        query={issues}
        skeleton={300}
        isEmpty={(d) => d.totalItems === 0}
        empty="No issues match these filters."
      >
        {(data) => (
          <>
            <div className="card table-card">
              <table className="stacked">
                <caption className="visually-hidden">Issues</caption>
                <thead>
                  <tr>
                    <th scope="col" className="num">
                      #
                    </th>
                    <th scope="col">Title</th>
                    <th scope="col">State</th>
                    <th scope="col">Label</th>
                    <th scope="col">Duplicate</th>
                    <th scope="col">Opened</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((i) => (
                    <tr key={i.number}>
                      <td data-label="#" className="num">
                        {i.number}
                      </td>
                      <td className="title-cell">
                        <Link to={`/repos/${repoId}/issues/${i.number}`}>{i.title}</Link>
                      </td>
                      <td data-label="State">{i.state}</td>
                      <td data-label="Label">
                        <Prediction label={i.predictedLabel} confidence={i.predictedConfidence} />
                      </td>
                      <td data-label="Duplicate">
                        {i.isPossibleDuplicate ? (
                          <span className="badge badge-mint">
                            Possible duplicate · {i.duplicateScore.toFixed(2)}
                          </span>
                        ) : (
                          <span className="muted">No</span>
                        )}
                      </td>
                      <td data-label="Opened">{date(i.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination {...data} onPage={(p) => set('page', p)} label={['issue', 'issues']} />
          </>
        )}
      </QueryState>
    </>
  );
}

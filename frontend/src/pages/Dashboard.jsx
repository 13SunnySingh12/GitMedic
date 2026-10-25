import { useParams } from 'react-router';
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useBusFactor, useDashboard, useHeatmap, useSync, useTrain } from '../api/hooks.js';
import { Heatmap, WEEKDAYS } from '../components/Heatmap.jsx';
import { RepoNav } from '../components/Layout.jsx';
import { QueryState, RiskBadge, SyncStatus, errorMessage } from '../components/Status.jsx';
import { date, percent, signed } from '../utils/format.js';

const COMPONENTS = [
  ['daysSinceCommit', 'Days since last commit'],
  ['openIssueGrowth', 'Open-issue growth (30 days)'],
  ['unansweredRate', 'Unanswered issues (> 7 days)'],
  ['contributorDropoff', 'Contributor drop-off (90 days)'],
];

function RiskPanel({ risk }) {
  if (!risk) {
    return (
      <p className="muted">No risk score yet. It is computed after the first successful sync.</p>
    );
  }
  return (
    <div className="stack">
      <div className="row" style={{ alignItems: 'baseline' }}>
        <span className="big-number">{risk.score}</span>
        <RiskBadge risk={risk} />
      </div>
      <p className="muted small">
        {risk.delta == null
          ? 'No score from a week ago to compare with yet.'
          : `${signed(risk.delta)} vs. a week ago`}{' '}
        (computed {date(risk.computedAt)})
      </p>
      <table>
        <caption className="visually-hidden">
          Risk components, 0 is healthy and 100% is worst
        </caption>
        <tbody>
          {COMPONENTS.map(([key, label]) => (
            <tr key={key}>
              <th scope="row" style={{ fontWeight: 400, color: 'var(--text)' }}>
                {label}
              </th>
              <td className="num">{percent(risk.components?.[key])}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Model({ name, title, model, metric, train }) {
  const running = train.isPending && train.variables === name;
  const failed = train.isError && train.variables === name ? train.error : null;
  return (
    <div className="stack" style={{ gap: 6 }}>
      <h3>{title}</h3>
      {model.trained ? (
        <p>
          Trained {date(model.trainedAt)} on {model.samples} samples. {metric}
        </p>
      ) : model.reason === 'INSUFFICIENT_TRAINING_DATA' ? (
        <p className="muted">
          Not enough data yet: {model.have} of {model.need} needed.
        </p>
      ) : (
        <p className="muted">Not trained yet. Enough data is available ({model.have} samples).</p>
      )}
      <div>
        <button
          type="button"
          className="btn btn-small"
          disabled={!model.ready || train.isPending}
          onClick={() => train.mutate(name)}
        >
          {running ? 'Training' : model.trained ? 'Retrain' : 'Train'}
        </button>
      </div>
      {failed && (
        <p className="error-text small" role="alert">
          {failed.code === 'INSUFFICIENT_TRAINING_DATA' ? failed.message : errorMessage(failed)}
        </p>
      )}
    </div>
  );
}

function ModelPanel({ models, mlStatus, repoId }) {
  const train = useTrain(repoId);
  if (mlStatus === 'UNAVAILABLE' || !models) {
    return (
      <p className="notice notice-warn">
        The ML service is unavailable, so model status and predictions cannot be shown right now.
      </p>
    );
  }
  return (
    <div className="stack">
      <p className="visually-hidden" aria-live="polite">
        {train.isPending ? 'Training model' : train.isSuccess ? 'Training finished' : ''}
      </p>
      <Model
        name="label"
        title="Issue label model"
        model={models.label}
        metric={
          models.label.trained
            ? `Macro-F1 ${models.label.macroF1.toFixed(2)} on held-out issues.`
            : ''
        }
        train={train}
      />
      <Model
        name="merge-time"
        title="Merge-time model"
        model={models.mergeTime}
        metric={
          models.mergeTime.trained ? `Mean error ${models.mergeTime.maeDays.toFixed(1)} days.` : ''
        }
        train={train}
      />
      <p className="muted small">
        {models.chunks} indexed text chunks for duplicate search and answers.
      </p>
    </div>
  );
}

function Weekdays({ data }) {
  const rows = data.weekdays.map((w) => ({ day: WEEKDAYS[w.weekday - 1], commits: w.count }));
  return (
    <>
      <div style={{ height: 160 }} aria-hidden="true">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} margin={{ top: 4, right: 4, bottom: 0, left: -24 }}>
            <XAxis dataKey="day" tickLine={false} axisLine={false} fontSize={12} />
            <YAxis allowDecimals={false} tickLine={false} axisLine={false} fontSize={12} />
            <Tooltip cursor={{ fill: 'var(--surface-sunk)' }} />
            <Bar dataKey="commits" fill="var(--accent)" radius={[3, 3, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <table className="visually-hidden">
        <caption>Commits per weekday</caption>
        <tbody>
          {rows.map((r) => (
            <tr key={r.day}>
              <th scope="row">{r.day}</th>
              <td>{r.commits}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function BusFactor({ data }) {
  return (
    <div className="stack">
      <div className="row" style={{ alignItems: 'baseline' }}>
        <span className="big-number">{data.busFactor ?? 'n/a'}</span>
        {data.risky && <span className="badge badge-high">Risky</span>}
      </div>
      <p className="muted small">
        Fewest contributors covering half of the {data.totalCommits} default-branch commits in the
        last 12 months. 1 or 2 means the project depends on very few people.
      </p>
      <div style={{ height: 32 * Math.max(data.contributors.length, 1) }} aria-hidden="true">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data.contributors}
            layout="vertical"
            margin={{ top: 0, right: 8, bottom: 0, left: 8 }}
          >
            <XAxis type="number" hide />
            <YAxis
              type="category"
              dataKey="login"
              width={110}
              tickLine={false}
              axisLine={false}
              fontSize={12}
            />
            <Tooltip cursor={{ fill: 'var(--surface-sunk)' }} />
            <Bar dataKey="commits" fill="var(--accent)" radius={[0, 3, 3, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <table className="visually-hidden">
        <caption>Top contributors by commits</caption>
        <tbody>
          {data.contributors.map((c) => (
            <tr key={c.login}>
              <th scope="row">{c.login}</th>
              <td>
                {c.commits} commits ({percent(c.share)})
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function Dashboard() {
  const { repoId } = useParams();
  const dashboard = useDashboard(repoId);
  const heatmap = useHeatmap(repoId);
  const busFactor = useBusFactor(repoId);
  const sync = useSync(repoId);
  const repo = dashboard.data?.repo;
  const syncing = repo && (repo.syncStatus === 'PENDING' || repo.syncStatus === 'RUNNING');

  return (
    <>
      <div className="page-head">
        <div className="stack" style={{ gap: 4 }}>
          <h1>{repo?.fullName ?? 'Repository'}</h1>
          {repo && (
            <span className="meta" aria-live="polite">
              <SyncStatus
                status={repo.syncStatus}
                error={repo.syncError}
                lastSyncedAt={repo.lastSyncedAt}
              />
            </span>
          )}
        </div>
        <button
          type="button"
          className="btn"
          disabled={!repo || syncing || sync.isPending}
          onClick={() => sync.mutate(undefined)}
        >
          {syncing ? 'Syncing' : 'Sync now'}
        </button>
      </div>
      {sync.isError && (
        <p className="error-text small" role="alert">
          {sync.error.code === 'SYNC_ALREADY_RUNNING'
            ? 'A sync is already running.'
            : errorMessage(sync.error)}
        </p>
      )}
      <RepoNav />
      <div className="grid grid-dash">
        <section className="panel span-4" aria-labelledby="risk-h">
          <h2 id="risk-h">Abandonment risk</h2>
          <QueryState query={dashboard} skeleton={180}>
            {(d) => <RiskPanel risk={d.risk} />}
          </QueryState>
        </section>
        <section className="panel span-8" aria-labelledby="stats-h">
          <h2 id="stats-h">At a glance</h2>
          <QueryState query={dashboard} skeleton={60}>
            {(d) => (
              <dl className="stats" style={{ margin: 0 }}>
                <div className="stat">
                  <dt>Open issues</dt>
                  <dd>{d.stats.openIssues}</dd>
                </div>
                <div className="stat">
                  <dt>Closed (7 days)</dt>
                  <dd>{d.stats.closedIssues7d}</dd>
                </div>
                <div className="stat">
                  <dt>Open pull requests</dt>
                  <dd>{d.stats.openPrs}</dd>
                </div>
                <div className="stat">
                  <dt>Possible duplicates (7 days)</dt>
                  <dd>{d.stats.duplicatesFlagged7d}</dd>
                </div>
              </dl>
            )}
          </QueryState>
          <h2 style={{ margin: '20px 0 12px' }}>Models</h2>
          <QueryState query={dashboard} skeleton={120}>
            {(d) => <ModelPanel models={d.models} mlStatus={d.mlStatus} repoId={repoId} />}
          </QueryState>
        </section>
        <section className="panel span-12" aria-labelledby="heat-h">
          <h2 id="heat-h">Commit activity</h2>
          <QueryState
            query={heatmap}
            skeleton={110}
            isEmpty={(d) => d.total === 0}
            empty="No commits on the default branch in the last 52 weeks."
          >
            {(d) => (
              <div className="grid grid-dash">
                <div className="span-8">
                  <Heatmap data={d} />
                </div>
                <div className="span-4">
                  <h3 style={{ marginBottom: 8 }}>By weekday</h3>
                  <Weekdays data={d} />
                </div>
              </div>
            )}
          </QueryState>
        </section>
        <section className="panel span-12" aria-labelledby="bus-h">
          <h2 id="bus-h">Bus factor</h2>
          <QueryState
            query={busFactor}
            skeleton={140}
            isEmpty={(d) => d.totalCommits === 0}
            empty="No commits in the last 12 months, so there is no bus factor yet."
          >
            {(d) => <BusFactor data={d} />}
          </QueryState>
        </section>
      </div>
    </>
  );
}

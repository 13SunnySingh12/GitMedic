import { useState } from 'react';
import { useParams } from 'react-router';
import { useBusFactor, useDashboard, useHeatmap, useSync, useTrain } from '../api/hooks.js';
import { ActivityBars, Meters } from '../components/Charts.jsx';
import { Heatmap, WEEKDAYS } from '../components/Heatmap.jsx';
import { IconSync } from '../components/Icons.jsx';
import { PageHeader } from '../components/Layout.jsx';
import { QueryState, RiskBadge, SyncStatus, errorMessage } from '../components/Status.jsx';
import { date, percent, signed } from '../utils/format.js';

const COMPONENTS = [
  ['daysSinceCommit', 'Days since last commit'],
  ['openIssueGrowth', 'Open-issue growth (30 days)'],
  ['unansweredRate', 'Unanswered issues (> 7 days)'],
  ['contributorDropoff', 'Contributor drop-off (90 days)'],
];

const MONTH = new Intl.DateTimeFormat(undefined, { month: 'short', timeZone: 'UTC' });
const MONTH_YEAR = new Intl.DateTimeFormat(undefined, {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

/** Commits per calendar month across the heatmap window (days are UTC calendar dates). */
export function monthlyCommits(data) {
  const counts = new Map();
  for (const d of data.days)
    counts.set(d.date.slice(0, 7), (counts.get(d.date.slice(0, 7)) ?? 0) + d.count);
  const rows = [];
  const end = data.to.slice(0, 7);
  for (
    let m = new Date(`${data.from.slice(0, 7)}-01T00:00:00Z`);
    ;
    m.setUTCMonth(m.getUTCMonth() + 1)
  ) {
    const key = m.toISOString().slice(0, 7);
    const value = counts.get(key) ?? 0;
    rows.push({
      label: MONTH.format(m),
      value,
      title: `${MONTH_YEAR.format(m)}: ${value} commits`,
    });
    if (key >= end) break;
  }
  return rows;
}

function Kpi({ label, children }) {
  return (
    <div className="card kpi">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function RiskKpi({ risk }) {
  if (!risk) {
    return (
      <Kpi label="Abandonment risk">
        <span className="kpi-note">
          No risk score yet. It is computed after the first successful sync.
        </span>
      </Kpi>
    );
  }
  return (
    <Kpi label="Abandonment risk">
      <span>
        <span className="kpi-value">{risk.score}</span>
        <span className="kpi-suffix">/100</span>
      </span>
      <RiskBadge risk={risk} />
      <span className="kpi-note">
        {risk.delta == null
          ? 'No score from a week ago to compare with yet.'
          : `${signed(risk.delta)} vs. a week ago`}
      </span>
    </Kpi>
  );
}

function BusFactorKpi({ query }) {
  return (
    <Kpi label="Bus factor">
      <QueryState
        query={query}
        skeleton={48}
        isEmpty={(d) => d.totalCommits === 0}
        empty={
          <span className="kpi-note">
            No commits in the last 12 months, so there is no bus factor yet.
          </span>
        }
      >
        {(d) => (
          <>
            <span className="kpi-value">{d.busFactor ?? 'n/a'}</span>
            {d.risky && <span className="badge badge-high">Risky</span>}
            <span className="kpi-note">of {d.totalCommits} commits, 12 months</span>
          </>
        )}
      </QueryState>
    </Kpi>
  );
}

function Activity({ query }) {
  const [view, setView] = useState('month');
  return (
    <section className="card" aria-labelledby="activity-h">
      <div className="card-head">
        <h2 id="activity-h" className="card-title">
          Commit activity
        </h2>
        <div className="segmented" role="group" aria-label="Group commits by">
          <button type="button" aria-pressed={view === 'month'} onClick={() => setView('month')}>
            Month
          </button>
          <button
            type="button"
            aria-pressed={view === 'weekday'}
            onClick={() => setView('weekday')}
          >
            Weekday
          </button>
        </div>
      </div>
      <QueryState
        query={query}
        skeleton={232}
        isEmpty={(d) => d.total === 0}
        empty="No commits on the default branch in the last 52 weeks."
      >
        {(d) =>
          view === 'month' ? (
            <ActivityBars rows={monthlyCommits(d)} caption="Commits per month" />
          ) : (
            <ActivityBars
              caption="Commits per weekday"
              rows={d.weekdays.map((w) => ({
                label: WEEKDAYS[w.weekday - 1],
                value: w.count,
                title: `${w.count} commits`,
              }))}
            />
          )
        }
      </QueryState>
    </section>
  );
}

function RiskBreakdown({ risk }) {
  if (!risk) {
    return (
      <p className="muted">No risk score yet. It is computed after the first successful sync.</p>
    );
  }
  return (
    <div className="stack" style={{ gap: 20 }}>
      <Meters
        rows={COMPONENTS.map(([key, name]) => ({
          name,
          value: risk.components?.[key],
          text: percent(risk.components?.[key]),
        }))}
      />
      <p className="muted small">
        0% is healthy, 100% is worst. The score is a weighted heuristic from these four signals
        (computed {date(risk.computedAt)}), not a certainty.
      </p>
    </div>
  );
}

function Model({ name, title, model, metric, train }) {
  const running = train.isPending && train.variables === name;
  const failed = train.isError && train.variables === name ? train.error : null;
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3>{title}</h3>
        <button
          type="button"
          className="btn btn-small"
          disabled={!model.ready || train.isPending}
          onClick={() => train.mutate(name)}
        >
          {running ? 'Training' : model.trained ? 'Retrain' : 'Train'}
        </button>
      </div>
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
      {!model.trained && model.need > 0 && (
        <Meters
          rows={[
            {
              name: 'Training data',
              value: model.have / model.need,
              text: `${Math.min(model.have, model.need)}/${model.need}`,
              good: true,
            },
          ]}
        />
      )}
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
    <div className="stack" style={{ gap: 20 }}>
      <p className="visually-hidden" aria-live="polite">
        {train.isPending ? 'Training model' : train.isSuccess ? 'Training finished' : ''}
      </p>
      <Model
        name="label"
        title="Issue label model"
        model={models.label}
        metric={
          models.label.trained
            ? `Macro-F1 ${models.label.macroF1.toFixed(2)} on held-out issues (how well it labels all three kinds alike; 1.00 is perfect).`
            : ''
        }
        train={train}
      />
      <Model
        name="merge-time"
        title="Merge-time model"
        model={models.mergeTime}
        metric={
          models.mergeTime.trained
            ? `Estimates are off by ${models.mergeTime.maeDays.toFixed(1)} days on average (mean absolute error).`
            : ''
        }
        train={train}
      />
      <p className="muted small">
        {models.chunks} pieces of README, docs and issue text are indexed for duplicate search and
        suggested answers.
      </p>
    </div>
  );
}

function Contributors({ query }) {
  return (
    <section className="card" aria-labelledby="contrib-h">
      <h2 id="contrib-h" className="card-title">
        Top contributors
      </h2>
      <QueryState
        query={query}
        skeleton={200}
        isEmpty={(d) => d.contributors.length === 0}
        empty="No contributors with default-branch commits in the last 12 months."
      >
        {(d) => (
          <>
            <ol className="rank-list">
              {d.contributors.map((c, i) => (
                <li key={c.login}>
                  <span className="avatar" aria-hidden="true">
                    {c.login.slice(0, 1).toUpperCase()}
                  </span>
                  <span className="rank-main">
                    <strong>{c.login}</strong>
                    <span className="muted small">
                      {c.commits} commits · {percent(c.share)} of the total
                    </span>
                  </span>
                  <span className="rank-no">
                    <span className="visually-hidden">Rank </span>
                    {i + 1}
                  </span>
                </li>
              ))}
            </ol>
            <p className="muted small" style={{ marginTop: 12 }}>
              The bus factor is the fewest contributors covering half of the {d.totalCommits}{' '}
              default-branch commits in the last 12 months. 1 or 2 means the project depends on very
              few people.
            </p>
          </>
        )}
      </QueryState>
    </section>
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
      <PageHeader
        title={repo?.fullName ?? 'Repository'}
        meta={
          repo && (
            <span aria-live="polite">
              <SyncStatus
                status={repo.syncStatus}
                error={repo.syncError}
                lastSyncedAt={repo.lastSyncedAt}
              />
            </span>
          )
        }
        actions={
          <button
            type="button"
            className="btn btn-primary"
            disabled={!repo || syncing || sync.isPending}
            onClick={() => sync.mutate(undefined)}
          >
            <IconSync />
            {syncing ? 'Syncing' : 'Sync now'}
          </button>
        }
      />
      {sync.isError && (
        <p className="error-text small" role="alert" style={{ marginBottom: 16 }}>
          {sync.error.code === 'SYNC_ALREADY_RUNNING'
            ? 'A sync is already running.'
            : errorMessage(sync.error)}
        </p>
      )}
      <div className="dash">
        <div className="dash-row dash-top">
          <QueryState query={dashboard} skeleton={316}>
            {(d) => (
              <dl className="kpis">
                <RiskKpi risk={d.risk} />
                <Kpi label="Open issues">
                  <span className="kpi-value">{d.stats.openIssues}</span>
                </Kpi>
                <Kpi label="Closed (7 days)">
                  <span className="kpi-value">{d.stats.closedIssues7d}</span>
                </Kpi>
                <Kpi label="Open pull requests">
                  <span className="kpi-value">{d.stats.openPrs}</span>
                </Kpi>
                <Kpi label="Possible duplicates (7 days)">
                  <span className="kpi-value">{d.stats.duplicatesFlagged7d}</span>
                </Kpi>
                <BusFactorKpi query={busFactor} />
              </dl>
            )}
          </QueryState>
          <Activity query={heatmap} />
        </div>
        <div className="dash-row">
          <section className="card" aria-labelledby="risk-h">
            <h2 id="risk-h" className="card-title">
              Risk breakdown
            </h2>
            <QueryState query={dashboard} skeleton={180}>
              {(d) => <RiskBreakdown risk={d.risk} />}
            </QueryState>
          </section>
          <section className="card" aria-labelledby="models-h">
            <h2 id="models-h" className="card-title">
              Models
            </h2>
            <QueryState query={dashboard} skeleton={180}>
              {(d) => <ModelPanel models={d.models} mlStatus={d.mlStatus} repoId={repoId} />}
            </QueryState>
          </section>
        </div>
        <div className="dash-row dash-row-start">
          <Contributors query={busFactor} />
          {/* The activity card already reports heatmap errors; the calendar shows only data. */}
          {heatmap.isSuccess && heatmap.data.total > 0 && (
            <section className="card" aria-labelledby="calendar-h">
              <h2 id="calendar-h" className="card-title">
                Commit calendar
              </h2>
              <Heatmap data={heatmap.data} />
            </section>
          )}
        </div>
      </div>
    </>
  );
}

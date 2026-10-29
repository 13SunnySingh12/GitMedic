import { Link, useParams, useSearchParams } from 'react-router';
import Markdown from 'react-markdown';
import { useGenerateReport, useReport, useReports } from '../api/hooks.js';

import { IconDownload, IconPlus } from '../components/Icons.jsx';
import { PageHeader } from '../components/Layout.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { QueryState, RiskBadge, errorMessage } from '../components/Status.jsx';
import { date, dateTime } from '../utils/format.js';

/** The next Monday 06:00 local time: when the default REPORT_CRON first runs. */
export function nextReportDate(now = new Date()) {
  const d = new Date(now);
  d.setDate(d.getDate() + ((8 - d.getDay()) % 7 || 7));
  d.setHours(6, 0, 0, 0);
  return d;
}

export function Reports() {
  const { repoId } = useParams();
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const reports = useReports(repoId, { page, size: 20 });
  const generate = useGenerateReport(repoId);

  return (
    <>
      <PageHeader
        title="Weekly reports"
        meta="One report per week, generated on schedule while the GitMedic server is running."
        actions={
          <button
            type="button"
            className="btn btn-primary"
            disabled={generate.isPending}
            onClick={() => generate.mutate()}
          >
            <IconPlus />
            {generate.isPending ? 'Generating' : 'Generate now'}
          </button>
        }
      />
      <p aria-live="polite" className="small" style={{ minHeight: '1.5em', marginBottom: 8 }}>
        {generate.isSuccess && 'Report for the current week generated.'}
        {generate.isError && <span className="error-text">{errorMessage(generate.error)}</span>}
      </p>
      <QueryState
        query={reports}
        skeleton={200}
        isEmpty={(d) => d.totalItems === 0}
        empty={`The first report will be generated on ${dateTime(nextReportDate().toISOString())}, or use Generate now.`}
      >
        {(data) => (
          <>
            <div className="card table-card">
              <table className="stacked">
                <caption className="visually-hidden">Report history</caption>
                <thead>
                  <tr>
                    <th scope="col">Period</th>
                    <th scope="col">Risk</th>
                    <th scope="col">Generated</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr key={r.id}>
                      <td className="title-cell">
                        <Link to={`/repos/${repoId}/reports/${r.id}`}>
                          {date(r.periodStart, r.timezone)} to {date(r.periodEnd, r.timezone)}
                        </Link>
                      </td>
                      <td data-label="Risk">
                        <RiskBadge
                          risk={
                            r.riskScore == null ? null : { score: r.riskScore, level: r.riskLevel }
                          }
                        />
                      </td>
                      <td data-label="Generated">{dateTime(r.generatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              {...data}
              onPage={(n) => setParams(new URLSearchParams({ page: String(n) }))}
              label={['report', 'reports']}
            />
          </>
        )}
      </QueryState>
    </>
  );
}

export function ReportDetail() {
  const { repoId, reportId } = useParams();
  const report = useReport(reportId);
  const base = `/api/v1/reports/${reportId}/download`;
  return (
    <>
      <PageHeader
        crumb={<Link to={`/repos/${repoId}/reports`}>Back to reports</Link>}
        title="Weekly report"
        meta={report.data && `Generated ${dateTime(report.data.generatedAt)}`}
        actions={
          report.data && (
            <>
              <a className="btn btn-ghost" href={`${base}?format=md`} download>
                <IconDownload />
                Download .md
              </a>
              <a className="btn btn-ghost" href={`${base}?format=html`} download>
                <IconDownload />
                Download .html
              </a>
            </>
          )
        }
      />
      <QueryState query={report} skeleton={320}>
        {(r) => (
          <article className="card">
            {/* Rendered from stored metrics on the server; react-markdown escapes any HTML (SEC-10). */}
            <div className="prose">
              <Markdown components={{ h1: 'h2', h2: 'h3' }}>{r.markdown}</Markdown>
            </div>
          </article>
        )}
      </QueryState>
    </>
  );
}

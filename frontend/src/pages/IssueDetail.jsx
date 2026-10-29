import { useState } from 'react';
import { Link, useParams } from 'react-router';
import Markdown from 'react-markdown';
import { useIssue } from '../api/hooks.js';
import { GitHubLink, PageHeader } from '../components/Layout.jsx';
import { Prediction, QueryState } from '../components/Status.jsx';
import { date } from '../utils/format.js';

function Duplicates({ items }) {
  if (items.length === 0) {
    return <p className="muted">No similar issues found.</p>;
  }
  return (
    <ol className="rank-list">
      {items.map((d) => (
        <li key={d.number}>
          <span className="rank-main">
            <strong>
              <GitHubLink href={d.htmlUrl}>
                #{d.number} {d.title}
              </GitHubLink>
            </strong>
            <span className="muted small">
              {d.state}, similarity {d.score.toFixed(2)} (0 to 1; higher means more alike)
            </span>
          </span>
          <span className="badge">{d.score.toFixed(2)}</span>
        </li>
      ))}
    </ol>
  );
}

function SuggestedAnswer({ answer }) {
  const [copied, setCopied] = useState('');
  async function copy() {
    try {
      await navigator.clipboard.writeText(answer.draft);
      setCopied('Draft copied to the clipboard.');
    } catch {
      setCopied('Copy failed. Select the text and copy it manually.');
    }
  }
  return (
    <div className="stack">
      <p className="muted small">
        A draft assembled from closed issues and documentation that look related. It is a
        suggestion, not a verified answer.
      </p>
      {/* react-markdown never renders raw HTML (SEC-10). */}
      <div className="prose">
        <Markdown disallowedElements={['img']} unwrapDisallowed>
          {answer.draft}
        </Markdown>
      </div>
      <div className="row">
        <button type="button" className="btn btn-small" onClick={copy}>
          Copy draft
        </button>
        <span className="muted small" aria-live="polite">
          {copied}
        </span>
      </div>
      {answer.sources.length > 0 && (
        <>
          <h3>Sources</h3>
          <ul className="sources">
            {answer.sources.map((s) => (
              <li key={`${s.type}-${s.ref}`}>
                <GitHubLink href={s.url}>
                  {s.type === 'issue' ? `Closed issue #${s.ref}` : s.ref}
                </GitHubLink>
                <div className="muted small">{s.snippet}</div>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

export default function IssueDetail() {
  const { repoId, number } = useParams();
  const issue = useIssue(repoId, number);

  return (
    <>
      {!issue.isSuccess && (
        <p className="crumb" style={{ marginBottom: 16 }}>
          <Link to={`/repos/${repoId}/issues`}>Back to issues</Link>
        </p>
      )}
      <QueryState query={issue} skeleton={240}>
        {(d) => (
          <>
            <PageHeader
              crumb={<Link to={`/repos/${repoId}/issues`}>Back to issues</Link>}
              title={
                <>
                  {d.issue.title} <span className="muted">#{d.issue.number}</span>
                </>
              }
              meta={
                <span className="row">
                  <span className="badge">{d.issue.state}</span>
                  <span>
                    Opened {date(d.issue.createdAt)}
                    {d.issue.authorLogin ? ` by ${d.issue.authorLogin}` : ''}
                  </span>
                  <GitHubLink href={d.issue.htmlUrl}>View on GitHub</GitHubLink>
                </span>
              }
            />
            <div className="grid-2">
              <section className="card span-all" aria-labelledby="labels-h">
                <h2 id="labels-h" className="card-title">
                  Labels
                </h2>
                <div className="row" style={{ gap: '10px 32px' }}>
                  <span className="row" style={{ gap: 8 }}>
                    On GitHub:{' '}
                    {d.issue.ghLabels.length ? (
                      d.issue.ghLabels.map((l) => (
                        <span key={l} className="badge">
                          {l}
                        </span>
                      ))
                    ) : (
                      <span className="muted">none</span>
                    )}
                  </span>
                  <span className="row" style={{ gap: 8 }}>
                    Model:{' '}
                    <Prediction
                      label={d.issue.predictedLabel}
                      confidence={d.issue.predictedConfidence}
                    />
                  </span>
                </div>
              </section>
              {d.mlStatus === 'UNAVAILABLE' ? (
                <p className="notice notice-warn span-all">
                  The ML service is unavailable, so similar issues and a suggested answer cannot be
                  shown right now.
                </p>
              ) : (
                <>
                  <section className="card" aria-labelledby="dups-h">
                    <h2 id="dups-h" className="card-title">
                      Possible duplicates
                    </h2>
                    <Duplicates items={d.duplicates} />
                  </section>
                  <section className="card" aria-labelledby="answer-h">
                    <h2 id="answer-h" className="card-title">
                      Suggested answer
                    </h2>
                    <SuggestedAnswer answer={d.suggestedAnswer} />
                  </section>
                </>
              )}
            </div>
          </>
        )}
      </QueryState>
    </>
  );
}

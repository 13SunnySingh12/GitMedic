import { useState } from 'react';
import { Link, useParams } from 'react-router';
import Markdown from 'react-markdown';
import { useIssue } from '../api/hooks.js';
import { GitHubLink, RepoNav } from '../components/Layout.jsx';
import { Prediction, QueryState } from '../components/Status.jsx';
import { date } from '../utils/format.js';

function Duplicates({ items }) {
  if (items.length === 0) {
    return <p className="muted">No similar issues found.</p>;
  }
  return (
    <ul className="sources">
      {items.map((d) => (
        <li key={d.number}>
          <GitHubLink href={d.htmlUrl}>
            #{d.number} {d.title}
          </GitHubLink>
          <div className="muted small">
            {d.state}, similarity {d.score.toFixed(2)}
          </div>
        </li>
      ))}
    </ul>
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
        <Markdown>{answer.draft}</Markdown>
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
      <p className="small" style={{ marginBottom: 8 }}>
        <Link to={`/repos/${repoId}/issues`}>Back to issues</Link>
      </p>
      <QueryState query={issue} skeleton={240}>
        {(d) => (
          <>
            <div className="page-head">
              <div className="stack" style={{ gap: 6 }}>
                <h1>
                  {d.issue.title} <span className="muted">#{d.issue.number}</span>
                </h1>
                <div className="row small">
                  <span className="badge">{d.issue.state}</span>
                  <span className="muted">
                    Opened {date(d.issue.createdAt)}
                    {d.issue.authorLogin ? ` by ${d.issue.authorLogin}` : ''}
                  </span>
                  <GitHubLink href={d.issue.htmlUrl}>View on GitHub</GitHubLink>
                </div>
              </div>
            </div>
            <RepoNav />
            <div className="grid grid-dash">
              <section className="panel span-12" aria-labelledby="labels-h">
                <h2 id="labels-h">Labels</h2>
                <div className="row">
                  <span>
                    On GitHub:{' '}
                    {d.issue.ghLabels.length ? (
                      d.issue.ghLabels.join(', ')
                    ) : (
                      <span className="muted">none</span>
                    )}
                  </span>
                  <span>
                    Model:{' '}
                    <Prediction
                      label={d.issue.predictedLabel}
                      confidence={d.issue.predictedConfidence}
                    />
                  </span>
                </div>
              </section>
              {d.mlStatus === 'UNAVAILABLE' ? (
                <p className="notice notice-warn span-12">
                  The ML service is unavailable, so similar issues and a suggested answer cannot be
                  shown right now.
                </p>
              ) : (
                <>
                  <section className="panel span-6" aria-labelledby="dups-h">
                    <h2 id="dups-h">Possible duplicates</h2>
                    <Duplicates items={d.duplicates} />
                  </section>
                  <section className="panel span-6" aria-labelledby="answer-h">
                    <h2 id="answer-h">Suggested answer</h2>
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

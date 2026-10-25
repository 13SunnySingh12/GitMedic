import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';
import { RiskBadge } from '../components/Status.jsx';
import { calendarDays } from '../components/Heatmap.jsx';
import { safeNext } from '../api/client.js';
import { nextReportDate } from '../pages/Reports.jsx';
import { server } from './server.js';
import { currentLocation, expectNoAxeViolations, page, problem, renderApp } from './utils.jsx';

const REPO = {
  id: 7,
  fullName: 'octo/demo',
  isPrivate: false,
  syncStatus: 'DONE',
  syncError: null,
  lastSyncedAt: '2026-10-24T06:00:00Z',
  risk: { score: 72, level: 'HIGH', computedAt: '2026-10-24T06:00:00Z' },
};

describe('QA-UI-01 risk badge', () => {
  test('shows level text and score, not colour alone, with no axe violations', async () => {
    const { container } = render(<RiskBadge risk={{ score: 72, level: 'HIGH' }} />);
    expect(screen.getByText(/High risk/)).toHaveTextContent('High risk · 72');
    await expectNoAxeViolations(container);
  });

  test('unscored repos say so', () => {
    render(<RiskBadge risk={null} />);
    expect(screen.getByText('Risk not scored')).toBeInTheDocument();
  });
});

describe('auth', () => {
  test('a 401 from /me goes to /login with the requested path as next', async () => {
    server.use(http.get('/api/v1/me', () => problem(401, 'UNAUTHENTICATED')));
    renderApp('/repos/7/issues?state=closed', { signedIn: false });
    expect(await screen.findByRole('link', { name: 'Sign in with GitHub' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/github',
    );
    expect(currentLocation().pathname).toBe('/login');
    expect(new URLSearchParams(currentLocation().search).get('next')).toBe(
      '/repos/7/issues?state=closed',
    );
  });

  test('login shows the OAuth error from the query string', async () => {
    renderApp('/login?error=oauth_failed', { signedIn: false });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /cancelled or could not be verified/,
    );
  });

  test('only same-app paths are accepted as next', () => {
    expect(safeNext('/repos/1')).toBe('/repos/1');
    expect(safeNext('//evil.example')).toBeNull();
    expect(safeNext('https://evil.example')).toBeNull();
    expect(safeNext(null)).toBeNull();
  });
});

describe('QA-UI-02 repo list states', () => {
  test('empty state invites tracking the first repository', async () => {
    server.use(http.get('/api/v1/repos', () => HttpResponse.json([])));
    renderApp('/repos');
    expect(await screen.findByText(/Track your first repository/)).toBeInTheDocument();
  });

  test('error state offers a retry', async () => {
    server.use(
      http.get('/api/v1/repos', () => problem(500, 'INTERNAL_ERROR', 'Unexpected error.')),
    );
    renderApp('/repos');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Unexpected error.');
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  test('lists repos with risk, sync status and a failed reason', async () => {
    server.use(
      http.get('/api/v1/repos', () =>
        HttpResponse.json([
          REPO,
          {
            ...REPO,
            id: 8,
            fullName: 'octo/broken',
            syncStatus: 'FAILED',
            syncError: 'GITHUB_NOT_FOUND: gone',
            risk: null,
          },
        ]),
      ),
    );
    const { container } = renderApp('/repos');
    expect(await screen.findByRole('link', { name: 'octo/demo' })).toHaveAttribute(
      'href',
      '/repos/7',
    );
    expect(screen.getByText('High risk · 72')).toBeInTheDocument();
    expect(screen.getByText('GITHUB_NOT_FOUND: gone')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry sync' })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  test('sync now sends the CSRF header (SEC-05)', async () => {
    let header = null;
    server.use(
      http.get('/api/v1/repos', () => HttpResponse.json([REPO])),
      http.post('/api/v1/repos/7/sync', ({ request }) => {
        header = request.headers.get('X-Requested-With');
        return HttpResponse.json({ id: 7, syncStatus: 'PENDING' }, { status: 202 });
      }),
    );
    renderApp('/repos');
    await userEvent.click(await screen.findByRole('button', { name: 'Sync now' }));
    await waitFor(() => expect(header).toBe('GitMedic'));
  });

  test('untrack asks for confirmation first (UX-05)', async () => {
    let deleted = false;
    server.use(
      http.get('/api/v1/repos', () => HttpResponse.json(deleted ? [] : [REPO])),
      http.delete('/api/v1/repos/7', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp('/repos');
    await userEvent.click(await screen.findByRole('button', { name: 'Untrack' }));
    expect(deleted).toBe(false);
    expect(screen.getByText(/will delete everything it stored/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Stop tracking' }));
    await waitFor(() => expect(deleted).toBe(true));
    expect(await screen.findByText(/Track your first repository/)).toBeInTheDocument();
  });
});

describe('issues', () => {
  const ISSUE = {
    number: 88,
    title: '<script>alert(1)</script> Crash on save',
    state: 'open',
    htmlUrl: 'https://github.com/octo/demo/issues/88',
    authorLogin: 'erin',
    ghLabels: [],
    commentsCount: 0,
    createdAt: '2026-10-20T10:00:00Z',
    predictedLabel: 'bug',
    predictedConfidence: 0.83,
    isPossibleDuplicate: true,
    duplicateScore: 0.87,
  };

  test('filters are read from and written to the URL (UX-07), predictions labelled (UX-02)', async () => {
    const seen = [];
    server.use(
      http.get('/api/v1/repos/7/issues', ({ request }) => {
        seen.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json(page([ISSUE], { size: 25, totalItems: 30 }));
      }),
      http.get('/api/v1/repos/7/dashboard', () => problem(404, 'NOT_FOUND')),
    );
    renderApp('/repos/7/issues?state=closed&label=bug');
    // QA-SEC-09 (UI): issue text is rendered as text, never as markup.
    expect(await screen.findByText('<script>alert(1)</script> Crash on save')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
    expect(seen[0]).toContain('state=closed');
    expect(seen[0]).toContain('label=bug');
    expect(screen.getByText(/predicted, 83%/)).toBeInTheDocument();
    expect(screen.getByText('Possible duplicate · 0.87')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('checkbox', { name: 'Possible duplicates only' }));
    await waitFor(() => expect(currentLocation().search).toContain('duplicate=true'));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(currentLocation().search).toContain('page=1'));
    expect(seen.at(-1)).toContain('page=1');
  });

  test('issue detail shows duplicates, a sourced draft, and GitHub links in a new tab', async () => {
    server.use(
      http.get('/api/v1/repos/7/issues/88', () =>
        HttpResponse.json({
          issue: { ...ISSUE, title: 'Crash on save' },
          body: 'It crashes',
          duplicates: [
            {
              number: 41,
              title: 'App crashes when saving',
              state: 'closed',
              score: 0.87,
              htmlUrl: 'https://github.com/octo/demo/issues/41',
            },
          ],
          suggestedAnswer: {
            mode: 'RETRIEVAL_ONLY',
            llmStatus: 'DISABLED',
            draft: 'Related: closed issue #41. <img src=x onerror=alert(1)>',
            sources: [
              {
                type: 'doc',
                ref: 'docs/faq.md#saving',
                url: 'https://github.com/octo/demo/blob/main/docs/faq.md#saving',
                snippet: 'Upgrade to 2.3',
                score: 0.7,
              },
            ],
          },
          mlStatus: 'OK',
        }),
      ),
    );
    const { container } = renderApp('/repos/7/issues/88');
    const dup = await screen.findByRole('link', { name: /#41 App crashes when saving/ });
    expect(dup).toHaveAttribute('target', '_blank');
    expect(dup).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('link', { name: /docs\/faq.md#saving/ })).toHaveAttribute(
      'href',
      'https://github.com/octo/demo/blob/main/docs/faq.md#saving',
    );
    expect(container.querySelector('img')).toBeNull(); // react-markdown left raw HTML as text
    expect(screen.getByRole('button', { name: 'Copy draft' })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  test('issue detail degrades when ML is unavailable (NFR-008)', async () => {
    server.use(
      http.get('/api/v1/repos/7/issues/88', () =>
        HttpResponse.json({
          issue: ISSUE,
          body: null,
          duplicates: null,
          suggestedAnswer: null,
          mlStatus: 'UNAVAILABLE',
        }),
      ),
    );
    renderApp('/repos/7/issues/88');
    expect(await screen.findByText(/ML service is unavailable/)).toBeInTheDocument();
  });
});

describe('dashboard', () => {
  test('widgets render independently: heatmap error does not hide risk; ML down is explained', async () => {
    server.use(
      http.get('/api/v1/repos/7/dashboard', () =>
        HttpResponse.json({
          repo: {
            id: 7,
            fullName: 'octo/demo',
            lastSyncedAt: '2026-10-24T06:00:00Z',
            syncStatus: 'DONE',
          },
          risk: {
            score: 72,
            level: 'HIGH',
            delta: 8,
            computedAt: '2026-10-24T06:00:00Z',
            components: { daysSinceCommit: 0.9 },
          },
          stats: { openIssues: 41, closedIssues7d: 3, openPrs: 5, duplicatesFlagged7d: 2 },
          models: null,
          mlStatus: 'UNAVAILABLE',
        }),
      ),
      http.get('/api/v1/repos/7/heatmap', () => problem(500, 'INTERNAL_ERROR', 'Heatmap failed.')),
      http.get('/api/v1/repos/7/bus-factor', () =>
        HttpResponse.json({
          busFactor: null,
          risky: false,
          totalCommits: 0,
          windowStart: '2025-10-24T00:00:00Z',
          contributors: [],
        }),
      ),
    );
    renderApp('/repos/7');
    expect(
      await screen.findByText('+8 vs. a week ago', { exact: false }, { timeout: 15000 }),
    ).toBeInTheDocument();
    expect(screen.getByText('High risk · 72')).toBeInTheDocument();
    expect(screen.getByText('41')).toBeInTheDocument();
    expect(await screen.findByText('Heatmap failed.')).toBeInTheDocument();
    expect(screen.getByText(/ML service is unavailable/)).toBeInTheDocument();
    expect(screen.getByText(/No commits in the last 12 months/)).toBeInTheDocument();
  });

  test('model panel shows metrics and how much data is missing', async () => {
    server.use(
      http.get('/api/v1/repos/7/dashboard', () =>
        HttpResponse.json({
          repo: { id: 7, fullName: 'octo/demo', lastSyncedAt: null, syncStatus: 'DONE' },
          risk: null,
          stats: { openIssues: 0, closedIssues7d: 0, openPrs: 0, duplicatesFlagged7d: 0 },
          models: {
            label: {
              trained: true,
              trainedAt: '2026-10-20T00:00:00Z',
              macroF1: 0.71,
              samples: 220,
              have: 220,
              need: 30,
              ready: true,
            },
            mergeTime: {
              trained: false,
              reason: 'INSUFFICIENT_TRAINING_DATA',
              have: 12,
              need: 20,
              ready: false,
            },
            chunks: 130,
          },
          mlStatus: 'OK',
        }),
      ),
      http.get('/api/v1/repos/7/heatmap', () =>
        HttpResponse.json({
          from: '2026-10-19',
          to: '2026-10-25',
          timezone: 'UTC',
          total: 3,
          days: [{ date: '2026-10-20', count: 3 }],
          weekdays: [1, 2, 3, 4, 5, 6, 7].map((weekday) => ({
            weekday,
            count: weekday === 2 ? 3 : 0,
          })),
        }),
      ),
      http.get('/api/v1/repos/7/bus-factor', () =>
        HttpResponse.json({
          busFactor: 1,
          risky: true,
          totalCommits: 3,
          windowStart: '2025-10-24T00:00:00Z',
          contributors: [{ login: 'alice', commits: 3, share: 1 }],
        }),
      ),
    );
    renderApp('/repos/7');
    expect(await screen.findByText(/Macro-F1 0.71/, {}, { timeout: 15000 })).toBeInTheDocument();
    expect(screen.getByText('Not enough data yet: 12 of 20 needed.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Train' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Retrain' })).toBeEnabled();
    expect(await screen.findByRole('img', { name: '2026-10-20: 3 commits' })).toBeInTheDocument();
    expect(screen.getByText('Risky')).toBeInTheDocument();
  });
});

describe('reports', () => {
  test('empty history names the first scheduled date; generate now posts', async () => {
    let posted = false;
    server.use(
      http.get('/api/v1/repos/7/reports', () => HttpResponse.json(page([]))),
      http.post('/api/v1/repos/7/reports', () => {
        posted = true;
        return HttpResponse.json({ id: 1 }, { status: 201 });
      }),
    );
    renderApp('/repos/7/reports');
    expect(await screen.findByText(/The first report will be generated on/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Generate now' }));
    await waitFor(() => expect(posted).toBe(true));
  });

  test('report periods are shown in the report timezone, not the browser one', async () => {
    server.use(
      http.get('/api/v1/repos/7/reports', () =>
        HttpResponse.json(
          page([
            {
              id: 1,
              periodStart: '2026-10-12T00:00:00Z',
              periodEnd: '2026-10-18T23:59:59.999999Z',
              generatedAt: '2026-10-19T06:00:00Z',
              riskScore: 17,
              riskLevel: 'LOW',
              timezone: 'UTC',
            },
          ]),
        ),
      ),
    );
    renderApp('/repos/7/reports');
    const link = await screen.findByRole('link', { name: /Oct/ });
    expect(link.textContent).toMatch(/12.*to.*18/);
    expect(screen.getByText(/\(1 report\)/)).toBeInTheDocument();
  });

  test('report detail renders markdown and offers both downloads', async () => {
    server.use(
      http.get('/api/v1/reports/3', () =>
        HttpResponse.json({
          id: 3,
          generatedAt: '2026-10-20T06:00:00Z',
          markdown: '# Weekly health report: octo/demo\n\n- Score: 40',
        }),
      ),
    );
    renderApp('/repos/7/reports/3');
    expect(
      await screen.findByRole('heading', { name: /Weekly health report/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Download .md' })).toHaveAttribute(
      'href',
      '/api/v1/reports/3/download?format=md',
    );
    expect(screen.getByRole('link', { name: 'Download .html' })).toHaveAttribute(
      'href',
      '/api/v1/reports/3/download?format=html',
    );
  });

  test('next report date is the coming Monday 06:00', () => {
    const d = nextReportDate(new Date(2026, 9, 25, 12)); // Sunday 25 Oct 2026
    expect([d.getDay(), d.getDate(), d.getHours()]).toEqual([1, 26, 6]);
    const fromMonday = nextReportDate(new Date(2026, 9, 26, 12));
    expect(fromMonday.getDate()).toBe(2); // the following Monday
  });
});

describe('heatmap grid', () => {
  test('pads to Monday and covers every day', () => {
    const days = calendarDays('2026-10-21', '2026-10-27'); // Wednesday to Tuesday
    expect(days.slice(0, 2)).toEqual([null, null]);
    expect(days.filter(Boolean)).toHaveLength(7);
  });
});

test('unknown routes show a way back', async () => {
  renderApp('/nope');
  expect(await screen.findByRole('link', { name: 'Go to your repositories' })).toHaveAttribute(
    'href',
    '/repos',
  );
});

# GitMedic — Complete Project Specification

> AI-powered GitHub repository health & issue assistant. Single source of truth: PRD + TRD + UI/UX + Security + QA + Roadmap + Local Dev/CI.

## Contents
1. Project Overview · 2. PRD · 3. User Flows & App Structure · 4. UI/UX · 5. TRD · 6. Database & Data · 7. API Contracts · 8. Security Plan · 9. Testing & QA · 10. Roadmap · 11. Local Dev, CI/CD, Maintenance · 12. Risks, Open Questions, Change History · Appendix A: Completeness Checklist

---

## 1. Project Overview

### 1.1 Summary
| Item | Value |
|---|---|
| Name | GitMedic [C] |
| One-liner | Connects to a maintainer's GitHub repos, flags duplicate issues, predicts issue labels, predicts PR merge time, scores abandonment risk, and produces a weekly health report. [C] |
| Problem | Maintainers are buried in duplicate issues and cannot tell which repos are at risk. [C] |
| Solution | Sync GitHub data → classical ML + embedding retrieval → dashboards + weekly report. [C] |
| Delivery | Local-only; CI validation only. [C] |

### 1.2 Personas [A]
| ID | Persona | Need |
|---|---|---|
| PER-1 | Solo OSS maintainer (1–10 repos) | Triage faster, spot duplicates, see which repo is going stale |
| PER-2 | Student/portfolio developer | Understand repo health of own projects; demo ML + RAG end-to-end |

Single role only: authenticated **Maintainer** (no teams/multi-tenant — OOS [C]).

### 1.3 Objectives & success criteria
| ID | Objective | Measurable criterion |
|---|---|---|
| OBJ-1 | Surface duplicates | On a seeded fixture set with known duplicate pairs, ≥ 80% of true duplicates appear in top-5 matches (QA-ML-04) [P] |
| OBJ-2 | Auto-label issues | Classifier reports macro-F1 on held-out split; predictions shown with confidence for every open issue of a trained repo |
| OBJ-3 | Predict merge time | Merge-time MAE (days) reported after training; prediction shown for every open PR of a trained repo |
| OBJ-4 | Risk visibility | Every tracked repo shows a 0–100 risk score + Low/Medium/High label + week-over-week delta |
| OBJ-5 | Weekly reporting | One report per tracked repo per week, viewable and downloadable as `.md`/`.html` |
| OBJ-6 | Quality | CI green on `main`: lint, tests, build, dependency checks across all 3 services |

### 1.4 Scope
**In scope [C]:** GitHub OAuth login · repo selection · initial sync (repos, issues, PRs, commits) · webhooks (`issues`, `pull_request`, `push`) · label classifier · PR merge-time predictor · abandonment risk score · RAG over README/docs/closed issues · duplicate detection · suggested answer · contributor heatmap · bus factor · weekly report (scheduler, history, md/html download) · 7 React pages · GitHub Actions CI (build/lint/test).

**Explicitly excluded [C]:** deployment/hosting · real-time WebSockets · multi-tenant/team features · deep-learning model training.
**Additionally excluded [P]:** writing labels/comments back to GitHub · GitHub Enterprise Server · email/Slack notifications · mobile apps · paid services of any kind.

### 1.5 Constraints, dependencies, limitations
| ID | Item | Type |
|---|---|---|
| CON-1 | Free tools / free tiers only; no paid fallback | Constraint [C] |
| CON-2 | No deployment; runs on developer machine | Constraint [C] |
| CON-3 | Stack fixed: React (Vite, JavaScript), Spring Boot, FastAPI, Supabase Postgres + pgvector, scikit-learn, sentence-transformers `all-MiniLM-L6-v2`, GitHub Actions | Constraint [C] |
| CON-4 | React talks only to Spring Boot; FastAPI never talks to GitHub | Constraint [C] |
| DEP-1 | GitHub REST API + OAuth; authenticated user tokens are rate-limited (5,000 req/h per GitHub docs — re-verify) | Dependency |
| DEP-2 | Supabase Free: 500 MB DB per project, max 2 active projects, **auto-pause after 1 week of inactivity** [C, supabase.com/pricing, checked 2026-10] | Dependency/limitation |
| LIM-1 | Webhooks need a reachable URL; without deployment they only arrive via `gh webhook forward` while the dev machine runs (§5.9) | Limitation |
| LIM-2 | Weekly scheduler runs only while Spring Boot runs locally (catch-up rule, BR-12) | Limitation |
| LIM-3 | Per-repo ML needs enough history; small repos get "insufficient data" (BR-07, BR-08) | Limitation |

### 1.6 MVP and completion
- **MVP = all P0 requirements** (§2.4): login, repo tracking, initial + manual sync, issues page, label classifier, embeddings + duplicate detection, risk score, dashboard, weekly report, CI.
- **Project complete = P0 + P1** (all confirmed features in `GitMedic.md`) + Definition of Done (§9.6).
- **P2 = optional**, never blocks completion.

### 1.7 Future enhancements (not required)
FE-1 Write predicted labels back to GitHub (needs write scope) · FE-2 LLM-polished answers via Gemini as alternative provider · FE-3 GitHub App instead of OAuth App (fine-grained permissions) · FE-4 Cross-repo duplicate search · FE-5 Notifications (email) · FE-6 Model explainability (feature importances in UI).

---

## 2. PRD — Product Requirements Document

### 2.1 Goals
G-1 Reduce triage time (duplicates + labels). G-2 Early warning for stagnating repos (risk + bus factor). G-3 Weekly digest without manual work. G-4 Portfolio-grade, test-backed engineering.

### 2.2 Roles & permissions
| Role | Permissions |
|---|---|
| Anonymous | Login page; `/api/v1/webhooks/github` (signature-verified, no session) |
| Maintainer (authenticated) | Full access to **own** tracked repos and derived data only (BR-01) |
| ML service (internal) | Called by Spring Boot only, via shared internal token (SEC-07) |

### 2.3 User stories
| ID | Story | FRs |
|---|---|---|
| US-01 | As a maintainer, I sign in with GitHub so I don't manage another password. | FR-001–003 |
| US-02 | I pick which repos to track so GitMedic analyses only what I care about. | FR-004, FR-005 |
| US-03 | I see a risk badge per repo so I know where to look first. | FR-014, FR-023 |
| US-04 | I see possible duplicates on new issues so I can close them quickly. | FR-015, FR-016, FR-025 |
| US-05 | I see a predicted label with confidence so I can label faster. | FR-011, FR-012 |
| US-06 | I see a drafted answer with sources so I can reply faster. | FR-017, FR-018 |
| US-07 | I see estimated merge time for open PRs so I can set expectations. | FR-013 |
| US-08 | I see activity heatmap and bus factor so I understand contributor risk. | FR-009, FR-010 |
| US-09 | I get a weekly report I can download and share. | FR-019–022 |
| US-10 | I retrain models on my repo's own data when history grows. | FR-011, FR-013, FR-026 |
| US-11 | My data stays fresh via webhooks or a manual sync. | FR-006, FR-007, FR-008 |

### 2.4 Functional requirements
| ID | Requirement | Pri | Status | Acceptance criteria |
|---|---|---|---|---|
| FR-001 | Login via GitHub OAuth (authorization-code flow) | P0 | C | Valid flow creates/updates `users` row and lands on Repo List; denied consent returns to Login with message |
| FR-002 | Store GitHub access token encrypted at rest | P0 | C | DB column holds ciphertext only (AES-256-GCM); token never in API responses or logs (QA-SEC-02) |
| FR-003 | App session via JWT in `HttpOnly` cookie; logout clears it | P0 | C (JWT) / P (cookie) | Protected endpoints return 401 without cookie; logout → subsequent calls 401 |
| FR-004 | List user's GitHub repos; track/untrack | P0 (track) / P1 (untrack) | C / P | Track creates `repos` row + starts sync; untrack deletes repo and all derived rows (cascade) |
| FR-005 | Initial sync: repo metadata, issues (excl. PRs), PRs (with size + review count), commits (default branch) | P0 | C | Counts match GitHub fixtures in integration test; sync status transitions `PENDING→RUNNING→DONE|FAILED` |
| FR-006 | Webhook ingestion for `issues`, `pull_request`, `push` with HMAC-SHA256 signature check | P1 | C | Valid signed payload upserts data ≤ 5 s; invalid signature → 401, no write; replay of same delivery is idempotent |
| FR-007 | Manual "Sync now" (incremental, `since=last_synced_at`) | P0 | P | Resolves LIM-1; button disabled while sync running |
| FR-008 | Show sync status + last synced time per repo | P0 | P | Visible on Repo List and Dashboard |
| FR-009 | Activity heatmap: commits per day (calendar grid) + per weekday | P1 | C | Values equal SQL aggregate on fixture data; default range last 52 weeks |
| FR-010 | Bus factor + top-contributors bar chart | P1 | C | Bus factor per BR-06; value 1–2 flagged "risky" |
| FR-011 | Train/retrain issue label classifier per repo (TF-IDF + Logistic Regression) | P0 | C | Training succeeds on fixture; returns macro-F1, sample counts; insufficient data → 422 `INSUFFICIENT_TRAINING_DATA` |
| FR-012 | Predict `bug`/`feature`/`question` + confidence for open issues (on sync, webhook, retrain) | P0 | C | Every open issue of a trained repo has label + confidence ∈ [0,1] |
| FR-013 | Train/predict PR merge time (Random Forest regressor) | P1 | C | Every open PR of a trained repo has `predicted_merge_days ≥ 0`; MAE reported |
| FR-014 | Abandonment risk score 0–100 + Low/Medium/High + history | P0 | C | Score per BR-05 recomputed after each sync and weekly; history row stored |
| FR-015 | Index README, docs files and issues into pgvector | P0 | C (README/docs/closed issues) / P (open issues for dup search) | Re-index is idempotent (content hash); chunk count shown in model status |
| FR-016 | Duplicate detection for new/synced issues: top-5 similar issues with score + link | P0 | C | Score ≥ threshold (BR-03) sets `possible duplicate` tag; self-match excluded |
| FR-017 | Suggested answer (retrieval-only): top closed issues + doc sections as draft with source links | P1 | C | Works with LLM disabled; every source links to GitHub |
| FR-018 | Optional LLM-polished answer from retrieved context only | P2 | C (optional) | Disabled by default; provider failure → retrieval-only response + `llm_status` flag |
| FR-019 | Weekly report scheduler (`@Scheduled`) per tracked repo | P0 | C | Report contents per BR-11; one report per repo per period (unique) |
| FR-020 | Report history list + detail view | P0 | C | Newest first, paginated |
| FR-021 | Download report as `.md` / `.html` | P1 | C | Correct `Content-Type` + `Content-Disposition`; HTML is escaped (SEC-10) |
| FR-022 | "Generate now" for current period | P1 | P | Needed for testing/demo without waiting a week |
| FR-023 | Repo List with risk badge per repo | P0 | C | Badge shows level text + score (not color-only) |
| FR-024 | Repo Dashboard: risk score + delta, stats, heatmap, bus factor | P0 | C | Loads with partial data (each widget has own empty/error state) |
| FR-025 | Issues list: predicted label, duplicate tag, filters (state, label, duplicate), pagination | P0 | C (list/tags) / P (filters) | Filters reflected in URL query |
| FR-026 | Model status per repo: trained?, trained_at, metrics, sample counts; Train/Retrain buttons | P1 | P | Needed for "Train/Retrain endpoint" UX |

### 2.5 Non-functional requirements
| ID | Requirement | Measure |
|---|---|---|
| NFR-001 | Zero mandatory paid services | Dependency audit in §5.11 lists only free/OSS/free-tier items |
| NFR-002 | Local runnability | Fresh clone → running app following §11 only |
| NFR-003 | Performance (local, ≤ 10k issues/repo) [P] | Dashboard/list APIs p95 < 500 ms; duplicate search < 1 s; label prediction < 200 ms/issue |
| NFR-004 | GitHub rate-limit compliance | Reads `X-RateLimit-Remaining/Reset`; pauses sync when remaining < 100; never exceeds limit in tests |
| NFR-005 | Secrets hygiene | No secrets in repo, frontend bundle, logs, CI output (gitleaks in CI) |
| NFR-006 | Accessibility | WCAG 2.2 AA target; keyboard-operable; status not conveyed by color alone |
| NFR-007 | Responsive | Usable from 360 px width to desktop |
| NFR-008 | Graceful degradation | FastAPI/LLM down → core pages still load; AI widgets show "unavailable" |
| NFR-009 | Reproducibility | Pinned dependencies; `random_state=42` for all models |
| NFR-010 | Maintainability | Lint/format enforced in CI; module boundaries per §5.4 |
| NFR-011 | CI validation | Every push/PR runs §11.6 jobs; no deploy jobs |
| NFR-012 | Storage budget | Total DB < 400 MB (headroom under 500 MB free limit); caps in BR-13 |
| NFR-013 | Observability | Structured logs with request/correlation ID across Spring ↔ FastAPI |
| NFR-014 | Privacy | Store only fields needed by features (§6.5) |

### 2.6 Business rules
| ID | Rule | Status |
|---|---|---|
| BR-01 | A user sees/acts only on repos where `repos.user_id = currentUser` | C (single-user scope) |
| BR-02 | GitHub "issues" endpoint items with a `pull_request` key are PRs → excluded from `issues` | C (GitHub API behaviour) |
| BR-03 | Duplicate threshold: cosine similarity ≥ **0.80** → `possible duplicate`; configurable `DUPLICATE_THRESHOLD`; top-k = 5 | P (tune on fixtures) |
| BR-04 | Label classes = `bug`, `feature`, `question`. Training labels derived from repo labels via alias map: `bug`→bug; `enhancement`,`feature`,`feature request`→feature; `question`,`help wanted`?→question. Issues matching 0 or >1 class excluded from training | C (classes) / P (map) / TBD (`help wanted`) |
| BR-05 | Risk = round(100 × Σ wᵢ·sᵢ), sᵢ ∈ [0,1]: s₁ days since last commit (min(d/180,1), w=0.35); s₂ open-issue growth over 30 d (clamp((open_now−open_30d_ago)/max(open_30d_ago,1),0,1), w=0.20); s₃ unanswered rate = open issues >7 d old with 0 comments ÷ open issues (w=0.25); s₄ contributor drop-off = max(0, 1 − active_last_90d ÷ max(active_prev_90d,1)) (w=0.20). Levels: Low 0–33, Medium 34–66, High 67–100 | C (factors, 0–100, levels) / P (weights, formulas) |
| BR-06 | Bus factor = smallest n of contributors (sorted desc by commits) whose commits ≥ 50% of total, over last 12 months of synced default-branch commits; 1–2 = risky | C (definition) / P (window) |
| BR-07 | Label classifier trains only if ≥ 30 usable labeled issues and ≥ 2 classes with ≥ 5 each | P |
| BR-08 | Merge-time model trains only if ≥ 20 merged PRs; target = (merged_at − created_at) in days | P |
| BR-09 | First prediction for a PR is frozen (`predicted_merge_days`, `predicted_at`) for actual-vs-predicted in reports; later predictions update `latest_predicted_merge_days` | P |
| BR-10 | Suggested-answer retrieval uses closed issues + README/docs only; duplicate search uses all issues of same repo | C |
| BR-11 | Weekly report (period = previous Mon 00:00 → Sun 23:59:59 in `APP_TIMEZONE`, default UTC) contains: new/closed/open issue counts, duplicates caught (new issues flagged in period), avg PR merge time actual vs predicted (PRs merged in period), risk score + delta vs previous report, bus factor, top 5 contributors | C (contents) / P (period, top-5) |
| BR-12 | Catch-up: on startup, if a tracked repo lacks the previous period's report, generate it | P |
| BR-13 | Sync caps: last 12 months of commits (max 5,000), last 500 PRs, all issues up to 10,000; docs = `README*` + `docs/**/*.md` ≤ 50 files, ≤ 200 KB each | P |
| BR-14 | Webhook events for untracked repos are acknowledged (204) and ignored | P |

### 2.7 Validation, edge cases, failure behaviour
| Case | Behaviour |
|---|---|
| Track repo already tracked | 409 `REPO_ALREADY_TRACKED` |
| Repo not accessible with token (deleted/private/revoked) | Sync `FAILED`, reason shown; 404 from GitHub mapped to `GITHUB_NOT_FOUND` |
| Token revoked by user on GitHub | GitHub 401 → mark session invalid → 401 `GITHUB_TOKEN_INVALID`; UI routes to Login |
| Rate limit exhausted | Sync paused until reset; status `RATE_LIMITED` with reset time |
| Empty repo (no commits/issues) | Dashboard shows empty states; risk computed with s₁=1 if no commits (TBD: confirm) |
| Issue with empty body | Embed title only |
| Very long issue body | Truncated to 8,000 chars before embedding/classification |
| Non-English issues | Processed; accuracy not guaranteed (MiniLM is English-focused) — documented limitation |
| FastAPI unreachable | Spring returns data without predictions; `ml_status: "UNAVAILABLE"`; retry on next sync |
| Model not trained | Predictions null; UI shows "Train model" CTA |
| Supabase paused (inactivity) | Startup health check fails with clear log: "Database unreachable — resume project in Supabase dashboard" |
| Pagination params out of range | `size` clamped to 1–100; negative `page` → 400 |
| Report download unknown format | 400 `UNSUPPORTED_FORMAT` |

### 2.8 Completion criteria
1. All P0 + P1 FRs meet acceptance criteria with evidence (passing tests in CI or recorded manual check).
2. NFR-001, 002, 005, 008, 011 verified.
3. CI green on `main` for 3 consecutive runs.
4. Appendix A checklist updated; no unresolved `TBD` that blocks a P0/P1 FR.

---

## 3. User Flows & Application Structure

### 3.1 Routes / page inventory
| # | Route | Page | Purpose | Auth |
|---|---|---|---|---|
| 1 | `/login` | Login | "Sign in with GitHub" | Public |
| 2 | `/repos` | Repo List | Tracked repos, risk badge, sync status; add/untrack | Yes |
| 3 | `/repos/:repoId` | Repo Dashboard | Risk + delta, stats, heatmap, bus factor, model status | Yes |
| 4 | `/repos/:repoId/issues` | Issues | Predicted labels, duplicate tags, filters | Yes |
| 5 | `/repos/:repoId/issues/:number` | Issue Detail | Duplicate matches, suggested answer | Yes |
| 6 | `/repos/:repoId/pulls` | Pull Requests | Open PRs + predicted merge time | Yes |
| 7 | `/repos/:repoId/reports` (+ `/:reportId`) | Weekly Reports | History, view, download | Yes |
| — | `*` | Not found | Link back to `/repos` | — |

"Add repository" is a modal on Repo List [P] (no extra page). Repo-scoped pages share a sub-navigation: Dashboard · Issues · Pull Requests · Reports.

### 3.2 Authentication flow
```mermaid
sequenceDiagram
  participant U as Browser (React :5173)
  participant S as Spring Boot (:8080)
  participant G as GitHub
  U->>S: GET /oauth2/authorization/github (via Vite proxy)
  S->>G: redirect authorize (client_id, scope, state)
  G->>U: consent screen
  U->>S: GET /login/oauth2/code/github?code&state
  S->>G: exchange code → access token
  S->>G: GET /user
  S->>S: upsert users (token AES-GCM encrypted)
  S->>U: Set-Cookie GM_SESSION (JWT, HttpOnly, SameSite=Lax) + redirect /repos
  U->>S: GET /api/v1/me
```
Failure branches: `state` mismatch / user denies → redirect `/login?error=oauth_failed`; GitHub down → same with `error=github_unavailable`.

### 3.3 Track repo & initial sync
```mermaid
flowchart TD
  A[Repo List: Add repository] --> B[GET /api/v1/github/repos]
  B --> C{User selects repo}
  C -->|cancel| A
  C --> D[POST /api/v1/repos]
  D -->|409| E[Show 'already tracked']
  D -->|201| F[Async sync job: issues → PRs → commits]
  F --> G[Index README/docs/issues → FastAPI /index]
  G --> H[Predict labels/merge time if models exist; compute risk]
  H --> I[status DONE]
  F -->|GitHub error| J[status FAILED + reason]
  F -->|rate limit| K[status RATE_LIMITED, resume after reset]
```

### 3.4 New issue (webhook) flow
```mermaid
flowchart LR
  W[GitHub issues.opened] --> V{HMAC valid?}
  V -->|no| X[401, no write]
  V -->|yes| T{Repo tracked?}
  T -->|no| Y[204 ignore]
  T -->|yes| U[Upsert issue]
  U --> P[FastAPI: embed + duplicates + classify]
  P -->|ok| Q[Store label, dup flag/score]
  P -->|ML down| R[Store issue only; ml_status UNAVAILABLE]
```

### 3.5 Other key flows
| Flow | Steps | Failure handling |
|---|---|---|
| Train model | Dashboard → Model status → Train (label / merge-time) → spinner → metrics shown → predictions refreshed | 422 insufficient data → message with required minimum; 503 ML down → retry later |
| Issue triage | Issues → filter "possible duplicate" → Issue Detail → open matched issue on GitHub (new tab) / copy suggested answer | No matches → "No similar issues found" |
| Weekly report | Scheduler / "Generate now" → Reports list → view → download md/html | Generation fails → report not stored, error logged, retried next startup (BR-12) |
| Logout | Header menu → Logout → `POST /api/v1/auth/logout` → `/login` | — |
| Session expiry | Any 401 → clear client state → `/login?next=<path>` | — |

---

## 4. UI/UX Design Document

### 4.1 Design direction
- Visual design, UI/UX and colour palette must match GitMedic's identity: a **diagnostic / health tool for code repositories** — calm, data-dense, trustworthy; health status (Low/Medium/High) is the primary semantic signal.
- **No visual style, layout technique, effect, or exact styling values are mandated.** Choose one consistent, modern, user-friendly design and apply it across all pages. Exact palette, typography and spacing = implementer's decision, recorded in the frontend README (TBD-12).
- Light/dark support: optional [P2].

### 4.2 Functional UI principles
| ID | Principle |
|---|---|
| UX-01 | Status never color-only: risk badge = text level + numeric score (+ icon optional) |
| UX-02 | Every AI output labelled as a prediction with confidence/score; never presented as fact |
| UX-03 | External GitHub links open in new tab with `rel="noopener noreferrer"` |
| UX-04 | Every data widget handles loading / empty / error independently |
| UX-05 | Destructive actions (untrack) require confirmation |
| UX-06 | Long-running actions (sync, train, generate report) show progress state and disable re-trigger |
| UX-07 | Filters, pagination, tabs persisted in URL query |

### 4.3 Page specifications
| Page | Key components | States |
|---|---|---|
| Login | App name + value statement, "Sign in with GitHub" button | Error banner from `?error=` |
| Repo List | Repo rows/cards: name, visibility, risk badge, last synced, sync status, actions (Open, Sync now, Untrack); "Add repository" modal with searchable list of GitHub repos | Empty: "Track your first repository"; syncing; failed (reason + retry); rate-limited (reset time) |
| Repo Dashboard | Risk score + level + delta vs last week; stat tiles (open/closed issues, open PRs, duplicates flagged); heatmap (calendar grid + weekday bars); bus factor value + risky flag + top-contributors bar chart; model status panel (Train/Retrain) | Per-widget loading/empty/error; "insufficient data" for models |
| Issues | Table: #, title, state, predicted label + confidence, duplicate tag (score), created date; filters: state, label, duplicate-only; pagination | Empty; ML unavailable notice; model not trained CTA |
| Issue Detail | Issue header (title, link, state, labels on GitHub vs predicted); Possible duplicates list (title, score, state, link); Suggested answer (draft text + source list; copy button); LLM toggle hidden when disabled | No matches; ML unavailable; LLM rate-limited → retrieval-only notice |
| Pull Requests | Table: #, title, author, size (+/−, files), reviews, age, predicted merge days | Model not trained; empty |
| Weekly Reports | List (period, risk, generated at); detail rendered from metrics; Download `.md` / `.html`; Generate now | Empty: "First report will be generated on <date>" |

### 4.4 Text wireframe — Repo Dashboard (structure only, not a mandated layout)
```
[Header: GitMedic | repo selector | user menu]
[Sub-nav: Dashboard | Issues | Pull Requests | Reports]
[Risk: 72 · High · ▲8 vs last week]  [Open issues] [Closed (7d)] [Open PRs] [Duplicates (7d)]
[Commit activity heatmap ................................]
[Bus factor: 1 · Risky]  [Top contributors bar chart]
[Model status: Labels ✓ F1 0.71 | Merge-time ✗ insufficient data (12/20 PRs)] [Train]
```

### 4.5 Accessibility & responsiveness
- WCAG 2.2 AA target: text contrast ≥ 4.5:1, focus visible, all controls keyboard-reachable, logical tab order, `aria-live="polite"` for sync/train status.
- Charts: provide accessible table/summary alternative (e.g., visually hidden table or "View as table").
- Heatmap cells: `aria-label="<date>: <n> commits"`.
- ≥ 360 px: tables collapse to stacked rows or horizontal scroll inside container (no page-level horizontal scroll).

### 4.6 Frontend libraries [P] (all free/OSS)
| Need | Choice | Note |
|---|---|---|
| Routing | React Router | MIT |
| Server state | TanStack Query | MIT; caching, retries off for 4xx |
| Charts | Recharts | MIT; bar charts |
| Heatmap | Custom CSS grid component | Avoids extra dependency |
| Markdown render (report, suggested answer) | `react-markdown` (no raw HTML) | MIT; safe by default |
| Styling | Implementer's choice (plain CSS/CSS Modules or a free utility framework) | No style mandated |

---

## 5. TRD — Technical Requirements Document

### 5.1 Technology stack
| Layer | Technology | Version (target) | Purpose | Status |
|---|---|---|---|---|
| Frontend | React + Vite, **JavaScript** | React 19, Vite (current major) | SPA: dashboard, tables, charts, reports | C (stack) / P (versions) |
| Frontend runtime | Node.js | 24 LTS (≥ 22.12) | Build/test tooling | P |
| Main backend | Spring Boot (Java), Maven | Spring Boot **4.1.x** on Java **21 LTS** | OAuth, GitHub sync, webhooks, REST API, scheduler | C (Spring Boot) / P (versions) |
| ML/RAG service | FastAPI (Python) | Python 3.12 | Classifier, predictors, risk, embeddings, search | C / P (version) |
| Database | Supabase Postgres + `pgvector` | Supabase-managed PG; `pgvector/pgvector:pg17` image for CI/local fallback | App data + embeddings in one DB | C |
| ML | scikit-learn, joblib | latest stable | TF-IDF + LogReg; RandomForestRegressor; model persistence | C |
| Embeddings | sentence-transformers `all-MiniLM-L6-v2` (384-dim, CPU) | latest stable | Local embeddings — no API cost | C |
| LLM (optional) | Groq API (OpenAI-compatible) | model via `LLM_MODEL` | Polish suggested answer only (FR-018) | P (see 5.8) |
| CI | GitHub Actions | — | Lint, test, build, dependency/secret scans | C |

Version notes: Spring Boot 3.5 OSS support ended 2026-06-30; 4.0.x OSS ends 2026-12-31 → target 4.1.x (requires Java 17+, supports up to 25/26). Re-verify at project start (§12.5).

### 5.2 Architecture
```mermaid
flowchart LR
  B[React SPA<br/>Vite :5173] -- /api via Vite proxy<br/>cookie JWT --> S[Spring Boot :8080]
  GH[(GitHub REST + OAuth)] <-- token --> S
  GH -- webhooks via gh webhook forward --> S
  S -- REST + X-Internal-Token --> F[FastAPI :8000<br/>127.0.0.1 only]
  S -- JDBC role gitmedic_app --> DB[(Supabase Postgres<br/>schema gitmedic + pgvector)]
  F -- psycopg role gitmedic_ml --> DB
  F -. optional HTTPS .-> L[(Groq API)]
  F -- joblib --> M[[Local model files]]
```
Rules [C]: React → Spring only. Spring owns users/repos/GitHub data and calls FastAPI. FastAPI never calls GitHub.

### 5.3 Component responsibilities
| Component | Owns | Does not |
|---|---|---|
| React | UI, routing, client cache | Call GitHub/FastAPI; hold tokens (cookie is HttpOnly) |
| Spring Boot | Auth, JWT, token encryption, GitHub client, sync jobs, webhook verification, analytics SQL (heatmap, bus factor, stats), weekly reports, DB migrations (Flyway), orchestration of ML calls | ML math, embeddings |
| FastAPI | Training/prediction, risk computation, chunking, embeddings, vector search, optional LLM call | GitHub access, user/session data, schema migrations |
| Postgres | Persistence, vector index | Business logic |

### 5.4 Folder structure [P]
```
gitmedic/
├── frontend/                     # React + Vite (JS)
│   ├── src/{api,components,hooks,pages,routes,utils}/
│   ├── src/test/                 # Vitest + RTL + MSW
│   ├── e2e/                      # Playwright (P2)
│   └── package.json, vite.config.js, eslint.config.js, .prettierrc
├── backend/                      # Spring Boot (Maven)
│   ├── src/main/java/dev/gitmedic/
│   │   ├── auth/ github/ sync/ webhook/ repo/ issue/ pull/
│   │   ├── analytics/ ml/ report/ config/ common/
│   ├── src/main/resources/{application.yml, db/migration/V*.sql}
│   └── src/test/java/...         # JUnit 5, Testcontainers, WireMock
├── ml-service/                   # FastAPI
│   ├── app/{api,core,db}/
│   ├── app/ml/{label_classifier.py,merge_time.py,risk.py}
│   ├── app/rag/{chunking.py,embedder.py,search.py,llm.py}
│   ├── tests/{unit,integration,fixtures}/
│   ├── data/models/              # gitignored joblib files
│   └── pyproject.toml, requirements*.txt
├── fixtures/github/              # shared JSON fixtures (issues, PRs, commits, webhooks)
├── docker-compose.yml            # optional local pgvector DB (P)
├── .github/workflows/ci.yml, .github/dependabot.yml
├── .gitignore  (.env, data/models, target, node_modules, .venv)
└── README.md
```

### 5.5 Module interfaces (Spring)
| Module | Key types | Talks to |
|---|---|---|
| `auth` | `SecurityConfig`, `OAuthSuccessHandler`, `JwtService`, `TokenCipher` | `users` |
| `github` | `GitHubClient` (RestClient; pagination, ETag, rate-limit guard) | GitHub |
| `sync` | `SyncService` (@Async), `SyncStatus` | `github`, repositories, `ml` |
| `webhook` | `WebhookController`, `SignatureVerifier`, event handlers | `sync`, `ml` |
| `analytics` | `HeatmapService`, `BusFactorService`, `StatsService` | SQL |
| `ml` | `MlClient` (RestClient, timeouts, circuit-break by health flag) | FastAPI |
| `report` | `WeeklyReportScheduler`, `ReportService`, `ReportRenderer` (md/html) | analytics, ml, `weekly_reports` |

### 5.6 Configuration & environment variables
| Var | Service | Required | Example (placeholder) |
|---|---|---|---|
| `DB_URL` | backend | Y | `jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require` |
| `DB_USER` / `DB_PASSWORD` | backend | Y | `gitmedic_app` / `<set-locally>` |
| `ML_DB_DSN` | ml | Y | `postgresql://gitmedic_ml:<pw>@<pooler-host>:5432/postgres?sslmode=require` |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | backend | Y | `<oauth-app-client-id>` / `<secret>` |
| `GITHUB_SCOPES` | backend | N | `read:user` (add `repo` for private repos — TBD-02) |
| `GITHUB_WEBHOOK_SECRET` | backend | Y (if webhooks) | `<random-32+-chars>` |
| `JWT_SECRET` | backend | Y | `<random-256-bit-base64>` |
| `JWT_TTL_MINUTES` | backend | N | `480` |
| `TOKEN_ENCRYPTION_KEY` | backend | Y | `<base64-256-bit-key>` |
| `ML_BASE_URL` | backend | Y | `http://127.0.0.1:8000` |
| `INTERNAL_API_TOKEN` | backend + ml | Y | `<random-32+-chars>` (same value both) |
| `FRONTEND_ORIGIN` | backend | Y | `http://localhost:5173` |
| `APP_TIMEZONE` / `REPORT_CRON` | backend | N | `UTC` / `0 0 6 * * MON` |
| `DUPLICATE_THRESHOLD` / `DUPLICATE_TOP_K` | ml | N | `0.80` / `5` |
| `MODEL_DIR` | ml | N | `./data/models` |
| `EMBEDDING_MODEL` | ml | N | `sentence-transformers/all-MiniLM-L6-v2` |
| `LLM_ENABLED` / `LLM_PROVIDER` / `LLM_MODEL` / `GROQ_API_KEY` | ml | N | `false` / `groq` / `<model-id>` / `<key>` |
| `LLM_TIMEOUT_SECONDS` | ml | N | `20` |

Startup fails fast with a clear message if a required var is missing (QA-CFG-01).

### 5.7 Background processing
| Job | Mechanism | Rules |
|---|---|---|
| Initial/manual sync | `@Async` on bounded executor (core 2, queue 10) | One active sync per repo (status check in tx with row lock); order: repo → issues → PRs (+details, reviews) → commits → index → predict → risk |
| Webhook processing | Synchronous handler, < 10 s; heavy work delegated to async | Respond 2xx promptly; idempotent upserts by natural keys |
| Weekly report | `@Scheduled(cron=${REPORT_CRON}, zone=${APP_TIMEZONE})` + startup catch-up (BR-12) | Unique `(repo_id, period_start)` prevents duplicates |
| Risk recompute | After each sync + inside weekly report | Appends `repo_health` row |

### 5.8 AI / ML / RAG design
**Models [C approaches, P parameters]**
| Model | Input features | Algorithm | Output | Eval |
|---|---|---|---|---|
| Label classifier | `title + "\n" + body` (≤ 8,000 chars) | `TfidfVectorizer(ngram_range=(1,2), min_df=2, max_features=20000)` + `LogisticRegression(class_weight="balanced", max_iter=1000)` | class + max probability | stratified 80/20, macro-F1 |
| Merge-time | additions, deletions, changed_files, review_count, author_prior_merged_prs, author_median_merge_days | `RandomForestRegressor(n_estimators=200, random_state=42)` | days (clamped ≥ 0) | 80/20, MAE days |
| Risk score | BR-05 factors | Deterministic weighted formula | 0–100 + level + components | Unit tests on hand-computed cases |

Persistence: `MODEL_DIR/{repo_id}/{label|merge_time}.joblib` + `meta.json` (trained_at, n_samples, class counts, metric, sklearn version). Only files written by the service are loaded (joblib = pickle; SEC-14).

**RAG**
| Step | Spec |
|---|---|
| Sources | README + `docs/**/*.md` (Spring fetches text, sends to FastAPI); issues (title+body); closed-issue content also used for answers |
| Chunking | Markdown split on headings, then ~800-char windows with 100-char overlap; issues = 1 chunk (title + first 1,500 chars of body) — MiniLM truncates long inputs (~256 word pieces) |
| Embedding | `all-MiniLM-L6-v2`, normalized vectors, batch 32, CPU |
| Store | `embeddings.embedding vector(384)`, HNSW index `vector_cosine_ops` |
| Duplicate search | same `repo_id`, `source_type='issue'`, exclude self, ORDER BY cosine distance, top-k; similarity = 1 − distance |
| Answer retrieval | closed issues + readme/doc chunks, top 5; response = ordered snippets + links (retrieval-only) |
| Idempotency | `content_hash` (SHA-256); unchanged chunks skipped |

**LLM provider decision [P]**
| Option | Free tier | Fit | Decision |
|---|---|---|---|
| None (retrieval-only) | n/a | Fully meets FR-017 | **Default** (`LLM_ENABLED=false`) |
| Groq | Free plan exists; limits per model, per org, shown in console; 429 on exceed [C, console.groq.com/docs/rate-limits] | OpenAI-compatible REST via `httpx`, no SDK required; low latency | **Selected optional provider (FR-018, P2)** |
| Gemini | Free tier exists for eligible countries; limits per project per model; Pro models reported as no longer on free tier since 2026-04 [secondary source — verify]; free-tier inputs may be used to improve Google products [verify terms] | Works, but free-tier data-use terms are a concern for private-repo issue text | Not integrated; documented alternative (FE-2) |

Rationale: AI text generation is optional per `GitMedic.md`; one provider keeps code/test surface minimal. Exact model ID, free-plan limits, card requirement and data-retention terms for Groq = TBD-05 (verify in console before enabling).

LLM contract (internal): input = issue title/body (truncated 2,000 chars) + ≤ 5 retrieved snippets with IDs; system prompt: answer only from snippets, cite snippet IDs, say "insufficient context" otherwise; output validated: non-empty, ≤ 2,000 chars, cited IDs ⊆ retrieved IDs (else drop citations and flag `llm_status="UNVERIFIED_CITATIONS"`). Timeout 20 s, no retry on 429/5xx → retrieval-only + `llm_status` ∈ {`OK`,`DISABLED`,`RATE_LIMITED`,`UNAVAILABLE`,`INVALID_OUTPUT`}.

### 5.9 External integrations
| Integration | Role | Notes |
|---|---|---|
| GitHub OAuth App | Login + user token | Callback `http://localhost:8080/login/oauth2/code/github`; scopes per `GITHUB_SCOPES` |
| GitHub REST API | Sync | Endpoints in §7.6; `per_page=100`; follow `Link` header; `If-None-Match` ETags; pause when `X-RateLimit-Remaining < 100` |
| GitHub Webhooks | Live updates | Dev-only delivery via `gh extension install cli/gh-webhook` → `gh webhook forward --repo=OWNER/REPO --events=issues,pull_request,push --url=http://localhost:8080/api/v1/webhooks/github` [C, GitHub docs; designed for testing/development; requires admin rights on the repo]; secret flag support = verify |
| Groq | Optional LLM | Server-side only |

Rate-limit budget example (initial sync, 1,000 issues / 500 PRs / 3,000 commits): issues 10 + PR list 5 + PR details 500 + reviews 500 + commits 30 + docs ≤ 52 ≈ **1,100 requests** (< 5,000/h). PR details/reviews dominate → capped by BR-13.

### 5.10 Logging, errors, observability (local)
- Spring: SLF4J/Logback, MDC `requestId` (from `X-Request-Id` or generated), forwarded to FastAPI; FastAPI logs it.
- Errors: Spring `ProblemDetail` (RFC 9457) with `code`; FastAPI equivalent JSON `{code, message, request_id}`.
- Never log tokens, secrets, cookies, full issue bodies (log IDs only).
- Health: Spring Actuator `/actuator/health` (DB + ML ping); FastAPI `/health` (DB + model dir).

### 5.11 Free-tier feasibility
| Service | Classification | Relevant limits | Mitigation |
|---|---|---|---|
| Supabase Free | Proprietary free tier (Postgres is OSS) | 500 MB DB, 2 active projects, pause after 1 week inactivity, shared CPU/500 MB RAM [C] | BR-13 caps; resume from dashboard; Docker `pgvector/pgvector` fallback |
| GitHub REST/OAuth | Free | 5,000 req/h per user token (verify) | ETags, caps, pause/resume |
| GitHub Actions | Free tier | Standard hosted runners free for public repos; private repos on GitHub Free: 2,000 min/month [C, GitHub billing docs] | Linux runners only; caching; `concurrency` cancel |
| Groq | Proprietary free plan | Per-model RPM/RPD/TPM, org-level [C]; exact values in console | Optional; retrieval-only fallback |
| Hugging Face model download | Free | ~90 MB one-time download (verify) | Cache in CI (`actions/cache`) |
| All libraries | OSS (MIT/Apache/BSD) | — | Dependabot |

### 5.12 Trade-offs
| Decision | Alternative | Why |
|---|---|---|
| JWT in HttpOnly cookie via Vite proxy | Bearer token in localStorage | Not readable by XSS; same-origin in dev removes CORS cookie complexity |
| Flyway in Spring owns all migrations (incl. `embeddings`) | Each service migrates own tables | Single schema history; FastAPI gets least-privilege role |
| Local embeddings | Gemini embeddings API | Zero cost, no quota, deterministic CI with fake embedder |
| HNSW index | IVFFlat | No training step, good recall at small scale |
| Per-repo models | Global model | Matches `GitMedic.md` ("trains on the repo's own synced data") |
| Spring computes heatmap/bus factor | FastAPI | Pure SQL aggregates; build order says no ML needed |

---

## 6. Database & Data Design

### 6.1 Selection
Supabase Postgres + `pgvector` [C]: relational app data and vectors in one DB. Used **only as Postgres** — not Supabase Auth, Storage, or Data API. CI/local fallback: `pgvector/pgvector:pg17` container [P].

### 6.2 Schema setup (Flyway V1) [P]
- `create schema if not exists extensions; create extension if not exists vector with schema extensions;`
- All tables in schema **`gitmedic`** (not exposed through Supabase Data API — verify Data API "exposed schemas" excludes it).
- `alter table … enable row level security;` on every table with **no policies for `anon`/`authenticated`** → deny-by-default as defense in depth. `gitmedic_app` owns the tables (owners bypass RLS unless `FORCE ROW LEVEL SECURITY`); `gitmedic_ml` is not owner → needs explicit policies `FOR SELECT/ALL TO gitmedic_ml USING (true)` on its tables (TBD-08: verify role creation/ownership rules on Supabase).
- Roles: `gitmedic_app` (owner, all tables) · `gitmedic_ml` (see §6.4).

### 6.3 Tables (DS-001 … DS-008)
`id` = `bigint generated always as identity primary key` unless stated; `*_gh` = timestamps from GitHub; all `timestamptz`.

**DS-001 `users`** (FR-001–003)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| github_id | bigint | NOT NULL, UNIQUE |
| login | varchar(100) | NOT NULL |
| name | varchar(255) | NULL |
| avatar_url | text | NULL |
| access_token_enc | bytea | NOT NULL (IV ‖ ciphertext ‖ tag) |
| token_scopes | varchar(255) | NULL |
| created_at / updated_at | timestamptz | NOT NULL DEFAULT now() |

**DS-002 `repos`** (FR-004–008, 023)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| user_id | bigint | NOT NULL FK → users ON DELETE CASCADE |
| github_repo_id | bigint | NOT NULL |
| full_name | varchar(200) | NOT NULL |
| default_branch | varchar(255) | NOT NULL |
| is_private | boolean | NOT NULL |
| sync_status | varchar(20) | NOT NULL DEFAULT 'PENDING', CHECK IN (PENDING,RUNNING,DONE,FAILED,RATE_LIMITED) |
| sync_error | text | NULL |
| last_synced_at | timestamptz | NULL |
| created_at | timestamptz | NOT NULL DEFAULT now() |
| | | UNIQUE (user_id, github_repo_id) |

**DS-003 `issues`** (FR-005, 011–012, 016, 025)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| repo_id | bigint | NOT NULL FK → repos ON DELETE CASCADE |
| github_issue_id | bigint | NOT NULL |
| number | int | NOT NULL; UNIQUE (repo_id, number) |
| title | text | NOT NULL |
| body | text | NULL (≤ 65,535 chars stored) |
| state | varchar(10) | CHECK IN (open, closed) |
| author_login | varchar(100) | NULL |
| gh_labels | text[] | NOT NULL DEFAULT '{}' |
| comments_count | int | NOT NULL DEFAULT 0 |
| html_url | text | NOT NULL |
| created_at_gh / updated_at_gh / closed_at_gh | timestamptz | closed NULL |
| predicted_label | varchar(10) | NULL, CHECK IN (bug, feature, question) |
| predicted_confidence | real | NULL, CHECK 0–1 |
| predicted_at | timestamptz | NULL |
| is_possible_duplicate | boolean | NOT NULL DEFAULT false |
| duplicate_score | real | NULL |
| duplicate_flagged_at | timestamptz | NULL (for "duplicates caught" in BR-11) |

Indexes: (repo_id, state), (repo_id, created_at_gh), (repo_id, is_possible_duplicate).

**DS-004 `pull_requests`** (FR-005, 013)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| repo_id | bigint | NOT NULL FK → repos ON DELETE CASCADE |
| github_pr_id | bigint | NOT NULL |
| number | int | NOT NULL; UNIQUE (repo_id, number) |
| title | text | NOT NULL |
| state | varchar(10) | CHECK IN (open, closed) |
| author_login | varchar(100) | NULL |
| additions / deletions / changed_files / review_count | int | NOT NULL DEFAULT 0, CHECK ≥ 0 |
| html_url | text | NOT NULL |
| created_at_gh / merged_at_gh / closed_at_gh | timestamptz | merged/closed NULL |
| predicted_merge_days | real | NULL (frozen first prediction, BR-09) |
| predicted_at | timestamptz | NULL |
| latest_predicted_merge_days | real | NULL |

Indexes: (repo_id, state), (repo_id, merged_at_gh).

**DS-005 `commits`** (FR-009–010, 014)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| repo_id | bigint | NOT NULL FK → repos ON DELETE CASCADE |
| sha | char(40) | NOT NULL; UNIQUE (repo_id, sha) |
| author_login | varchar(100) | NULL |
| author_email_hash | char(64) | NULL (SHA-256 of lowercase email; identity fallback, raw email not stored) |
| committed_at | timestamptz | NOT NULL |

Index: (repo_id, committed_at).

**DS-006 `embeddings`** (FR-015–017)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| repo_id | bigint | NOT NULL FK → repos ON DELETE CASCADE |
| source_type | varchar(10) | CHECK IN (readme, doc, issue) |
| source_ref | varchar(500) | NOT NULL (file path or issue number) |
| source_url | text | NOT NULL |
| chunk_index | int | NOT NULL DEFAULT 0 |
| content | text | NOT NULL |
| content_hash | char(64) | NOT NULL |
| embedding | extensions.vector(384) | NOT NULL |
| created_at | timestamptz | NOT NULL DEFAULT now() |
| | | UNIQUE (repo_id, source_type, source_ref, chunk_index) |

Indexes: HNSW `(embedding extensions.vector_cosine_ops)`; btree (repo_id, source_type).

**DS-007 `repo_health`** (FR-014)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| repo_id | bigint | FK → repos ON DELETE CASCADE |
| computed_at | timestamptz | NOT NULL DEFAULT now() |
| score | smallint | CHECK 0–100 |
| level | varchar(6) | CHECK IN (LOW, MEDIUM, HIGH) |
| components | jsonb | NOT NULL (`{days_since_commit, open_issue_growth, unanswered_rate, contributor_dropoff}`) |

Index: (repo_id, computed_at desc).

**DS-008 `weekly_reports`** (FR-019–022)
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK |
| repo_id | bigint | FK → repos ON DELETE CASCADE |
| period_start / period_end | timestamptz | NOT NULL; UNIQUE (repo_id, period_start) |
| generated_at | timestamptz | NOT NULL DEFAULT now() |
| metrics | jsonb | NOT NULL (BR-11 fields) |

Markdown/HTML rendered on demand from `metrics` (no stored HTML → no stored-XSS surface).

```mermaid
erDiagram
  users ||--o{ repos : tracks
  repos ||--o{ issues : has
  repos ||--o{ pull_requests : has
  repos ||--o{ commits : has
  repos ||--o{ embeddings : indexes
  repos ||--o{ repo_health : scored
  repos ||--o{ weekly_reports : reports
```
No extra tables: model metadata lives in `MODEL_DIR` files; webhook idempotency relies on natural-key upserts.

### 6.4 Access rules
| Role | users | repos | issues | pull_requests | commits | embeddings | repo_health | weekly_reports |
|---|---|---|---|---|---|---|---|---|
| gitmedic_app | RW | RW | RW | RW | RW | RW (delete on untrack) | RW | RW |
| gitmedic_ml | — | R (id only via view or column grant) | R + U(prediction columns) | R + U(prediction columns) | R | RW | R | — |

Spring writes predictions it receives; `gitmedic_ml` update grants are needed only if FastAPI writes predictions directly — **TBD-07** (Proposed: FastAPI returns predictions, Spring writes; then `gitmedic_ml` = read on issues/PRs/commits, RW embeddings only).

### 6.5 Data classification & retention
| Data | Class | Handling |
|---|---|---|
| GitHub access token | Secret | AES-256-GCM; deleted on account delete; never returned |
| Login, name, avatar | Personal (public) | Stored for display |
| Commit author email | Personal | Hashed only |
| Issue/PR text | Repo content (may be private) | Retained while repo tracked; cascade-deleted on untrack |
| Reports, health history | Derived | Retained while repo tracked |
| Model files | Derived | Deleted on untrack (FastAPI `DELETE /internal/v1/repos/{id}`) |

### 6.6 Migrations, seed, backup
- Flyway `V1__schema.sql`, `V2__indexes.sql`, …; never edit applied migrations.
- Seed: `fixtures/github/*.json` loaded by a dev-only Spring profile `seed` (no GitHub calls) + pytest fixtures; includes known duplicate pairs and labeled issues for ML tests.
- Backup: Supabase Free has no guaranteed point-in-time restore [A — verify]; data is re-derivable by re-sync, so backup = optional `pg_dump` script documented in README. Model files re-creatable by retraining.

---

## 7. API Contracts & Integrations

### 7.1 Conventions
- Base: Spring `/api/v1` (public to React); FastAPI `/internal/v1` (Spring only).
- Auth: Spring → cookie `GM_SESSION` (JWT HS256, `sub`=user id, `exp`); FastAPI → header `X-Internal-Token`.
- JSON, UTF-8, ISO-8601 UTC timestamps.
- Pagination: `page` (0-based), `size` (default 20, max 100), `sort=field,asc|desc`; response `{items, page, size, totalItems, totalPages}`.
- Errors (RFC 9457):
```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "code": "REPO_ALREADY_TRACKED", "detail": "Repository is already tracked.", "requestId": "b1f2…" }
```
| Status | Use |
|---|---|
| 400 | Validation (`VALIDATION_ERROR`, field errors in `errors[]`) |
| 401 | No/expired session, bad webhook signature, bad internal token |
| 403 | Resource not owned (returned as 404 to avoid enumeration — SEC-04) |
| 404 | Not found |
| 409 | Conflict (duplicate track, sync already running) |
| 422 | `INSUFFICIENT_TRAINING_DATA`, `MODEL_NOT_TRAINED` |
| 429 | GitHub rate limit surfaced (`GITHUB_RATE_LIMITED`, `retryAfter`) |
| 502/503 | GitHub / ML service unavailable |

### 7.2 Spring Boot endpoints
| ID | Method & path | Purpose | FR |
|---|---|---|---|
| API-01 | GET `/oauth2/authorization/github` | Start OAuth (Spring Security) | 001 |
| API-02 | GET `/login/oauth2/code/github` | OAuth callback → set cookie → redirect | 001–003 |
| API-03 | GET `/api/v1/me` | Current user | 003 |
| API-04 | POST `/api/v1/auth/logout` | Clear cookie (204) | 003 |
| API-05 | GET `/api/v1/github/repos?page&size&q` | User's GitHub repos (+`tracked` flag) | 004 |
| API-06 | GET `/api/v1/repos` | Tracked repos + latest risk + sync status | 004, 008, 023 |
| API-07 | POST `/api/v1/repos` | Track repo, start sync | 004, 005 |
| API-08 | DELETE `/api/v1/repos/{repoId}` | Untrack (cascade + delete models) | 004 |
| API-09 | POST `/api/v1/repos/{repoId}/sync` | Manual incremental sync (202) | 007 |
| API-10 | GET `/api/v1/repos/{repoId}/dashboard` | Risk, delta, stats, model status | 014, 024, 026 |
| API-11 | GET `/api/v1/repos/{repoId}/heatmap?from&to` | Daily + weekday counts | 009 |
| API-12 | GET `/api/v1/repos/{repoId}/bus-factor` | Bus factor + top contributors | 010 |
| API-13 | GET `/api/v1/repos/{repoId}/issues?state&label&duplicate&page&size&sort` | Issue list | 012, 016, 025 |
| API-14 | GET `/api/v1/repos/{repoId}/issues/{number}` | Detail + duplicates + suggested answer (`?llm=true` optional) | 016–018 |
| API-15 | GET `/api/v1/repos/{repoId}/pulls?state&page&size` | PRs + predictions | 013 |
| API-16 | POST `/api/v1/repos/{repoId}/models/{model}/train` (`model` ∈ `label`,`merge-time`) | Train/retrain + re-predict | 011, 013, 026 |
| API-17 | POST `/api/v1/repos/{repoId}/index` | Re-index README/docs/issues (202) | 015 |
| API-18 | GET `/api/v1/repos/{repoId}/reports?page&size` | Report history | 020 |
| API-19 | POST `/api/v1/repos/{repoId}/reports` | Generate current/previous period now | 022 |
| API-20 | GET `/api/v1/reports/{reportId}` | Report detail | 020 |
| API-21 | GET `/api/v1/reports/{reportId}/download?format=md\|html` | File download | 021 |
| API-22 | POST `/api/v1/webhooks/github` | Webhook receiver (no session; HMAC) | 006 |

**Examples**

API-07 request/response:
```http
POST /api/v1/repos
{ "githubRepoId": 123456, "fullName": "octo/demo" }
→ 201 { "id": 7, "fullName": "octo/demo", "syncStatus": "PENDING" }
```
Validation: `fullName` matches `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`; repo must appear in API-05 result for this user (else 404).

API-10 response:
```json
{ "repo": {"id":7,"fullName":"octo/demo","lastSyncedAt":"2026-10-24T06:00:00Z","syncStatus":"DONE"},
  "risk": {"score":72,"level":"HIGH","delta":8,"components":{"daysSinceCommit":0.9,"openIssueGrowth":0.4,"unansweredRate":0.6,"contributorDropoff":0.5}},
  "stats": {"openIssues":41,"closedIssues7d":3,"openPrs":5,"duplicatesFlagged7d":2},
  "models": {"label":{"trained":true,"trainedAt":"…","macroF1":0.71,"samples":220},
             "mergeTime":{"trained":false,"reason":"INSUFFICIENT_TRAINING_DATA","have":12,"need":20}},
  "mlStatus": "OK" }
```

API-14 response (excerpt):
```json
{ "issue": {"number":88,"title":"Crash on save","state":"open","htmlUrl":"…","predictedLabel":"bug","predictedConfidence":0.83},
  "duplicates": [{"number":41,"title":"App crashes when saving","state":"closed","score":0.87,"htmlUrl":"…"}],
  "suggestedAnswer": {"mode":"RETRIEVAL_ONLY","llmStatus":"DISABLED",
     "draft":"Similar issues were resolved by …",
     "sources":[{"type":"issue","ref":"41","url":"…","snippet":"…"},{"type":"doc","ref":"docs/faq.md#saving","url":"…","snippet":"…"}]} }
```

API-21: `Content-Type: text/markdown; charset=utf-8` or `text/html; charset=utf-8`; `Content-Disposition: attachment; filename="gitmedic-octo-demo-2026-10-19.md"`.

API-22: headers `X-GitHub-Event`, `X-GitHub-Delivery`, `X-Hub-Signature-256`. Verify `sha256=HMAC(secret, rawBody)` with constant-time compare **before** parsing. Responses: 200/202 processed, 204 ignored (untracked repo or unsupported event/action), 401 bad signature, 400 malformed JSON. Handled actions [P]: `issues` (opened, edited, closed, reopened, labeled, unlabeled, deleted), `pull_request` (opened, edited, closed, reopened, synchronize), `push` (commits on default branch only).

### 7.3 FastAPI internal endpoints
All require `X-Internal-Token`; bound to `127.0.0.1`; request body ≤ 2 MB.
| ID | Method & path | Request | Response |
|---|---|---|---|
| ML-01 | GET `/health` (no token) | — | `{status, db, embedder_loaded}` |
| ML-02 | POST `/internal/v1/train/label` | `{repo_id}` (reads issues from DB) | `{trained_at, n_samples, class_counts, macro_f1}` / 422 |
| ML-03 | POST `/internal/v1/predict/label` | `{repo_id, issues:[{id,title,body}]}` (≤ 500) | `{predictions:[{id,label,confidence}]}` / 422 `MODEL_NOT_TRAINED` |
| ML-04 | POST `/internal/v1/train/merge-time` | `{repo_id}` | `{trained_at, n_samples, mae_days}` / 422 |
| ML-05 | POST `/internal/v1/predict/merge-time` | `{repo_id, prs:[{id,additions,deletions,changed_files,review_count,author_login}]}` | `{predictions:[{id,days}]}` |
| ML-06 | POST `/internal/v1/risk` | `{repo_id, as_of}` | `{score, level, components}` |
| ML-07 | POST `/internal/v1/index` | `{repo_id, documents:[{source_type,source_ref,source_url,text}]}` | `{chunks_upserted, chunks_skipped}` |
| ML-08 | POST `/internal/v1/duplicates` | `{repo_id, issue_number, title, body, top_k?}` | `{matches:[{issue_number,score}], is_possible_duplicate, top_score}` |
| ML-09 | POST `/internal/v1/suggest` | `{repo_id, issue_number, title, body, use_llm}` | `{sources:[…], draft, mode, llm_status}` |
| ML-10 | DELETE `/internal/v1/repos/{repo_id}` | — | 204 (models + embeddings removed) |

Pydantic models validate all fields (types, lengths, `top_k` 1–20, `source_type` enum).

### 7.4 Timeouts, retries, failure behaviour
| Call | Timeout | Retry | On failure |
|---|---|---|---|
| Spring → GitHub | 10 s connect/read 30 s | 3× exp. backoff on 5xx/network; none on 4xx; rate limit → pause until reset | Sync FAILED/RATE_LIMITED |
| Spring → FastAPI predict/search | 5 s (train: 120 s, index: 120 s) | 1 retry on connect error | `mlStatus:"UNAVAILABLE"`, data served without predictions |
| FastAPI → Groq | `LLM_TIMEOUT_SECONDS` (20) | none | retrieval-only + `llm_status` |

### 7.5 Versioning
URL major version (`/api/v1`, `/internal/v1`); breaking change → v2 + spec update (§11.9).

### 7.6 GitHub endpoints used [C: GitHub REST; verify params at implementation]
`GET /user` · `GET /user/repos` · `GET /repos/{owner}/{repo}` · `GET /repos/{owner}/{repo}/issues?state=all&since=` · `GET /repos/{owner}/{repo}/pulls?state=all` · `GET /repos/{owner}/{repo}/pulls/{n}` · `GET /repos/{owner}/{repo}/pulls/{n}/reviews` · `GET /repos/{owner}/{repo}/commits?sha=&since=` · `GET /repos/{owner}/{repo}/readme` · `GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1` · `GET /repos/{owner}/{repo}/contents/{path}`.

### 7.7 Mocks & API testing
- GitHub: WireMock stubs from `fixtures/github/` (Spring tests); no real GitHub calls in CI.
- FastAPI from Spring: WireMock stub; FastAPI tests use `TestClient` + fake embedder (deterministic hash-based 384-dim vectors).
- Groq: `httpx.MockTransport` (200, 429, 500, timeout, malformed JSON).
- Contract check: Spring DTOs and FastAPI Pydantic schemas validated against shared JSON examples in `fixtures/contracts/` [P].
- Acceptance: every API-xx/ML-xx has ≥ 1 success test + ≥ 1 failure test (QA-API-*).

---

## 8. Simple Security Plan

Status of every control below: **planned, not verified** until its QA-SEC test passes.

### 8.1 Controls
| ID | Area | Control | Test |
|---|---|---|---|
| SEC-01 | Authentication | GitHub OAuth via Spring Security OAuth2 Client; `state` validated by framework | QA-SEC-01 |
| SEC-02 | Token storage | GitHub token AES-256-GCM (random 96-bit IV per encryption) with `TOKEN_ENCRYPTION_KEY`; never serialized to responses/logs | QA-SEC-02 |
| SEC-03 | Session | JWT HS256 (`JWT_SECRET` ≥ 256 bit), `exp` = `JWT_TTL_MINUTES`; cookie `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` when served over HTTPS (local HTTP: off) | QA-SEC-03 |
| SEC-04 | Authorization | Every repo-scoped query filters by `user_id`; non-owned → 404; IDs never trusted from body | QA-SEC-04 |
| SEC-05 | CSRF | Same-origin via Vite proxy + `SameSite=Lax` + state-changing endpoints require custom header `X-Requested-With: GitMedic` (cross-site forms cannot set it); webhook endpoint exempt (HMAC instead) | QA-SEC-05 |
| SEC-06 | Webhooks | HMAC-SHA256 over raw body, constant-time compare, reject before parse; max body 1 MB | QA-SEC-06 |
| SEC-07 | Internal API | FastAPI bound to `127.0.0.1`; `X-Internal-Token` required (constant-time compare); not callable from browser (no CORS) | QA-SEC-07 |
| SEC-08 | Input validation | Bean Validation (Spring) + Pydantic (FastAPI): types, lengths, enums, regex for `fullName`, `size ≤ 100` | QA-VAL-* |
| SEC-09 | SQL injection | JPA/parameterized queries only; vector search via parameterized SQL (`%s` placeholders in psycopg) | QA-SEC-08 |
| SEC-10 | XSS | React escapes by default; no `dangerouslySetInnerHTML`; `react-markdown` without raw HTML; HTML report built with escaped values only | QA-SEC-09 |
| SEC-11 | CORS | Not needed in dev (proxy); if enabled, allow only `FRONTEND_ORIGIN`, credentials true | QA-SEC-10 |
| SEC-12 | Security headers | Spring Security defaults + CSP for API responses (`default-src 'none'`) and HTML report download (`Content-Disposition: attachment`) | QA-SEC-11 |
| SEC-13 | Secrets | `.env` gitignored; `.env.example` placeholders only; gitleaks in CI; CI secrets only via GitHub encrypted secrets, never echoed | QA-SEC-12 |
| SEC-14 | Model files | joblib loaded only from `MODEL_DIR` paths built from integer `repo_id`; no user-supplied paths/uploads | QA-SEC-13 |
| SEC-15 | DB least privilege | Separate roles `gitmedic_app` / `gitmedic_ml`; schema not exposed via Supabase Data API; RLS deny-by-default for `anon`/`authenticated`; no Supabase `service_role` key used anywhere | QA-SEC-14 |
| SEC-16 | Rate limiting / abuse | Per-user limit on expensive endpoints (sync, train, index, report generate): 1 concurrent + 10/hour [P] via in-memory bucket; 409 if job running | QA-SEC-15 |
| SEC-17 | Prompt injection (LLM) | Issue/doc text treated as untrusted data in delimited blocks; LLM has no tools/actions; output is advisory text only, length-limited, citations validated (§5.8), rendered as plain markdown | QA-LLM-* |
| SEC-18 | LLM key | `GROQ_API_KEY` only in FastAPI env; never sent to Spring/React; absent key → feature disabled | QA-SEC-16 |
| SEC-19 | Error handling | Generic messages + `requestId`; no stack traces in responses; secrets/tokens/bodies redacted from logs | QA-SEC-17 |
| SEC-20 | Dependencies | Dependabot (npm, maven, pip, actions); CI `npm audit --audit-level=high`, `pip-audit`, OWASP Dependency-Check (Maven, P1) | CI job `security` |
| SEC-21 | Data minimization | §6.5; untrack deletes all repo data + models | QA-SEC-18 |
| SEC-22 | OAuth scope | Minimal `GITHUB_SCOPES`; private-repo scope opt-in (TBD-02) | Manual review |

### 8.2 Threats → mitigations → residual risk
| Threat | Mitigation | Residual |
|---|---|---|
| Token theft from DB dump | SEC-02 | Key on same machine as DB creds; acceptable for local scope |
| XSS steals session | SEC-03 HttpOnly, SEC-10 | Low |
| CSRF triggers sync/untrack | SEC-05 | Low |
| Forged webhook writes fake issues | SEC-06 | Secret leak → rotate |
| Another local process calls FastAPI | SEC-07 | Same-user malware out of scope |
| Supabase Data API exposes tables | SEC-15 | Misconfiguration risk → QA-SEC-14 checks with anon key returns nothing |
| Malicious issue text manipulates LLM answer | SEC-17 | Draft may still be wrong → UX-02 labels it as suggestion |
| Pickle RCE via model file | SEC-14 | Local file tampering out of scope |
| Vulnerable dependency | SEC-20 | Zero-days |

### 8.3 Consequences of misconfiguration
Missing `TOKEN_ENCRYPTION_KEY`/`JWT_SECRET`/`INTERNAL_API_TOKEN` → app refuses to start. Missing `GITHUB_WEBHOOK_SECRET` → webhook endpoint returns 503 (never accepts unsigned). Weak/shared secrets → forged sessions/webhooks. Data API exposing schema → repo content readable with anon key.

### 8.4 Out of scope (security)
Production TLS/WAF/DDoS, SOC2/compliance, pen-testing, HSM/KMS key management, multi-user isolation beyond `user_id` scoping.

### 8.5 CI-automatable checks
gitleaks (secrets) · npm audit · pip-audit · OWASP Dependency-Check · ESLint security-relevant rules (`react/no-danger`) · unit/integration security tests (QA-SEC-*). CodeQL = P2 (free for public repos; private availability TBD-15).

---

## 9. Testing & Quality Assurance

### 9.1 Strategy
| Level | Frontend | Spring Boot | FastAPI |
|---|---|---|---|
| Unit | Vitest + React Testing Library (components, hooks, formatters) | JUnit 5 + Mockito (services, risk-delta, report renderer, signature verifier, TokenCipher) | pytest (chunking, risk formula, alias map, feature building, citation validator) |
| Integration | MSW-mocked API page tests | `@SpringBootTest` + Testcontainers `pgvector/pgvector:pg17` + WireMock (GitHub, FastAPI) | pytest + pgvector service container + fake embedder |
| E2E | Playwright against local stack with `seed` profile and stubbed GitHub (P2) | — | — |
| Model | — | — | `@pytest.mark.model`: real MiniLM on fixtures (separate CI job, P1) |

No real GitHub, Supabase, or Groq calls in CI. Fixtures are deterministic; `random_state=42`.

### 9.2 Critical test cases
| ID | Scenario | Data / steps | Expected |
|---|---|---|---|
| QA-AUTH-01 | OAuth success | WireMock GitHub token + `/user` | User upserted, cookie set, redirect `/repos` |
| QA-AUTH-02 | OAuth denied / bad state | `error=access_denied` | Redirect `/login?error=oauth_failed`, no cookie |
| QA-AUTH-03 | Expired JWT | `exp` in past | 401 on `/api/v1/me` |
| QA-SYNC-01 | Initial sync counts | Fixture: 120 issues (+15 PRs in issues feed), 40 PRs, 300 commits | 120 issues, 40 PRs, 300 commits stored; status DONE |
| QA-SYNC-02 | PRs excluded from issues | Items with `pull_request` key | Not in `issues` (BR-02) |
| QA-SYNC-03 | Rate limit pause | `X-RateLimit-Remaining: 50` | Status RATE_LIMITED, resumes after reset (clock mocked) |
| QA-SYNC-04 | Idempotent resync | Run sync twice | No duplicate rows |
| QA-SYNC-05 | Concurrent sync | Two POST /sync | Second → 409 |
| QA-WH-01 | Valid `issues.opened` | Signed fixture | Issue upserted, ML called, 202 |
| QA-WH-02 | Untracked repo | Signed fixture, unknown repo | 204, no write |
| QA-ML-01 | Classifier trains | 60 labeled fixture issues, 3 classes | macro-F1 returned; model file + meta written |
| QA-ML-02 | Insufficient data | 10 labeled issues | 422 `INSUFFICIENT_TRAINING_DATA` |
| QA-ML-03 | Predict without model | — | 422 `MODEL_NOT_TRAINED`; Spring shows predictions null |
| QA-ML-04 | Duplicate recall | 20 known duplicate pairs (real MiniLM, model job) | ≥ 16 pairs in top-5 |
| QA-ML-05 | Self-match excluded | Search with issue's own number | Not in matches |
| QA-ML-06 | Risk formula | Hand-computed component table (≥ 6 cases incl. empty repo) | Exact score + level |
| QA-ML-07 | Bus factor | Commits 60/25/15 by A/B/C | 1, risky |
| QA-ML-08 | Merge-time | 30 merged PR fixture | Non-negative predictions; MAE returned |
| QA-RAG-01 | Index idempotent | Index same README twice | 2nd run `chunks_upserted=0` |
| QA-RAG-02 | Suggest retrieval-only | `LLM_ENABLED=false` | `mode=RETRIEVAL_ONLY`, sources link to GitHub |
| QA-LLM-01..05 | Groq 200 / 429 / 500 / timeout / malformed | MockTransport | OK draft / fallback with `RATE_LIMITED` / `UNAVAILABLE` / `UNAVAILABLE` / `INVALID_OUTPUT` |
| QA-LLM-06 | Fabricated citation | LLM cites unknown ID | Citations dropped, `UNVERIFIED_CITATIONS` |
| QA-RPT-01 | Weekly report content | Fixture week | All BR-11 fields correct |
| QA-RPT-02 | Unique per period | Generate twice | One row |
| QA-RPT-03 | Catch-up | Startup with missing previous period | Report created |
| QA-RPT-04 | Download formats | md, html, xml | 200, 200, 400 |
| QA-SEC-01..18 | Per §8.1 | e.g. QA-SEC-04: user B requests user A's repo → 404; QA-SEC-06: bad signature → 401 no write; QA-SEC-09: issue title `<script>` rendered as text in UI + HTML report; QA-SEC-14: anon role `select` on `gitmedic.*` fails | As stated |
| QA-VAL-01..n | Boundaries | `size=0/101`, `page=-1`, bad `fullName`, `top_k=0/21`, oversized body | 400 / clamped per spec |
| QA-RES-01 | FastAPI down | Stop stub | Dashboard 200 with `mlStatus:"UNAVAILABLE"` (NFR-008) |
| QA-UI-01 | Risk badge a11y | Render HIGH | Text "High" + score present; axe no violations |
| QA-UI-02 | Empty/loading/error states | MSW variants per page | Correct state rendered |
| QA-CFG-01 | Missing required env | Unset `JWT_SECRET` | Startup fails with named variable |
| QA-PERF-01 | List latency | 10k fixture issues, local | p95 < 500 ms (manual/local, not CI-gating) |

### 9.3 Commands
| Service | Lint/format | Type/static | Test | Build |
|---|---|---|---|---|
| frontend | `npm run lint` · `npm run format:check` | — (JavaScript; optional JSDoc `checkJs`, P2) | `npm test -- --run` | `npm run build` |
| backend | `./mvnw -B spotless:check` [P] | `javac` via build | `./mvnw -B verify` | `./mvnw -B -DskipTests package` |
| ml-service | `ruff check .` · `ruff format --check .` | `mypy app` (P1) | `pytest -m "not model"` · `pytest -m model` | `python -m compileall app` + app import smoke test |

### 9.4 Coverage expectation
No arbitrary global percentage. Required: every FR has ≥ 1 automated test or a documented manual check; every API-xx/ML-xx has success + failure tests; every SEC control has its QA-SEC test; BR-03/05/06/07/08/11 have unit tests with hand-computed expectations. Coverage reports (JaCoCo, coverage.py, Vitest) generated for visibility only.

### 9.5 Honesty rules
- A test is "passing" only with a CI run link or local command output as evidence.
- This document lists **proposed** tests; none have been executed at v1.0.0.
- Do not mark Appendix A items verified without evidence.
- Flaky tests are fixed or quarantined with an issue — never silently retried.

### 9.6 Definition of Done (per task / FR)
1. Code + tests merged via PR; CI green.
2. Acceptance criteria met with evidence.
3. No new lint warnings; no high/critical dependency findings (or documented exception).
4. Security controls for touched area tested.
5. Spec updated if behaviour changed (§11.9).

---

## 10. Implementation Roadmap
Deployment is not a milestone. Pri = highest FR priority covered.

### Phase 1 — Foundation
Goal: monorepo skeleton runs; CI skeleton green.
| Task | Description | Deps | Pri | Done when |
|---|---|---|---|---|
| T-1.1 | Create repo structure (§5.4), `.gitignore`, `.env.example` files, README stub | — | P0 | Tree matches §5.4 |
| T-1.2 | Scaffold Vite React (JS), Spring Boot 4.1 (web, security, oauth2-client, data-jpa, validation, flyway, actuator), FastAPI app with `/health` | T-1.1 | P0 | All three start locally |
| T-1.3 | Lint/format config (ESLint+Prettier, Spotless, Ruff) | T-1.2 | P0 | Commands in §9.3 pass |
| T-1.4 | Minimal `ci.yml` (install, lint, build) | T-1.3 | P0 | CI green on push/PR |
Tests: smoke tests per service.

### Phase 2 — Data model & core architecture
| Task | Description | Deps | Pri | Done when |
|---|---|---|---|---|
| T-2.1 | Supabase project + roles (`gitmedic_app`, `gitmedic_ml`); Docker pgvector fallback | T-1.2 | P0 | Both reachable |
| T-2.2 | Flyway V1/V2: extension, schema, DS-001…DS-008, RLS, grants | T-2.1 | P0 | Migrations apply on Supabase + Testcontainers |
| T-2.3 | JPA entities/repositories; psycopg pool in FastAPI | T-2.2 | P0 | Integration test reads/writes |
| T-2.4 | Fixtures `fixtures/github/` + `seed` profile | T-2.3 | P0 | Seeded DB renders data |
Tests: QA-SEC-14, migration test.

### Phase 3 — Core backend (build-order steps 1–3)
| Task | Description | Deps | Pri | Done when |
|---|---|---|---|---|
| T-3.1 | OAuth login, TokenCipher, JWT cookie, `/me`, logout | T-2.3 | P0 | QA-AUTH-01..03, QA-SEC-01..03 |
| T-3.2 | GitHubClient (pagination, ETag, rate-limit guard, retries) | T-3.1 | P0 | WireMock tests |
| T-3.3 | Repo list/track/untrack (API-05..08) | T-3.2 | P0/P1 | QA-SEC-04 |
| T-3.4 | Sync service: issues, PRs (+details/reviews), commits; status; manual sync (API-09) | T-3.3 | P0 | QA-SYNC-01..05 |
| T-3.5 | Webhook receiver (API-22) | T-3.4 | P1 | QA-WH-*, QA-SEC-06 |
| T-3.6 | Heatmap, bus factor, stats (API-11, 12) | T-3.4 | P1 | QA-ML-07 |
| T-3.7 | Error model, validation, requestId logging | T-3.1 | P0 | QA-VAL-*, QA-SEC-17 |

### Phase 4 — Frontend
| Task | Description | Deps | Pri | Done when |
|---|---|---|---|---|
| T-4.1 | Choose & document visual direction per §4.1 (TBD-12) | — | P0 | Recorded in frontend README |
| T-4.2 | App shell, routing, auth guard, API client (`X-Requested-With`), Vite proxy | T-3.1 | P0 | Login → Repo List works |
| T-4.3 | Repo List + Add modal + sync status | T-3.3 | P0 | QA-UI-02 |
| T-4.4 | Dashboard (risk, stats, heatmap, bus factor, model status) | T-3.6 | P0/P1 | QA-UI-01 |
| T-4.5 | Issues, Issue Detail, Pull Requests | T-5.x | P0/P1 | MSW tests |
| T-4.6 | Reports list/detail/download | T-5.6 | P0/P1 | — |
| T-4.7 | Accessibility & responsive pass | T-4.3–4.6 | P0 | axe clean; 360 px usable |

### Phase 5 — ML & RAG (build-order steps 4–7)
| Task | Description | Deps | Pri | Done when |
|---|---|---|---|---|
| T-5.1 | Internal token auth, MlClient in Spring, degradation path | T-1.2, T-3.7 | P0 | QA-SEC-07, QA-RES-01 |
| T-5.2 | Label classifier train/predict (ML-02/03, API-16) | T-5.1, T-3.4 | P0 | QA-ML-01..03 |
| T-5.3 | Chunking, embedder, index (ML-07, API-17), docs fetch in Spring | T-5.1 | P0 | QA-RAG-01 |
| T-5.4 | Duplicate detection (ML-08) on sync + webhook | T-5.3 | P0 | QA-ML-04, 05 |
| T-5.5 | Suggested answer retrieval-only (ML-09) | T-5.3 | P1 | QA-RAG-02 |
| T-5.6 | Risk score (ML-06) + history; weekly report scheduler, catch-up, generate now, md/html | T-3.6 | P0/P1 | QA-ML-06, QA-RPT-* |
| T-5.7 | Merge-time predictor (ML-04/05) | T-3.4 | P1 | QA-ML-08 |
| T-5.8 | Optional Groq adapter (FR-018) after TBD-05 verified | T-5.5 | P2 | QA-LLM-* |

### Phase 6 — Security hardening
T-6.1 Rate limits on expensive endpoints (SEC-16) · T-6.2 headers/CSP (SEC-12) · T-6.3 log redaction review · T-6.4 full QA-SEC suite green. Deps: Phases 3–5. Pri P0.

### Phase 7 — Testing & bug fixing
T-7.1 Fill coverage gaps per §9.4 · T-7.2 tune `DUPLICATE_THRESHOLD` on fixtures (TBD-03) · T-7.3 local perf check QA-PERF-01 · T-7.4 Playwright happy path (P2). Done: all P0/P1 tests green.

### Phase 8 — CI/CD completion
T-8.1 Full `ci.yml` per §11.6 (test, services, caching, security job) · T-8.2 Dependabot config · T-8.3 branch protection requiring CI checks · T-8.4 model-test job with HF cache (P1). Done: 3 consecutive green runs on `main`.

### Phase 9 — Final local validation & docs
T-9.1 Fresh-clone setup following §11 only · T-9.2 end-to-end manual run on a real repo (sync, train, duplicates, report) · T-9.3 README + this spec synced; Appendix A updated with evidence. Done: completion criteria §2.8.

---

## 11. Local Development, CI/CD & Maintenance

### 11.1 Prerequisites
| Tool | Version | Notes |
|---|---|---|
| Git | current | — |
| JDK | 21 LTS (Temurin or other OpenJDK) | Maven via wrapper `./mvnw` |
| Node.js | 24 LTS (≥ 22.12) | npm |
| Python | 3.12 | venv |
| GitHub CLI | current + `gh extension install cli/gh-webhook` | Webhook forwarding (dev only) |
| Docker | optional | Local pgvector fallback + Testcontainers |
| Accounts | GitHub (OAuth App), Supabase Free | No paid plans |

### 11.2 One-time setup
1. GitHub → Settings → Developer settings → OAuth Apps → New: Homepage `http://localhost:5173`, Callback `http://localhost:8080/login/oauth2/code/github`.
2. Supabase → new project → copy **session pooler** connection string (IPv4-compatible; verify) → run the role SQL (kept in the README) to create `gitmedic_app`/`gitmedic_ml` with strong passwords.
3. Confirm Supabase Data API does not expose schema `gitmedic` (TBD-08).
4. Copy `.env.example` → `.env` in `backend/` and `ml-service/`; fill values; generate secrets: `openssl rand -base64 32`.
5. Alternative DB: `docker compose up -d db` (image `pgvector/pgvector:pg17`).

### 11.3 Environment template (placeholders only)
```dotenv
# backend/.env
DB_URL=jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require
DB_USER=gitmedic_app
DB_PASSWORD=<change-me>
GITHUB_CLIENT_ID=<oauth-client-id>
GITHUB_CLIENT_SECRET=<oauth-client-secret>
GITHUB_SCOPES=read:user
GITHUB_WEBHOOK_SECRET=<random-32-chars>
JWT_SECRET=<base64-32-bytes>
JWT_TTL_MINUTES=480
TOKEN_ENCRYPTION_KEY=<base64-32-bytes>
ML_BASE_URL=http://127.0.0.1:8000
INTERNAL_API_TOKEN=<random-32-chars>
FRONTEND_ORIGIN=http://localhost:5173
APP_TIMEZONE=UTC
REPORT_CRON=0 0 6 * * MON

# ml-service/.env
ML_DB_DSN=postgresql://gitmedic_ml:<change-me>@<pooler-host>:5432/postgres?sslmode=require
INTERNAL_API_TOKEN=<same-as-backend>
MODEL_DIR=./data/models
EMBEDDING_MODEL=sentence-transformers/all-MiniLM-L6-v2
DUPLICATE_THRESHOLD=0.80
DUPLICATE_TOP_K=5
LLM_ENABLED=false
LLM_PROVIDER=groq
LLM_MODEL=<verify-in-groq-console>
GROQ_API_KEY=<optional>
LLM_TIMEOUT_SECONDS=20
```

### 11.4 Run locally (3 terminals + optional 4th)
```bash
# ml-service
cd ml-service && python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements-dev.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000

# backend (Flyway migrates on start)
cd backend && ./mvnw spring-boot:run

# frontend (proxies /api, /oauth2, /login/oauth2 → :8080)
cd frontend && npm ci && npm run dev

# webhooks (optional, dev only; needs admin on the repo)
gh webhook forward --repo=OWNER/REPO --events=issues,pull_request,push --url=http://localhost:8080/api/v1/webhooks/github
```
Open `http://localhost:5173`.

### 11.5 Troubleshooting
| Symptom | Cause | Fix |
|---|---|---|
| Backend can't reach DB | Supabase project paused (7 days idle) / IPv6-only direct host | Resume in dashboard; use session pooler string |
| `relation "gitmedic.…" does not exist` in FastAPI | Backend not started (migrations) | Start backend once first |
| OAuth `redirect_uri_mismatch` | Callback URL differs | Match OAuth App callback exactly |
| Login loop | Cookie not sent (not using proxy) | Access via `:5173`, not `:8080` |
| `mlStatus: UNAVAILABLE` | FastAPI down / token mismatch | Start service; align `INTERNAL_API_TOKEN` |
| Slow first prediction | Model download/load | Wait for first load; cached afterwards |
| Webhook 401 | Secret mismatch | Align `GITHUB_WEBHOOK_SECRET` with forwarder (TBD-13) |
| Sync `RATE_LIMITED` | GitHub quota | Wait until reset time shown |

### 11.6 CI/CD (GitHub Actions) — validation only, **no deployment**
| Item | Spec |
|---|---|
| Triggers | `push` (all branches), `pull_request` (→ `main`); `workflow_dispatch` |
| Runner | `ubuntu-latest` (standard; free for public repos, 2,000 min/month for private on GitHub Free) |
| Concurrency | `group: ci-${{ github.ref }}`, `cancel-in-progress: true` |
| Permissions | `contents: read` |
| Caching | `setup-node` (npm), `setup-java` (maven), `setup-python` (pip), `actions/cache` for HF model (model job) |
| Jobs | `frontend` · `backend` · `ml` · `ml-model` (P1) · `security` |
| Failure | Any failing step fails the job; required checks block PR merge via branch protection |
| Success | All required jobs green |
| Secrets | None required for default suite; `NVD_API_KEY` optional for Dependency-Check (step skipped if absent) |

```yaml
# .github/workflows/ci.yml (reference)
name: CI
on:
  push:
  pull_request:
    branches: [main]
  workflow_dispatch:
permissions: { contents: read }
concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }
jobs:
  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 24, cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run lint
      - run: npm run format:check
      - run: npm test -- --run
      - run: npm run build
  backend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: backend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21, cache: maven }
      - run: ./mvnw -B spotless:check
      - run: ./mvnw -B verify          # unit + Testcontainers integration tests
  ml:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: ml-service } }
    services:
      postgres:
        image: pgvector/pgvector:pg17
        env: { POSTGRES_PASSWORD: postgres }
        ports: ["5432:5432"]
        options: --health-cmd pg_isready --health-interval 5s --health-retries 10
    env:
      ML_DB_DSN: postgresql://postgres:postgres@localhost:5432/postgres
      INTERNAL_API_TOKEN: ci-test-token
      LLM_ENABLED: "false"
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: "3.12", cache: pip }
      - run: pip install torch --index-url https://download.pytorch.org/whl/cpu
      - run: pip install -r requirements-dev.txt
      - run: ruff check . && ruff format --check .
      - run: pytest -m "not model"
  security:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: gitleaks
        run: docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:<pinned-version> detect --source=/repo
      - uses: actions/setup-node@v4
        with: { node-version: 24 }
      - run: cd frontend && npm ci && npm audit --audit-level=high
      - uses: actions/setup-python@v5
        with: { python-version: "3.12" }
      - run: pip install pip-audit && pip-audit -r ml-service/requirements.txt
```
Pin action and image versions (major tag or SHA) at implementation; verify current majors. `ml` job applies the Flyway SQL files from `backend/src/main/resources/db/migration/` in a pytest session fixture (single schema source; Spring doesn't run there) [P].

### 11.7 Git conventions
- `main` protected (PR + green CI). Branches `feat/…`, `fix/…`, `chore/…`, `docs/…`, `test/…`.
- Conventional Commits (`feat(sync): add PR review count`). Small PRs referencing FR/T IDs.
- Never commit `.env`, model files, real fixtures containing private repo data.

### 11.8 Dependency maintenance
Dependabot weekly for npm, maven, pip, github-actions (grouped minor/patch). Review majors manually (Spring Boot, React, Vite, scikit-learn — retrain models after sklearn upgrades since joblib files are version-sensitive). Re-verify free-tier limits quarterly (§12.5).

### 11.9 Keeping this spec in sync
Behaviour change → update relevant FR/API/DS/SEC/QA entries in the same PR; bump version (patch = clarification, minor = new/changed requirement, major = scope change); add Change History row.

---

## 12. Risks, Open Questions & Change History

### 12.1 Risks
| ID | Risk | Type | Likelihood/Impact | Mitigation |
|---|---|---|---|---|
| R-01 | Supabase free project pauses after 7 idle days → app/report failures | External | High/Med | Clear startup error; resume; Docker fallback; BR-12 catch-up |
| R-02 | 500 MB DB limit reached (embeddings + text) | External | Med/Med | BR-13 caps; body truncation; monitor size via `pg_database_size` in health |
| R-03 | Webhooks unusable without public URL | Technical | Certain/Low | `gh webhook forward` + manual sync (FR-007) |
| R-04 | GitHub rate limits on large repos (PR details/reviews) | External | Med/Med | Caps, ETags, pause/resume |
| R-05 | Small repos → poor/no ML models | Technical | High/Med | Minimums, "insufficient data" UX, confidence shown |
| R-06 | Duplicate threshold mis-tuned → false positives | Technical | Med/Med | Fixture tuning (TBD-03), "possible" wording, score shown |
| R-07 | Free-tier terms/limits change (Groq/Gemini/Supabase/Actions) | External | Med/Low | LLM optional; re-verify quarterly |
| R-08 | Torch install size slows CI | Performance | Med/Low | CPU wheels, pip cache, model tests isolated |
| R-09 | joblib/scikit-learn version drift breaks saved models | Maintainability | Med/Low | Store sklearn version in meta; retrain on mismatch |
| R-10 | Three-service setup complexity for newcomers | Maintainability | Med/Med | §11 step-by-step, fail-fast config |
| R-11 | Private repo content sent to LLM provider | Privacy | Low (off by default)/High | Disabled by default; TBD-05 terms check; warn in UI when enabled for private repos |
| R-12 | Weekly scheduler misses runs (machine off) | Technical | High/Low | Catch-up BR-12, "Generate now" |

### 12.2 Known limitations
LIM-1…LIM-3 (§1.5) · English-focused embeddings · heuristic risk score (not validated against real abandonment outcomes) · single-user scope · no write-back to GitHub.

### 12.3 Assumptions requiring verification
A-1 Personas (§1.2) · A-2 Supabase Free lacks guaranteed backups · A-3 Supabase direct connection IPv6-only on free tier (use pooler) · A-4 GitHub 5,000 req/h user-token limit and ETag 304 behaviour · A-5 `all-MiniLM-L6-v2` download size/token limit · A-6 Spring Boot 4.1 starter names (e.g., Flyway module) · A-7 `pgvector/pgvector:pg17` matches Supabase PG major.

### 12.4 Open questions / TBD
| ID | Decision needed | Why it matters | Affects |
|---|---|---|---|
| TBD-01 | Confirm Supabase as DB (earlier project notes referenced Neon; `GitMedic.md` says Supabase — this spec follows `GitMedic.md`) | Connection, roles, limits | §5–6, §11 |
| TBD-02 | Support private repos? (requires broad `repo` scope) | Security/scope, data sensitivity | FR-004/005, SEC-22 |
| TBD-03 | Final `DUPLICATE_THRESHOLD` | Precision vs recall | FR-016, BR-03 |
| TBD-04 | Label alias map incl. `help wanted`; per-repo override needed? | Training data quality | BR-04, FR-011 |
| TBD-05 | Groq model ID, free-plan limits, card requirement, data-retention terms | Feasibility/privacy of FR-018 | §5.8 |
| TBD-06 | Validate risk weights/formulas | Score credibility | BR-05, FR-014 |
| TBD-07 | Who writes predictions (Spring vs FastAPI) | DB grants | §6.4 |
| TBD-08 | Supabase role creation, RLS policies, Data API exposed schemas | Least privilege | SEC-15 |
| TBD-09 | Report timezone/cron default | Report periods | BR-11, FR-019 |
| TBD-10 | Risk semantics for empty/new repos | Edge-case behaviour | BR-05 |
| TBD-11 | Is Playwright E2E required in CI? | CI time vs confidence | §9.1, Phase 7 |
| TBD-12 | Visual direction (earlier iteration specified Glassmorphism + Claymorphism; current requirement forbids mandating a style) | Frontend consistency | §4 |
| TBD-13 | Webhook signature in dev with `gh webhook forward` (secret flag support) | FR-006 testing | §5.9 |
| TBD-14 | Sync caps (BR-13) values | Storage + rate limits | FR-005 |
| TBD-15 | GitMedic's own repo public or private | Actions minutes, CodeQL availability | §11.6 |

### 12.5 Externally verified details (as of 2026-10-25)
| Detail | Source | Status |
|---|---|---|
| Supabase Free: 500 MB DB, 2 active projects, pause after 1 week inactivity | supabase.com/pricing | Verified |
| GitHub Actions: standard runners free for public repos; GitHub Free private = 2,000 min/month | docs.github.com billing | Verified |
| Groq: free plan, org-level per-model limits, 429 on exceed, exact limits in console | console.groq.com/docs/rate-limits | Verified (numbers not fixed here) |
| Gemini: free tier per project per model; Pro off free tier since 2026-04 | ai.google.dev + secondary source | Partially verified |
| Spring Boot 4.x current; 3.5 OSS ended 2026-06-30; 4.0 OSS ends 2026-12-31 | secondary sources | Verify on spring.io |
| `gh webhook forward` for local testing | docs.github.com webhooks | Verified |

---

## Appendix A — Documentation Completeness Checklist

**Sections**
- [x] 1 Overview · [x] 2 PRD · [x] 3 Flows · [x] 4 UI/UX · [x] 5 TRD · [x] 6 Data · [x] 7 API · [x] 8 Security · [x] 9 QA · [x] 10 Roadmap · [x] 11 Local/CI · [x] 12 Risks — all present, in order, written.

**Scope & constraints**
- [x] Deployment marked OUT OF SCOPE; no deploy jobs, hosting or URLs.
- [x] CI validates lint/format/tests/build/security on push + PR.
- [x] No mandatory paid dependency; LLM optional with retrieval-only fallback.
- [x] No visual style imposed; functional UI/a11y requirements kept.
- [x] Confirmed / Proposed / Assumption / TBD / OOS labels applied.

**Not yet verified (requires implementation evidence)**
- [ ] Any test in §9 executed — none executed at v1.0.0.
- [ ] Security controls SEC-01…SEC-22 verified.
- [ ] CI workflow green.
- [ ] Performance targets (NFR-003).

**Decisions still TBD:** TBD-01 … TBD-15 (§12.4); P0-blocking: TBD-01, TBD-02, TBD-08.

**External details still to verify:** Groq model/limits/terms (TBD-05), Gemini free-tier terms, Spring Boot 4.1 module names (A-6), Supabase connection/backup specifics (A-2, A-3), GitHub rate-limit/ETag behaviour (A-4), pinned action/image versions.

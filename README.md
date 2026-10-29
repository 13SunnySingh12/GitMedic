# GitMedic

Repository health and issue triage for GitHub maintainers. GitMedic syncs the repositories you
choose, flags possible duplicate issues, predicts issue labels and pull-request merge times,
scores abandonment risk, shows contributor activity and bus factor, drafts evidence-based
answers from closed issues and docs, and produces a weekly report.

It runs locally only. GitHub Actions validate every push; nothing is deployed.
The full specification is [GitMedic_Complete.md](GitMedic_Complete.md).

| Service | Path | Stack | Port |
|---|---|---|---|
| Frontend | [frontend/](frontend/) | React 19 + Vite (JavaScript), TanStack Query, Motion | 5173 |
| Backend | [backend/](backend/) | Spring Boot 4.1, Java 21, Flyway, JDBC | 8080 |
| ML/RAG | [ml-service/](ml-service/) | FastAPI, scikit-learn, sentence-transformers (all-MiniLM-L6-v2) | 8000 (loopback only) |
| Database | Supabase Postgres + pgvector, or the local Docker fallback | PostgreSQL 17 | 5433 (local) |

The browser only talks to Spring (through the Vite proxy). Spring owns GitHub access, users,
sync, webhooks, reports and all migrations; FastAPI owns models, embeddings and vector search and
never calls GitHub.

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 (Temurin or another OpenJDK) |
| Node.js | 24 LTS (22.12 or later) |
| Python | 3.12 |
| Docker | Needed for the local database and for backend tests (Testcontainers) |
| GitHub CLI | Optional, for webhook forwarding in development |

## 1. One-time setup

### GitHub OAuth App

GitHub, Settings, Developer settings, OAuth Apps, New OAuth App:

- Homepage URL: `http://localhost:5173`
- Authorization callback URL: `http://localhost:8080/login/oauth2/code/github`

Keep the client ID and generate a client secret; they go into `backend/.env` (step 3).
The default scope `read:user` covers public repositories. Private repositories need the `repo`
scope (`GITHUB_SCOPES=read:user,repo`); that grants GitMedic read and write access to them on
GitHub, although GitMedic only ever reads.

### Database: choose one

**Local Docker (simplest).** Copy `.env.example` to `.env` in the repository root and set three
strong passwords, then:

```bash
docker compose up -d db
```

This starts `pgvector/pgvector:pg17` on `127.0.0.1:5433` and creates the `gitmedic_app` and
`gitmedic_ml` roles from [backend/db/setup-roles.sql](backend/db/setup-roles.sql).

**Supabase.** Create a project, then run the role setup once as the `postgres` user, with
passwords you choose (they never appear in the file):

```bash
psql "<session-pooler-connection-string>" -v app_password='<pw1>' -v ml_password='<pw2>' -f backend/db/setup-roles.sql
```

(Alternatively, run the script's statements in the dashboard's SQL editor with your passwords in
place of the variables.) Use the **session pooler** connection (IPv4-compatible, port 5432).
Through the pooler, the user name is `<role>.<project-ref>`, for example `gitmedic_app.abcd1234`.
Check that the Data API "exposed schemas" list does not include `gitmedic`. GitMedic never uses
the Supabase `service_role` key, Auth, Storage or Data API.

To keep a local-database `.env` and a Supabase one side by side, put the Supabase values in
`.env.supabase` (gitignored) and select it per run with `GITMEDIC_ENV_FILE=.env.supabase`; both the
backend and the ML service honour it.

### 2. Secrets

Generate each secret separately:

```bash
openssl rand -base64 32
```

`JWT_SECRET` and `TOKEN_ENCRYPTION_KEY` must be base64 of 32 bytes (that command's output).
`INTERNAL_API_TOKEN` (32+ characters) must be identical in both services.

### 3. Environment files

Copy `backend/.env.example` to `backend/.env` and `ml-service/.env.example` to `ml-service/.env`,
then fill in the values. All `.env` files are gitignored. For the local Docker database:

- `DB_URL=jdbc:postgresql://127.0.0.1:5433/gitmedic`, `DB_USER=gitmedic_app`
- `ML_DB_DSN=postgresql://gitmedic_ml:<pw2>@127.0.0.1:5433/gitmedic`

On Windows, use `127.0.0.1` rather than `localhost` in these addresses: `localhost` tries IPv6
first and the container publishes IPv4 only.

## 2. Run

Start the backend once before the ML service on a new database: the backend applies the Flyway
migrations that the ML service reads.

```bash
cd backend
./mvnw spring-boot:run
```

```bash
cd ml-service
python -m venv .venv
.venv/Scripts/activate   # macOS/Linux: source .venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements-dev.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

```bash
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173` (always the Vite address: the session cookie and API share that
origin). The first duplicate search or index downloads the embedding model (about 90 MB) once.

### Demo data without GitHub

The dev-only `seed` profile loads the synthetic data in [fixtures/github/](fixtures/github/)
for a local user and runs the real ML pipeline on it (index, train both models, duplicates, risk):

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
```

Only use it against a local database. Signing in still requires the OAuth App.

### Webhooks (optional, development only)

GitHub cannot reach `localhost`, so webhooks arrive through the GitHub CLI while it runs. You
need admin rights on the repository:

```bash
gh extension install cli/gh-webhook
gh webhook forward --repo=OWNER/REPO --events=issues,pull_request,push --url=http://localhost:8080/api/v1/webhooks/github --secret=<GITHUB_WEBHOOK_SECRET>
```

Without webhooks, use **Sync now**; it fetches everything changed since the last sync.

## Tests and checks

| Service | Commands |
|---|---|
| Frontend | `npm run lint` · `npm run format:check` · `npm test -- --run` · `npm run build` |
| Backend | `./mvnw -B spotless:check` · `./mvnw -B verify` (needs Docker) |
| ML | `ruff check .` · `ruff format --check .` · `mypy app` · `pytest -m "not model"` · `pytest -m model` |

The ML tests rebuild the schema from the backend migrations in a **disposable** database named by
`ML_TEST_DSN`, for example:

```bash
docker run -d --rm --name gm-test-db -e POSTGRES_PASSWORD=throwaway -e POSTGRES_DB=gitmedic -p 127.0.0.1:5434:5432 pgvector/pgvector:pg17
```

```bash
ML_TEST_DSN=postgresql://postgres:throwaway@127.0.0.1:5434/gitmedic pytest -m "not model"
```

The suite refuses to run against a Supabase host. CI ([.github/workflows/ci.yml](.github/workflows/ci.yml))
runs all of the above plus gitleaks, `npm audit`, `pip-audit` and, when an `NVD_API_KEY` secret
exists, OWASP Dependency-Check. No tests call GitHub, Supabase or an LLM.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Backend exits with "Database unreachable" | Resume the Supabase project (it pauses after 7 idle days), check the pooler host, or `docker compose up -d db` |
| ML log: "relation gitmedic.… does not exist" | Start the backend once so Flyway migrates |
| OAuth `redirect_uri_mismatch` | The OAuth App callback must be exactly `http://localhost:8080/login/oauth2/code/github` |
| Sign-in loops back to the login page | Open the app at `:5173`, not `:8080` |
| Pages show "ML service is unavailable", or `/actuator/health` shows `ml: DEGRADED` | Start FastAPI; make `INTERNAL_API_TOKEN` identical in both `.env` files |
| Sync shows "Paused (rate limit)" | It resumes automatically after the GitHub reset time shown |
| Webhook deliveries answer 401 | `GITHUB_WEBHOOK_SECRET` must match the forwarder's `--secret` |

## Limitations

- The weekly report scheduler only runs while the backend runs; a missed week is generated at the
  next start.
- Small repositories may not have enough labelled issues (30) or merged pull requests (20) to train.
- The first sync of a large repository is slow on CPU: embedding 10,000 issues took about 5.5 minutes
  in local measurements. Later syncs only re-embed issues that changed.
- Embeddings are English-focused; other languages work with lower accuracy.
- The risk score is a heuristic and has not been validated against real abandonment outcomes.
- Optional LLM polishing of answers (Groq, FR-018) is not enabled; answers are retrieval-only.

## Backup

Everything is re-derivable by re-syncing and retraining. For a snapshot of a local database:

```bash
docker compose exec db pg_dump -U postgres -n gitmedic gitmedic > gitmedic-backup.sql
```

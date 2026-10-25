# GitMedic

Repository health and issue triage for GitHub maintainers: duplicate detection, label and
merge-time predictions, abandonment-risk scoring and weekly reports. Local-only; CI validates,
nothing deploys.

The full specification is [GitMedic_Complete.md](GitMedic_Complete.md).

| Service | Path | Stack |
|---|---|---|
| Frontend | `frontend/` | React + Vite (JavaScript) |
| Backend | `backend/` | Spring Boot 4.1, Java 21, Flyway |
| ML/RAG | `ml-service/` | FastAPI, scikit-learn, sentence-transformers |
| Database | Supabase Postgres + pgvector (or `docker compose up -d db`) | |

Setup instructions are being written as the services come together.

"""Test harness. Needs a DISPOSABLE Postgres+pgvector in ML_TEST_DSN: the schema is dropped and
rebuilt from the backend's Flyway SQL files (single schema source, §11.6)."""

import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlparse

import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"
FIXTURES = ROOT / "fixtures/github"
TOKEN = "test-internal-token-000000000000000"

TEST_DSN = os.environ.get("ML_TEST_DSN", "")
if not TEST_DSN:
    pytest.exit("Set ML_TEST_DSN to a disposable Postgres+pgvector database.", returncode=2)
if "supabase" in (urlparse(TEST_DSN).hostname or ""):
    pytest.exit("ML_TEST_DSN must not point at Supabase: the test schema is dropped.", returncode=2)

os.environ.update(
    ML_DB_DSN=TEST_DSN,
    INTERNAL_API_TOKEN=TOKEN,
    MODEL_DIR=tempfile.mkdtemp(prefix="gitmedic-models-"),
    LLM_ENABLED="false",
)


def _rebuild_schema() -> None:
    with psycopg.connect(TEST_DSN, autocommit=True) as c:
        # Roles exist before migrating so V2 creates the gitmedic_ml grants/policies (QA-SEC-14).
        for role in ("gitmedic_ml", "gm_anon_sim"):
            c.execute(
                f"do $$ begin if not exists (select 1 from pg_roles where rolname = '{role}')"
                f" then create role {role} nologin; end if; end $$"
            )
        c.execute("drop schema if exists gitmedic cascade")
        c.execute("create schema gitmedic")
        version = lambda p: int(re.findall(r"\d+", p.name)[0])  # noqa: E731
        for f in sorted(MIGRATIONS.glob("V*.sql"), key=version):
            c.execute(f.read_text(encoding="utf-8"))


_rebuild_schema()


class FakeEmbedder:
    """Deterministic bag-of-words hashing into 384 dims: shared words → high cosine."""

    def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for t in texts:
            v = [0.0] * 384
            for w in re.findall(r"[a-z0-9]+", t.lower()):
                v[int(hashlib.md5(w.encode()).hexdigest(), 16) % 384] += 1.0  # noqa: S324
            norm = sum(x * x for x in v) ** 0.5 or 1.0
            out.append([x / norm for x in v])
        return out


@pytest.fixture
def db():
    with psycopg.connect(TEST_DSN, autocommit=True) as c:
        c.execute(
            "truncate gitmedic.users, gitmedic.repos, gitmedic.issues, gitmedic.pull_requests,"
            " gitmedic.commits, gitmedic.embeddings, gitmedic.repo_health,"
            " gitmedic.weekly_reports restart identity cascade"
        )
        yield c


@pytest.fixture
def client(db):
    import shutil

    from fastapi.testclient import TestClient

    # Repo ids restart at 1 per test, so model files from earlier tests must not survive.
    model_dir = Path(os.environ["MODEL_DIR"])
    shutil.rmtree(model_dir, ignore_errors=True)
    model_dir.mkdir()

    from app.main import app
    from app.rag.embedder import get_embedder

    app.dependency_overrides[get_embedder] = FakeEmbedder
    with TestClient(app, headers={"X-Internal-Token": TOKEN}) as c:
        yield c
    app.dependency_overrides.clear()


def fixture(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def make_repo(db, github_repo_id: int = 123456) -> int:
    user = db.execute(
        "insert into gitmedic.users (github_id, login, access_token_enc) values (1, 'u', '\\x00')"
        " on conflict (github_id) do update set login = excluded.login returning id"
    ).fetchone()[0]
    return db.execute(
        "insert into gitmedic.repos"
        " (user_id, github_repo_id, full_name, default_branch, is_private)"
        " values (%s, %s, 'octo/demo', 'main', false) returning id",
        (user, github_repo_id),
    ).fetchone()[0]


def load_issues(db, repo_id: int) -> None:
    """Loads fixtures/github/issues.json the way Spring stores them (PR items excluded, BR-02)."""
    for i in fixture("issues.json"):
        if "pull_request" in i:
            continue
        db.execute(
            """insert into gitmedic.issues (repo_id, github_issue_id, number, title, body, state,
                 author_login, gh_labels, comments_count, html_url, created_at_gh, updated_at_gh,
                 closed_at_gh)
               values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            (
                repo_id,
                i["id"],
                i["number"],
                i["title"],
                i["body"],
                i["state"],
                i["user"]["login"],
                [lbl["name"] for lbl in i["labels"]],
                i["comments"],
                i["html_url"],
                i["created_at"],
                i["updated_at"],
                i["closed_at"],
            ),
        )


def load_pulls(db, repo_id: int) -> None:
    details, reviews = fixture("pull_details.json"), fixture("pull_reviews.json")
    for p in fixture("pulls.json"):
        d = details[str(p["number"])]
        db.execute(
            """insert into gitmedic.pull_requests (repo_id, github_pr_id, number, title, state,
                 author_login, additions, deletions, changed_files, review_count, html_url,
                 created_at_gh, merged_at_gh, closed_at_gh)
               values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            (
                repo_id,
                p["id"],
                p["number"],
                p["title"],
                p["state"],
                p["user"]["login"],
                d["additions"],
                d["deletions"],
                d["changed_files"],
                len(reviews[str(p["number"])]),
                p["html_url"],
                p["created_at"],
                p["merged_at"],
                p["closed_at"],
            ),
        )

"""QA-ML-04 (model job): real all-MiniLM-L6-v2 finds ≥ 16 of 20 known duplicate pairs in top-5."""

import pytest

from app.rag.embedder import MiniLMEmbedder
from tests.conftest import fixture, load_issues, make_repo

pytestmark = pytest.mark.model


def test_qa_ml_04_duplicate_recall_at_5(client, db):
    from app.main import app
    from app.rag.embedder import get_embedder

    real = MiniLMEmbedder("sentence-transformers/all-MiniLM-L6-v2")
    app.dependency_overrides[get_embedder] = lambda: real
    repo = make_repo(db)
    issues = [i for i in fixture("issues.json") if "pull_request" not in i]
    load_issues(db, repo)
    docs = [
        {
            "source_type": "issue",
            "source_ref": str(i["number"]),
            "source_url": i["html_url"],
            "text": f"{i['title']}\n{(i['body'] or '')[:1500]}",
        }
        for i in issues
    ]
    assert client.post("/internal/v1/index", json={"repo_id": repo, "documents": docs}).is_success
    by_number = {i["number"]: i for i in issues}
    found, scores = 0, []
    for original, duplicate in fixture("duplicate_pairs.json"):
        d = by_number[duplicate]
        r = client.post(
            "/internal/v1/duplicates",
            json={
                "repo_id": repo,
                "issue_number": duplicate,
                "title": d["title"],
                "body": d["body"],
            },
        ).json()
        hits = {m["issue_number"]: m["score"] for m in r["matches"]}
        if original in hits:
            found += 1
            scores.append(hits[original])
    print(f"recall@5 = {found}/20; pair scores min={min(scores):.3f} max={max(scores):.3f}")
    assert found >= 16

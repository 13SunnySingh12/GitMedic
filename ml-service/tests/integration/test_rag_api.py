"""ML-07 … ML-09: indexing idempotency, duplicate search, retrieval-only suggestions."""

import pytest

from app.db.pool import vector_literal
from app.rag import search
from tests.conftest import FakeEmbedder, fixture, load_issues, make_repo


def _issue_docs(issues):
    return [
        {
            "source_type": "issue",
            "source_ref": str(i["number"]),
            "source_url": i["html_url"],
            "text": f"{i['title']}\n{(i['body'] or '')[:1500]}",
        }
        for i in issues
        if "pull_request" not in i
    ]


def _readme():
    r = fixture("readme.json")
    import base64

    return {
        "source_type": "readme",
        "source_ref": "README.md",
        "source_url": r["html_url"],
        "text": base64.b64decode(r["content"]).decode(),
    }


def test_qa_rag_01_index_is_idempotent_and_drops_stale_chunks(client, db):
    repo = make_repo(db)
    doc = _readme()
    first = client.post("/internal/v1/index", json={"repo_id": repo, "documents": [doc]}).json()
    assert first["chunks_upserted"] == 3 and first["chunks_skipped"] == 0
    second = client.post("/internal/v1/index", json={"repo_id": repo, "documents": [doc]}).json()
    assert second == {"chunks_upserted": 0, "chunks_skipped": 3}
    shorter = {**doc, "text": "# Demo\n\nNow tiny."}
    third = client.post("/internal/v1/index", json={"repo_id": repo, "documents": [shorter]})
    assert third.json() == {"chunks_upserted": 1, "chunks_skipped": 0}
    n = db.execute("select count(*) from gitmedic.embeddings where repo_id = %s", (repo,))
    assert n.fetchone()[0] == 1


def test_qa_ml_05_duplicates_exclude_self_and_flag_above_threshold(client, db):
    repo = make_repo(db)
    issues = fixture("issues.json")
    load_issues(db, repo)
    client.post("/internal/v1/index", json={"repo_id": repo, "documents": _issue_docs(issues)})
    original, duplicate = fixture("duplicate_pairs.json")[0]
    dup = next(i for i in issues if i["number"] == duplicate)
    r = client.post(
        "/internal/v1/duplicates",
        json={
            "repo_id": repo,
            "issue_number": duplicate,
            "title": dup["title"],
            "body": dup["body"],
        },
    )
    assert r.status_code == 200
    body = r.json()
    numbers = [m["issue_number"] for m in body["matches"]]
    assert duplicate not in numbers
    assert len(numbers) == 5
    assert [m["score"] for m in body["matches"]] == sorted(
        (m["score"] for m in body["matches"]), reverse=True
    )
    assert body["top_score"] == body["matches"][0]["score"]
    assert body["is_possible_duplicate"] == (body["top_score"] >= 0.80)


def test_duplicates_for_unindexed_issue_embed_the_query(client, db):
    repo = make_repo(db)
    r = client.post(
        "/internal/v1/duplicates",
        json={"repo_id": repo, "issue_number": 9999, "title": "Brand new", "body": None},
    )
    assert r.json() == {"matches": [], "top_score": None, "is_possible_duplicate": False}


def test_qa_rag_02_suggest_is_retrieval_only_from_closed_issues_and_docs(client, db):
    repo = make_repo(db)
    issues = fixture("issues.json")
    load_issues(db, repo)
    client.post(
        "/internal/v1/index",
        json={"repo_id": repo, "documents": [*_issue_docs(issues), _readme()]},
    )
    open_numbers = {i["number"] for i in issues if i["state"] == "open"}
    r = client.post(
        "/internal/v1/suggest",
        json={
            "repo_id": repo,
            "issue_number": 42,
            "title": "Crash on save if the filename has emoji",
            "body": "Whenever the file name includes an emoji the application crashes",
            "use_llm": True,
        },
    )
    assert r.status_code == 200
    body = r.json()
    assert body["mode"] == "RETRIEVAL_ONLY"
    assert body["llm_status"] == "DISABLED"
    assert body["sources"], body
    for s in body["sources"]:
        assert s["url"].startswith("https://github.com/octo/demo/")
        if s["type"] == "issue":
            assert int(s["ref"]) not in open_numbers  # BR-10: closed issues only
            assert s["ref"] != "42"
    assert "#41" in body["draft"]  # the closed original of duplicate pair 41/42


def test_suggest_without_material_says_insufficient_context(client, db):
    repo = make_repo(db)
    r = client.post(
        "/internal/v1/suggest",
        json={"repo_id": repo, "issue_number": 1, "title": "Anything", "body": None},
    )
    assert r.json()["sources"] == []
    assert r.json()["draft"].startswith("Insufficient context")


def test_index_accepts_a_document_at_the_200_kib_doc_cap(client, db):
    # BR-13 lets Spring send docs of up to 200 KiB; an ASCII one has that many characters.
    repo = make_repo(db)
    doc = {**_readme(), "text": "# Big\n\n" + "word " * (200 * 1024 // 5 - 2)}
    assert len(doc["text"]) <= 200 * 1024
    r = client.post("/internal/v1/index", json={"repo_id": repo, "documents": [doc]})
    assert r.status_code == 200, r.text
    too_big = {**doc, "text": "x" * (200 * 1024 + 1)}
    r = client.post("/internal/v1/index", json={"repo_id": repo, "documents": [too_big]})
    assert r.status_code == 400


def _store_vector(db, repo: int, number: int, a: float) -> None:
    """Issue chunk whose cosine with the unit vector e0 is exactly `a` (v = a·e0 + √(1−a²)·e1)."""
    v = [0.0] * 384
    v[0], v[1] = a, (1 - a * a) ** 0.5
    db.execute(
        "insert into gitmedic.embeddings (repo_id, source_type, source_ref, source_url,"
        " chunk_index, content, content_hash, embedding)"
        " values (%s, 'issue', %s, 'u', 0, 't', 'h', %s::extensions.vector)",
        (repo, str(number), vector_literal(v)),
    )


def test_duplicate_scores_are_cosine_ranked_repo_scoped_and_thresholded(client, db):
    repo, other = make_repo(db), make_repo(db, github_repo_id=999)
    _store_vector(db, repo, 1, 1.0)  # the issue being checked: its own vector is e0
    for number, cos in ((2, 0.9), (3, 0.5), (4, 0.79)):
        _store_vector(db, repo, number, cos)
    _store_vector(db, other, 2, 1.0)  # identical text in another repo must never match
    q = {"repo_id": repo, "issue_number": 1, "title": "t", "body": None}

    body = client.post("/internal/v1/duplicates", json=q).json()
    assert [m["issue_number"] for m in body["matches"]] == [2, 4, 3]  # self excluded
    assert [round(m["score"], 4) for m in body["matches"]] == [0.9, 0.79, 0.5]
    assert body["is_possible_duplicate"] is True  # 0.9 ≥ 0.80

    db.execute("delete from gitmedic.embeddings where repo_id = %s and source_ref = '2'", (repo,))
    body = client.post("/internal/v1/duplicates", json=q).json()
    assert body["top_score"] == pytest.approx(0.79, abs=1e-4)
    assert body["is_possible_duplicate"] is False  # 0.79 < 0.80

    # The comparison is ≥: a top score exactly at the threshold flags the issue.
    top = body["top_score"]
    at = search.duplicates(repo, 1, "t", None, 5, top, FakeEmbedder())
    above = search.duplicates(repo, 1, "t", None, 5, top + 1e-6, FakeEmbedder())
    assert (at["is_possible_duplicate"], above["is_possible_duplicate"]) == (True, False)

"""ML-07 … ML-09: indexing idempotency, duplicate search, retrieval-only suggestions."""

from tests.conftest import fixture, load_issues, make_repo


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

"""ML-02 … ML-06, ML-10, ML-11 against the disposable database."""

import json

import sklearn

from tests.conftest import load_issues, load_pulls, make_repo


def test_qa_ml_01_label_classifier_trains_and_predicts(client, db):
    repo = make_repo(db)
    load_issues(db, repo)
    r = client.post("/internal/v1/train/label", json={"repo_id": repo})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["n_samples"] == 70
    assert body["class_counts"] == {"bug": 30, "feature": 22, "question": 18}
    assert 0 <= body["macro_f1"] <= 1

    issues = [
        {"id": 1, "title": "App crashes with an error after upgrading", "body": "stack trace"},
        {"id": 2, "title": "How do I run this in Docker?", "body": "any advice?"},
    ]
    p = client.post("/internal/v1/predict/label", json={"repo_id": repo, "issues": issues})
    assert p.status_code == 200
    preds = {x["id"]: x for x in p.json()["predictions"]}
    assert preds[1]["label"] == "bug"
    assert preds[2]["label"] == "question"
    assert all(0 <= x["confidence"] <= 1 for x in preds.values())

    status = client.get(f"/internal/v1/repos/{repo}/models").json()
    assert status["label"]["n_samples"] == 70
    assert status["label_data"] == {
        "have": 70,
        "need": 30,
        "class_counts": {"bug": 30, "feature": 22, "question": 18},
        "ready": True,
    }
    assert status["merge_time"] is None
    assert status["merge_time_data"] == {"have": 0, "need": 20, "ready": False}


def test_qa_ml_02_insufficient_label_data(client, db):
    repo = make_repo(db)
    for n in range(10):
        db.execute(
            "insert into gitmedic.issues"
            " (repo_id, github_issue_id, number, title, state, gh_labels, html_url,"
            " created_at_gh, updated_at_gh)"
            " values (%s, %s, %s, 't', 'open', %s, 'u', now(), now())",
            (repo, n, n + 1, ["bug" if n % 2 else "question"]),
        )
    r = client.post("/internal/v1/train/label", json={"repo_id": repo})
    assert r.status_code == 422
    assert r.json()["code"] == "INSUFFICIENT_TRAINING_DATA"


def test_qa_ml_03_predict_without_model(client, db):
    repo = make_repo(db)
    r = client.post(
        "/internal/v1/predict/label",
        json={"repo_id": repo, "issues": [{"id": 1, "title": "x", "body": None}]},
    )
    assert r.status_code == 422
    assert r.json()["code"] == "MODEL_NOT_TRAINED"


def test_model_from_other_sklearn_version_is_not_loaded(client, db, tmp_path):
    from app.core.config import get_settings

    repo = make_repo(db)
    load_issues(db, repo)
    assert client.post("/internal/v1/train/label", json={"repo_id": repo}).status_code == 200
    meta_path = get_settings().model_dir.resolve() / str(repo) / "label.meta.json"
    meta = json.loads(meta_path.read_text())
    meta["sklearn_version"] = "0.0.1"
    meta_path.write_text(json.dumps(meta))
    r = client.post(
        "/internal/v1/predict/label",
        json={"repo_id": repo, "issues": [{"id": 1, "title": "x"}]},
    )
    assert r.status_code == 422 and "retrain" in r.json()["message"]
    assert sklearn.__version__ != "0.0.1"


def test_qa_ml_08_merge_time_trains_and_predicts_non_negative(client, db):
    repo = make_repo(db)
    load_pulls(db, repo)
    r = client.post("/internal/v1/train/merge-time", json={"repo_id": repo})
    assert r.status_code == 200, r.text
    assert r.json()["n_samples"] == 30
    assert r.json()["mae_days"] >= 0
    prs = [
        {
            "id": 1,
            "additions": 10,
            "deletions": 0,
            "changed_files": 1,
            "review_count": 0,
            "author_login": "alice",
        },
        {
            "id": 2,
            "additions": 5000,
            "deletions": 10,
            "changed_files": 40,
            "review_count": 6,
            "author_login": "newcomer",
        },
    ]
    p = client.post("/internal/v1/predict/merge-time", json={"repo_id": repo, "prs": prs})
    assert p.status_code == 200
    days = [x["days"] for x in p.json()["predictions"]]
    assert all(d >= 0 for d in days)
    assert days[1] > days[0]


def test_merge_time_insufficient_data(client, db):
    repo = make_repo(db)
    r = client.post("/internal/v1/train/merge-time", json={"repo_id": repo})
    assert r.status_code == 422
    assert r.json()["code"] == "INSUFFICIENT_TRAINING_DATA"


def test_risk_endpoint_reads_facts_from_db(client, db):
    repo = make_repo(db)
    r = client.post("/internal/v1/risk", json={"repo_id": repo, "as_of": "2026-10-20T00:00:00Z"})
    assert r.status_code == 200
    assert r.json()["score"] == 35  # empty repo, TBD-10
    db.execute(
        "insert into gitmedic.commits (repo_id, sha, author_login, committed_at)"
        " values (%s, repeat('a', 40), 'alice', '2026-10-19T00:00:00Z')",
        (repo,),
    )
    r = client.post("/internal/v1/risk", json={"repo_id": repo, "as_of": "2026-10-20T00:00:00Z"})
    body = r.json()
    assert round(body["components"]["days_since_commit"], 4) == round(1 / 180, 4)
    assert body["level"] == "LOW"


def test_ml_10_delete_repo_removes_models_and_embeddings(client, db):
    from app.core.config import get_settings

    repo = make_repo(db)
    load_issues(db, repo)
    client.post("/internal/v1/train/label", json={"repo_id": repo})
    client.post(
        "/internal/v1/index",
        json={
            "repo_id": repo,
            "documents": [
                {
                    "source_type": "readme",
                    "source_ref": "README.md",
                    "source_url": "u",
                    "text": "# Hi",
                }
            ],
        },
    )
    assert client.delete(f"/internal/v1/repos/{repo}").status_code == 204
    assert not (get_settings().model_dir.resolve() / str(repo)).exists()
    n = db.execute("select count(*) from gitmedic.embeddings where repo_id = %s", (repo,))
    assert n.fetchone()[0] == 0


def test_orphan_model_dirs_are_swept_on_startup(db):
    from fastapi.testclient import TestClient

    from app.core.config import get_settings
    from app.main import app

    repo = make_repo(db)
    root = get_settings().model_dir.resolve()
    (root / str(repo)).mkdir(parents=True, exist_ok=True)
    (root / "999999").mkdir(parents=True, exist_ok=True)
    with TestClient(app):
        pass
    assert (root / str(repo)).exists()
    assert not (root / "999999").exists()

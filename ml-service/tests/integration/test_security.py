"""QA-SEC-07 (internal token), QA-SEC-13 (model paths), QA-SEC-14 (DB roles), QA-VAL-*."""

import json

import psycopg
import pytest

from app.core.errors import MLError
from app.ml import store
from app.ml.store import repo_dir
from tests.conftest import TEST_DSN


def test_requests_log_at_info_with_request_id(client, caplog):
    """Regression: INFO request logging used to raise KeyError (request_id set twice)."""
    import logging

    caplog.set_level(logging.INFO, logger="gitmedic.ml")
    r = client.get("/health", headers={"X-Request-Id": "abc-123"})
    assert r.status_code == 200
    assert r.headers["X-Request-Id"] == "abc-123"
    assert any("GET /health -> 200" in m for m in caplog.messages)


def test_health_needs_no_token(client):
    r = client.get("/health", headers={"X-Internal-Token": ""})
    assert r.status_code == 200
    assert r.json()["db"] is True


@pytest.mark.parametrize("token", [None, "", "wrong-token-wrong-token-wrong-token"])
def test_qa_sec_07_internal_token_required(client, token):
    headers = {"X-Internal-Token": token} if token is not None else {}
    c = client
    c.headers.pop("X-Internal-Token", None)
    r = c.post(
        "/internal/v1/risk", json={"repo_id": 1, "as_of": "2026-01-01T00:00:00Z"}, headers=headers
    )
    assert r.status_code == 401
    assert r.json()["code"] == "UNAUTHENTICATED"
    assert r.json()["request_id"]


def test_no_cors_headers_for_browsers(client):
    r = client.options(
        "/internal/v1/risk",
        headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST"},
    )
    assert "access-control-allow-origin" not in r.headers


@pytest.mark.parametrize(
    ("path", "payload"),
    [
        ("/internal/v1/duplicates", {"repo_id": 1, "issue_number": 1, "title": "t", "top_k": 0}),
        ("/internal/v1/duplicates", {"repo_id": 1, "issue_number": 1, "title": "t", "top_k": 21}),
        ("/internal/v1/train/label", {"repo_id": 0}),
        (
            "/internal/v1/index",
            {
                "repo_id": 1,
                "documents": [
                    {"source_type": "pdf", "source_ref": "x", "source_url": "u", "text": ""}
                ],
            },
        ),
        (
            "/internal/v1/predict/label",
            {"repo_id": 1, "issues": [{"id": i, "title": "t"} for i in range(501)]},
        ),
    ],
)
def test_qa_val_invalid_requests_are_400(client, path, payload):
    r = client.post(path, json=payload)
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "VALIDATION_ERROR"


def test_oversized_body_rejected(client):
    r = client.post(
        "/internal/v1/index",
        content=b"{" + b" " * (2 * 1024 * 1024 + 1) + b"}",
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413


def test_non_integer_repo_path_rejected(client):
    assert client.delete("/internal/v1/repos/..%2F..%2Fetc").status_code in (400, 404)
    assert client.delete("/internal/v1/repos/0").status_code == 400


def test_qa_sec_13_model_paths_only_from_positive_ints(tmp_path):
    assert repo_dir(tmp_path, 7) == tmp_path.resolve() / "7"
    for bad in (0, -1, True, "7", 1.5):
        with pytest.raises(ValueError):
            repo_dir(tmp_path, bad)  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ("meta", "model"),
    [("{not json", b"x"), ('{"sklearn_version": "0"}', b"x"), (None, b"not a pickle")],
)
def test_damaged_model_files_mean_retrain_not_500(tmp_path, meta, model):
    d = repo_dir(tmp_path, 9)
    d.mkdir()
    (d / "label.joblib").write_bytes(model)
    if meta is None:
        import sklearn

        meta = json.dumps({"sklearn_version": sklearn.__version__})
    (d / "label.meta.json").write_text(meta, encoding="utf-8")
    with pytest.raises(MLError) as e:
        store.load(tmp_path, 9, "label")
    assert (e.value.status, e.value.code) == (422, "MODEL_NOT_TRAINED")


@pytest.mark.parametrize(
    ("role", "sql", "allowed"),
    [
        ("gitmedic_ml", "select count(*) from gitmedic.issues", True),
        ("gitmedic_ml", "select id from gitmedic.repos", True),
        ("gitmedic_ml", "select full_name from gitmedic.repos", False),
        ("gitmedic_ml", "select count(*) from gitmedic.users", False),
        ("gitmedic_ml", "update gitmedic.issues set title = 'x'", False),
        ("gitmedic_ml", "delete from gitmedic.embeddings", True),
        ("gitmedic_ml", "select count(*) from gitmedic.weekly_reports", False),
        ("gm_anon_sim", "select count(*) from gitmedic.users", False),
        ("gm_anon_sim", "select count(*) from gitmedic.issues", False),
    ],
)
def test_qa_sec_14_least_privilege(db, role, sql, allowed):
    with psycopg.connect(TEST_DSN) as c:
        c.execute(f"set role {role}")
        if allowed:
            c.execute(sql)
        else:
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                c.execute(sql)
        c.rollback()

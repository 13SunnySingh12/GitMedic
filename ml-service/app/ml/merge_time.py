"""FR-013: per-repo PR merge-time regressor (Random Forest), target = days from open to merge."""

import statistics
from datetime import datetime
from pathlib import Path

from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error
from sklearn.model_selection import train_test_split

from app.core.errors import MLError
from app.ml import store

NAME = "merge_time"
MIN_SAMPLES = 20


def _days(start: datetime, end: datetime) -> float:
    return max((end - start).total_seconds() / 86400, 0.0)


def build_dataset(rows: list[dict]) -> tuple[list[list[float]], list[float], float]:
    """Rows are merged PRs. Author features use only PRs merged before this PR was opened."""
    if len(rows) < MIN_SAMPLES:
        raise MLError(
            422,
            "INSUFFICIENT_TRAINING_DATA",
            f"Need ≥ {MIN_SAMPLES} merged PRs; have {len(rows)}.",
        )
    rows = sorted(rows, key=lambda r: r["created_at_gh"])
    durations = [_days(r["created_at_gh"], r["merged_at_gh"]) for r in rows]
    global_median = statistics.median(durations)
    x, y = [], []
    for r, target in zip(rows, durations, strict=True):
        prior = [
            d
            for o, d in zip(rows, durations, strict=True)
            if o["author_login"] == r["author_login"] and o["merged_at_gh"] < r["created_at_gh"]
        ]
        x.append(features(r, len(prior), statistics.median(prior) if prior else global_median))
        y.append(target)
    return x, y, global_median


def features(pr: dict, author_prior: int, author_median: float) -> list[float]:
    return [
        pr["additions"],
        pr["deletions"],
        pr["changed_files"],
        pr["review_count"],
        author_prior,
        author_median,
    ]


def _model() -> RandomForestRegressor:
    return RandomForestRegressor(n_estimators=200, random_state=42)


def train(model_dir: Path, repo_id: int, rows: list[dict]) -> dict:
    x, y, global_median = build_dataset(rows)
    x_tr, x_te, y_tr, y_te = train_test_split(x, y, test_size=0.2, random_state=42)
    mae = float(mean_absolute_error(y_te, _model().fit(x_tr, y_tr).predict(x_te)))
    final = _model().fit(x, y)
    return store.save(
        model_dir,
        repo_id,
        NAME,
        final,
        {"n_samples": len(x), "mae_days": mae, "global_median_days": global_median},
    )


def predict(
    model_dir: Path, repo_id: int, prs: list[dict], author_history: dict[str, list[float]]
) -> list[dict]:
    """author_history: author login → merge durations (days) of that author's merged PRs."""
    model, meta = store.load(model_dir, repo_id, NAME)
    if not prs:
        return []
    rows = []
    for pr in prs:
        hist = author_history.get(pr.get("author_login") or "", [])
        rows.append(
            features(pr, len(hist), statistics.median(hist) if hist else meta["global_median_days"])
        )
    return [
        {"id": pr["id"], "days": max(float(d), 0.0)}
        for pr, d in zip(prs, model.predict(rows), strict=True)
    ]

"""Per-repo model files under MODEL_DIR/{repo_id}/ (SEC-14: paths only from an integer repo id)."""

import json
import shutil
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import joblib
import sklearn

from app.core.errors import MLError

_cache: dict[Path, tuple[float, Any]] = {}


def repo_dir(model_dir: Path, repo_id: int) -> Path:
    if not isinstance(repo_id, int) or isinstance(repo_id, bool) or repo_id <= 0:
        raise ValueError("repo_id must be a positive integer")
    return model_dir.resolve() / str(repo_id)


def save(model_dir: Path, repo_id: int, name: str, model: Any, meta: dict[str, Any]) -> dict:
    d = repo_dir(model_dir, repo_id)
    d.mkdir(parents=True, exist_ok=True)
    meta = {
        **meta,
        "trained_at": datetime.now(UTC).isoformat(),
        "sklearn_version": sklearn.__version__,
    }
    tmp = d / f"{name}.joblib.tmp"
    joblib.dump(model, tmp)
    tmp.replace(d / f"{name}.joblib")
    tmp_meta = d / f"{name}.meta.json.tmp"
    tmp_meta.write_text(json.dumps(meta), encoding="utf-8")
    tmp_meta.replace(d / f"{name}.meta.json")
    return meta


def meta(model_dir: Path, repo_id: int, name: str) -> dict[str, Any] | None:
    """The model's metadata; None when missing or unreadable (both mean: train again)."""
    p = repo_dir(model_dir, repo_id) / f"{name}.meta.json"
    try:
        m = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return m if isinstance(m, dict) else None


def load(model_dir: Path, repo_id: int, name: str) -> tuple[Any, dict[str, Any]]:
    """Loads a model this service wrote; joblib is pickle, so nothing else is ever loaded."""
    m = meta(model_dir, repo_id, name)
    path = repo_dir(model_dir, repo_id) / f"{name}.joblib"
    if m is None or not path.exists():
        raise MLError(422, "MODEL_NOT_TRAINED", f"The {name} model has not been trained yet.")
    if m.get("sklearn_version") != sklearn.__version__:
        raise MLError(
            422,
            "MODEL_NOT_TRAINED",
            f"The {name} model was trained with scikit-learn {m.get('sklearn_version')}; retrain.",
        )
    mtime = path.stat().st_mtime
    cached = _cache.get(path)
    if cached is None or cached[0] != mtime:
        try:
            cached = (mtime, joblib.load(path))  # noqa: S301 - only service-written files (SEC-14)
        except Exception as e:  # a truncated or damaged file: same answer as no model
            raise MLError(
                422, "MODEL_NOT_TRAINED", f"The {name} model file is unreadable; retrain."
            ) from e
        _cache[path] = cached
    return cached[1], m


def delete_repo(model_dir: Path, repo_id: int) -> None:
    d = repo_dir(model_dir, repo_id)
    for p in list(_cache):
        if p.parent == d:
            del _cache[p]
    shutil.rmtree(d, ignore_errors=True)


def sweep_orphans(model_dir: Path, live_repo_ids: set[int]) -> list[int]:
    """Removes model folders of repos untracked while this service was down (SEC-21)."""
    removed: list[int] = []
    root = model_dir.resolve()
    if not root.is_dir():
        return removed
    for d in root.iterdir():
        if d.is_dir() and d.name.isdigit() and int(d.name) not in live_repo_ids:
            delete_repo(model_dir, int(d.name))
            removed.append(int(d.name))
    return removed

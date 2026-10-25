"""FR-011/012: per-repo TF-IDF + Logistic Regression issue label classifier."""

from collections import Counter
from pathlib import Path

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline

from app.core.errors import MLError
from app.ml import store

NAME = "label"
MAX_CHARS = 8000
MIN_SAMPLES = 30
MIN_PER_CLASS = 5

# BR-04 alias map. TBD-04 resolved: "help wanted" is a call for contributors, not a question,
# so it maps to no class.
ALIASES = {
    "bug": "bug",
    "enhancement": "feature",
    "feature": "feature",
    "feature request": "feature",
    "question": "question",
}


def text_of(title: str, body: str | None) -> str:
    return f"{title}\n{body or ''}"[:MAX_CHARS]


def class_of(labels: list[str]) -> str | None:
    """The single class an issue's GitHub labels map to; None for zero or conflicting classes."""
    classes = {ALIASES[lbl.strip().lower()] for lbl in labels if lbl.strip().lower() in ALIASES}
    return classes.pop() if len(classes) == 1 else None


def build_dataset(rows: list[dict]) -> tuple[list[str], list[str]]:
    """BR-07: classes need ≥ 5 examples; ≥ 2 such classes and ≥ 30 usable issues overall."""
    pairs = [(text_of(r["title"], r["body"]), c) for r in rows if (c := class_of(r["gh_labels"]))]
    counts = Counter(c for _, c in pairs)
    kept = {c for c, n in counts.items() if n >= MIN_PER_CLASS}
    pairs = [(t, c) for t, c in pairs if c in kept]
    if len(kept) < 2 or len(pairs) < MIN_SAMPLES:
        raise MLError(
            422,
            "INSUFFICIENT_TRAINING_DATA",
            f"Need ≥ {MIN_SAMPLES} labelled issues across ≥ 2 classes with ≥ {MIN_PER_CLASS} each;"
            f" have {len(pairs)} usable ({dict(counts)}).",
        )
    return [t for t, _ in pairs], [c for _, c in pairs]


def readiness(rows: list[dict]) -> dict:
    """How close the repo is to BR-07, for the dashboard's model-status panel."""
    counts = Counter(c for r in rows if (c := class_of(r["gh_labels"])))
    usable = sum(n for n in counts.values() if n >= MIN_PER_CLASS)
    ready = usable >= MIN_SAMPLES and sum(n >= MIN_PER_CLASS for n in counts.values()) >= 2
    return {"have": usable, "need": MIN_SAMPLES, "class_counts": dict(counts), "ready": ready}


def _pipeline() -> Pipeline:
    return Pipeline(
        [
            ("tfidf", TfidfVectorizer(ngram_range=(1, 2), min_df=2, max_features=20000)),
            (
                "clf",
                LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42),
            ),
        ]
    )


def train(model_dir: Path, repo_id: int, rows: list[dict]) -> dict:
    texts, labels = build_dataset(rows)
    x_tr, x_te, y_tr, y_te = train_test_split(
        texts, labels, test_size=0.2, stratify=labels, random_state=42
    )
    held_out = _pipeline().fit(x_tr, y_tr)
    macro_f1 = float(f1_score(y_te, held_out.predict(x_te), average="macro"))
    # The reported metric comes from the held-out split; the saved model then learns from all data.
    final = _pipeline().fit(texts, labels)
    return store.save(
        model_dir,
        repo_id,
        NAME,
        final,
        {"n_samples": len(texts), "class_counts": dict(Counter(labels)), "macro_f1": macro_f1},
    )


def predict(model_dir: Path, repo_id: int, issues: list[dict]) -> list[dict]:
    model, _ = store.load(model_dir, repo_id, NAME)
    if not issues:
        return []
    proba = model.predict_proba([text_of(i["title"], i["body"]) for i in issues])
    classes = model.classes_
    out = []
    for issue, p in zip(issues, proba, strict=True):
        best = int(p.argmax())
        out.append({"id": issue["id"], "label": str(classes[best]), "confidence": float(p[best])})
    return out

"""Locks the documented model setup and data rules (FR-011/013, BR-04/07/08) to the spec."""

from datetime import UTC, datetime, timedelta

import pytest

from app.core.errors import MLError
from app.ml import label_classifier, merge_time


def test_label_pipeline_matches_spec_configuration():
    p = label_classifier._pipeline().get_params()
    assert (p["tfidf__ngram_range"], p["tfidf__min_df"], p["tfidf__max_features"]) == (
        (1, 2),
        2,
        20000,
    )
    assert (p["clf__class_weight"], p["clf__max_iter"]) == ("balanced", 1000)


def test_merge_model_matches_spec_configuration():
    p = merge_time._model().get_params()
    assert (p["n_estimators"], p["random_state"]) == (200, 42)


def _issues(counts: dict[str, int]) -> list[dict]:
    return [
        {"title": f"{label} {i}", "body": None, "gh_labels": [label]}
        for label, n in counts.items()
        for i in range(n)
    ]


@pytest.mark.parametrize(
    ("counts", "trains", "usable"),
    [
        ({"bug": 25, "question": 5}, True, 30),  # exactly 30 usable, two classes of ≥ 5
        ({"bug": 24, "question": 5}, False, 29),  # one short of 30
        ({"bug": 26, "question": 4}, False, 26),  # 4 questions do not count as a class
        ({"bug": 30}, False, 30),  # one class only
        ({"bug": 22, "enhancement": 5, "question": 4}, False, 27),  # aliases count, 4 dropped
    ],
)
def test_br07_training_threshold(counts, trains, usable):
    rows = _issues(counts)
    assert label_classifier.readiness(rows)["have"] == usable
    assert label_classifier.readiness(rows)["ready"] is trains
    if trains:
        texts, labels = label_classifier.build_dataset(rows)
        assert len(texts) == usable
    else:
        with pytest.raises(MLError) as e:
            label_classifier.build_dataset(rows)
        assert (e.value.status, e.value.code) == (422, "INSUFFICIENT_TRAINING_DATA")


def test_unsupported_and_conflicting_labels_never_become_training_classes():
    rows = _issues({"bug": 20, "question": 10})
    rows += [
        {"title": "x", "body": None, "gh_labels": ["help wanted"]},  # TBD-04: no class
        {"title": "y", "body": None, "gh_labels": ["bug", "enhancement"]},  # two classes
        {"title": "z", "body": None, "gh_labels": ["documentation"]},
    ]
    _, labels = label_classifier.build_dataset(rows)
    assert len(labels) == 30 and set(labels) == {"bug", "question"}


def test_empty_body_still_uses_the_title():
    assert label_classifier.text_of("Crash on start", None) == "Crash on start\n"


T0 = datetime(2026, 1, 1, tzinfo=UTC)


def _pr(author: str, opened_day: float, merged_day: float) -> dict:
    return {
        "author_login": author,
        "additions": 10,
        "deletions": 2,
        "changed_files": 1,
        "review_count": 1,
        "created_at_gh": T0 + timedelta(days=opened_day),
        "merged_at_gh": T0 + timedelta(days=merged_day),
    }


def test_br08_target_is_days_from_open_to_merge_and_author_history_has_no_leak():
    # 20 PRs by "a", each open 2 days, opened 10 days apart; one 0.5-day PR by "b".
    rows = [_pr("a", 10 * i, 10 * i + 2) for i in range(20)] + [_pr("b", 3, 3.5)]
    x, y, median = merge_time.build_dataset(rows)
    assert sorted(y) == [0.5] + [2.0] * 20
    assert median == 2.0
    by_open = sorted(rows, key=lambda r: r["created_at_gh"])
    first_a, third_a = x[0], x[by_open.index(rows[2])]
    # Features: additions, deletions, files, reviews, prior merged PRs, median of their days.
    assert first_a == [10, 2, 1, 1, 0, 2.0]  # no history yet → global median
    assert third_a == [10, 2, 1, 1, 2, 2.0]  # only the two PRs merged before it opened


def test_br08_needs_twenty_merged_prs():
    with pytest.raises(MLError) as e:
        merge_time.build_dataset([_pr("a", i, i + 1) for i in range(19)])
    assert (e.value.status, e.value.code) == (422, "INSUFFICIENT_TRAINING_DATA")


def test_training_is_deterministic(tmp_path):
    rows = [_pr("a" if i % 3 else "b", 5 * i, 5 * i + 1 + i % 4) for i in range(30)]
    first = merge_time.train(tmp_path, 1, rows)["mae_days"]
    assert merge_time.train(tmp_path, 1, rows)["mae_days"] == first

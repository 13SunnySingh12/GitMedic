"""QA-ML-06: BR-05 risk formula against hand-computed cases."""

import pytest

from app.ml.risk import Facts, level, score


@pytest.mark.parametrize(
    ("facts", "expected_score", "expected_level"),
    [
        # Empty repo (TBD-10): s1 = 1 → 0.35·1 = 35 → MEDIUM.
        (Facts(None, 0, 0, 0, 0, 0), 35, "MEDIUM"),
        # Healthy: committed today, no growth, all answered, contributors steady → 0.
        (Facts(0, 10, 10, 0, 4, 4), 0, "LOW"),
        # 90 days stale (s1=0.5), +50% open issues (s2=0.5), 2/10 unanswered (s3=0.2),
        # 3 of 4 contributors remain (s4=0.25): 35·0.5+20·0.5+25·0.2+20·0.25 = 37.5 → 38.
        (Facts(90, 15, 10, 3, 3, 4), 38, "MEDIUM"),
        # Everything maxed: 400 days, open issues tripled, all unanswered, nobody left → 100.
        (Facts(400, 30, 10, 30, 0, 5), 100, "HIGH"),
        # Growth from zero uses max(open_30d_ago, 1): (5-0)/1 → clamped to 1 → 20.
        (Facts(0, 5, 0, 0, 2, 2), 20, "LOW"),
        # Shrinking backlog clamps s2 at 0; more contributors than before clamps s4 at 0.
        # s1 = 18/180 = 0.1 → 3.5; s3 = 1/4 → 6.25: 9.75 → 10.
        (Facts(18, 4, 8, 1, 6, 3), 10, "LOW"),
        # Boundary: s1 = 1 (35) + s3 = 1 (25) + s4 = 0.35 (7) = 67 → HIGH.
        (Facts(200, 2, 2, 2, 13, 20), 67, "HIGH"),
    ],
)
def test_hand_computed(facts, expected_score, expected_level):
    result = score(facts)
    assert result["score"] == expected_score
    assert result["level"] == expected_level
    assert set(result["components"]) == {
        "days_since_commit",
        "open_issue_growth",
        "unanswered_rate",
        "contributor_dropoff",
    }
    assert all(0 <= v <= 1 for v in result["components"].values())


def test_level_bands():
    assert [level(s) for s in (0, 33, 34, 66, 67, 100)] == [
        "LOW",
        "LOW",
        "MEDIUM",
        "MEDIUM",
        "HIGH",
        "HIGH",
    ]

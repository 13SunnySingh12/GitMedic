"""FR-014 / BR-05: deterministic abandonment-risk score."""

import math
from dataclasses import dataclass

WEIGHTS = {
    "days_since_commit": 0.35,
    "open_issue_growth": 0.20,
    "unanswered_rate": 0.25,
    "contributor_dropoff": 0.20,
}


@dataclass(frozen=True)
class Facts:
    days_since_last_commit: float | None  # None = no commits at all
    open_now: int
    open_30d_ago: int
    open_old_uncommented: int  # open > 7 days with 0 comments
    active_last_90d: int
    active_prev_90d: int


def _clamp(x: float) -> float:
    return min(max(x, 0.0), 1.0)


def components(f: Facts) -> dict[str, float]:
    return {
        # TBD-10 resolved: no commits at all counts as maximally stale (s1 = 1).
        "days_since_commit": 1.0
        if f.days_since_last_commit is None
        else min(f.days_since_last_commit / 180, 1.0),
        "open_issue_growth": _clamp((f.open_now - f.open_30d_ago) / max(f.open_30d_ago, 1)),
        "unanswered_rate": f.open_old_uncommented / f.open_now if f.open_now else 0.0,
        # TBD-10 resolved: with no contributors in the previous window nobody can have dropped off.
        "contributor_dropoff": 0.0
        if f.active_prev_90d == 0
        else max(0.0, 1 - f.active_last_90d / f.active_prev_90d),
    }


def level(score: int) -> str:
    return "LOW" if score <= 33 else "MEDIUM" if score <= 66 else "HIGH"


def score(f: Facts) -> dict:
    c = components(f)
    raw = 100 * sum(WEIGHTS[k] * v for k, v in c.items())
    s = int(math.floor(raw + 0.5 + 1e-9))  # round half up, robust to float noise
    return {"score": s, "level": level(s), "components": c}

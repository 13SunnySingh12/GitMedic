"""Read-only queries over Spring-owned tables (gitmedic_ml has SELECT only, §6.4)."""

from datetime import datetime, timedelta

from app.db.pool import connection, one
from app.ml.risk import Facts


def labelled_issues(repo_id: int) -> list[dict]:
    with connection() as c:
        return c.execute(
            "select title, body, gh_labels from gitmedic.issues where repo_id = %s", (repo_id,)
        ).fetchall()


def merged_prs(repo_id: int) -> list[dict]:
    with connection() as c:
        return c.execute(
            """select author_login, additions, deletions, changed_files, review_count,
                      created_at_gh, merged_at_gh
               from gitmedic.pull_requests where repo_id = %s and merged_at_gh is not null""",
            (repo_id,),
        ).fetchall()


def author_merge_history(repo_id: int, logins: list[str]) -> dict[str, list[float]]:
    if not logins:
        return {}
    with connection() as c:
        rows = c.execute(
            """select author_login,
                      extract(epoch from (merged_at_gh - created_at_gh)) / 86400 as days
               from gitmedic.pull_requests
               where repo_id = %s and merged_at_gh is not null and author_login = any(%s)""",
            (repo_id, logins),
        ).fetchall()
    out: dict[str, list[float]] = {}
    for r in rows:
        out.setdefault(r["author_login"], []).append(max(float(r["days"]), 0.0))
    return out


_OPEN_AT = """select count(*) as n,
                      count(*) filter (where created_at_gh < %(old)s and comments_count = 0) as old
               from gitmedic.issues where repo_id = %(r)s and created_at_gh <= %(t)s
               and (closed_at_gh is null or closed_at_gh > %(t)s)"""
_ACTIVE = """select count(distinct coalesce(author_login, author_email_hash)) as n
             from gitmedic.commits where repo_id = %(r)s
             and committed_at > %(lo)s and committed_at <= %(hi)s"""


def risk_facts(repo_id: int, as_of: datetime) -> Facts:
    d = timedelta(days=1)
    with connection() as c:
        last = one(
            c,
            "select max(committed_at) as t from gitmedic.commits"
            " where repo_id = %s and committed_at <= %s",
            (repo_id, as_of),
        )["t"]
        now = one(c, _OPEN_AT, {"r": repo_id, "t": as_of, "old": as_of - 7 * d})
        then = one(c, _OPEN_AT, {"r": repo_id, "t": as_of - 30 * d, "old": as_of})
        a_last = one(c, _ACTIVE, {"r": repo_id, "lo": as_of - 90 * d, "hi": as_of})
        a_prev = one(c, _ACTIVE, {"r": repo_id, "lo": as_of - 180 * d, "hi": as_of - 90 * d})
    open_now, uncommented, open_30 = now["n"], now["old"], then["n"]
    a_last, a_prev = a_last["n"], a_prev["n"]
    return Facts(
        days_since_last_commit=None if last is None else (as_of - last).total_seconds() / 86400,
        open_now=open_now,
        open_30d_ago=open_30,
        open_old_uncommented=uncommented,
        active_last_90d=a_last,
        active_prev_90d=a_prev,
    )

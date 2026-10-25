"""Regenerates the deterministic GitHub fixtures in this folder: `python fixtures/github/generate.py`.

Synthetic data only (no real repository content). Shapes follow the GitHub REST API responses
GitMedic consumes. Sizes match QA-SYNC-01: 120 issues (+15 PR items in the issues feed), 40 PRs,
300 commits; 20 known duplicate pairs (QA-ML-04) and ≥ 60 labelled issues (QA-ML-01).
"""

import base64
import json
import random
from datetime import UTC, datetime, timedelta
from pathlib import Path

OUT = Path(__file__).parent
REPO = "octo/demo"
REPO_ID = 123456
WEB = f"https://github.com/{REPO}"
START = datetime(2025, 11, 3, tzinfo=UTC)
END = datetime(2026, 10, 19, tzinfo=UTC)
rng = random.Random(42)

COMPONENTS = [
    "settings page", "CSV export", "login form", "dark mode", "search bar", "file upload",
    "notification center", "dashboard charts", "API client", "CLI installer",
    "markdown preview", "keyboard shortcuts", "image resizer", "sync service", "plugin loader",
    "date picker", "PDF export", "offline mode", "user profile", "webhook handler",
]
TEMPLATES = {
    "bug": [
        ("The {c} crashes with a null reference error", "Steps to reproduce: open the {c} and"
         " click save. Expected it to work, actual result is a crash and a stack trace."),
        ("Error 500 from the {c} after upgrading", "Since the last release the {c} fails with"
         " an internal server error. Regression, it worked in the previous version."),
        ("{c} shows broken values when locale is German", "The {c} renders wrong numbers and"
         " garbled text with a German locale. Bug reproducible every time."),
    ],
    "feature": [
        ("Add support for custom themes in the {c}", "It would be great if the {c} supported"
         " custom themes. This enhancement would let teams match their branding."),
        ("Allow bulk actions in the {c}", "Feature request: please allow selecting many items in"
         " the {c} and applying an action to all of them at once."),
        ("Option to configure the {c} from a config file", "Proposal: add a new option so the"
         " {c} can be configured declaratively. Would be a nice improvement."),
    ],
    "question": [
        ("How do I use the {c} behind a corporate proxy?", "Question: what is the right way to"
         " run the {c} behind a proxy? I could not find this in the docs. Any advice?"),
        ("Is it possible to run the {c} inside Docker?", "Wondering whether the {c} works in a"
         " container. How should I set it up? Thanks for any help or guidance."),
        ("Where does the {c} store its logs?", "I am trying to understand where the {c} writes"
         " log files and how to change that location. Can someone explain?"),
    ],
}
# 20 hand-written (original, duplicate) pairs: paraphrases of the same problem.
DUP_PAIRS = [
    ("bug", "App crashes when saving a file with an emoji in its name",
     "Saving a document whose filename contains emoji characters makes the app crash.",
     "Crash on save if the filename has emoji", "Whenever the file name includes an emoji the"
     " application crashes as soon as I hit save."),
    ("bug", "Login fails after the session cookie expires", "When my session expires I cannot"
     " log in again until I clear cookies manually.", "Cannot sign in again once the session"
     " times out", "After the session times out, logging in keeps failing unless I delete the"
     " cookies."),
    ("bug", "Memory usage grows without bound during long sync jobs", "Running a sync for several"
     " hours makes memory climb until the process is killed.", "Memory leak in long-running"
     " synchronization", "The sync process leaks memory: after a few hours RAM usage is huge and"
     " it gets OOM killed."),
    ("bug", "Timezone offset is wrong in exported reports", "Exported reports show times shifted"
     " by one hour compared to my local timezone.", "Report export uses the wrong time zone",
     "Times in the exported report are an hour off from my timezone."),
    ("bug", "Dark mode text is unreadable in the sidebar", "In dark mode the sidebar labels are"
     " dark grey on black and impossible to read.", "Sidebar labels invisible with dark theme",
     "With the dark theme enabled the sidebar text has almost no contrast and can't be read."),
    ("bug", "Uploading a large CSV freezes the browser tab", "Importing a CSV over 50 MB hangs"
     " the page completely.", "Browser hangs when importing big CSV files", "The tab freezes"
     " when I upload a large CSV file for import."),
    ("bug", "Search returns no results for words with accents", "Searching for café or naïve"
     " finds nothing even though matching items exist.", "Accented characters break search",
     "Queries containing accents like é or ï return zero results."),
    ("feature", "Support exporting dashboards as PNG images", "I would like to download a"
     " dashboard as a PNG picture to paste into slides.", "Export dashboard to an image file",
     "Please add a way to save the dashboard as an image (PNG) for presentations."),
    ("feature", "Add keyboard shortcut to toggle the sidebar", "A shortcut such as Ctrl+B to"
     " show or hide the sidebar would speed up navigation.", "Hotkey for hiding and showing"
     " the side panel", "It would be handy to have a keyboard shortcut that collapses the"
     " sidebar."),
    ("feature", "Allow scheduling reports for a custom weekday", "Reports always run on Monday;"
     " let me choose another day of the week.", "Configurable day for weekly report generation",
     "Feature request: pick which weekday the weekly report is generated on."),
    ("feature", "Integrate with Slack for notifications", "Send alerts to a Slack channel when"
     " something important happens.", "Slack notifications support", "Would love to receive"
     " notifications in our Slack workspace."),
    ("feature", "Provide a REST endpoint to list all projects", "An API endpoint returning every"
     " project would help automation scripts.", "API to fetch the list of projects",
     "Please expose an HTTP API that returns all projects so we can script against it."),
    ("feature", "Two-factor authentication for accounts", "Add optional 2FA with an"
     " authenticator app to improve account security.", "Support 2FA login", "Requesting"
     " two-factor auth (TOTP authenticator apps) for signing in."),
    ("question", "How can I migrate data from version 1 to version 2?", "What is the supported"
     " upgrade path for existing data when moving to v2?", "Upgrade guide from v1 to v2 data",
     "Is there documentation on migrating my existing v1 data into v2?"),
    ("question", "Which databases are supported in production?", "Can I use MySQL or only"
     " PostgreSQL for a production deployment?", "Is MySQL supported or just Postgres?",
     "Wondering which database engines are officially supported for production use."),
    ("question", "How do I increase the upload size limit?", "Uploads above 10 MB are rejected;"
     " where do I raise that limit?", "Where to configure the maximum upload size",
     "Files bigger than 10MB fail to upload. How can I change the max size?"),
    ("question", "Can I run the CLI on Windows?", "Does the command line tool work on Windows"
     " or only Linux and macOS?", "Windows support for the command line tool",
     "Is the CLI usable on Windows machines?"),
    ("question", "What license is the project released under?", "I need to know the license"
     " before using this at work.", "Project license question", "Under which license can I"
     " use this project commercially?"),
    ("bug", "Pagination skips items on the last page", "The final page of results is missing"
     " a few entries that exist in the database.", "Last page of the list drops some records",
     "When paging through results, the last page omits some items."),
    ("feature", "Add CSV import for contacts", "Let users import contacts from a CSV file"
     " instead of typing them one by one.", "Bulk import contacts from CSV",
     "Feature idea: import a list of contacts via a CSV upload."),
]
AUTHORS = ["alice", "bob", "carol", "dave", "erin", "frank"]


def iso(t: datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def rand_time(lo: datetime = START, hi: datetime = END) -> datetime:
    return lo + timedelta(seconds=rng.randrange(int((hi - lo).total_seconds())))


def issue(number, title, body, labels, state, created, closed, comments, author):
    return {
        "id": 900000 + number,
        "number": number,
        "title": title,
        "body": body,
        "state": state,
        "user": {"login": author},
        "labels": [{"name": n} for n in labels],
        "comments": comments,
        "html_url": f"{WEB}/issues/{number}",
        "created_at": iso(created),
        "updated_at": iso(closed or created + timedelta(hours=rng.randrange(1, 200))),
        "closed_at": iso(closed) if closed else None,
    }


def build_issues() -> tuple[list[dict], list[list[int]]]:
    issues, pairs = [], []
    n = 41
    for cls, t1, b1, t2, b2 in DUP_PAIRS:
        created = rand_time(START, END - timedelta(days=90))
        closed = created + timedelta(days=rng.randrange(1, 30))
        issues.append(issue(n, t1, b1, [cls if cls != "feature" else "enhancement"], "closed",
                            created, closed, rng.randrange(1, 6), rng.choice(AUTHORS)))
        dup_created = rand_time(created + timedelta(days=40), END)
        issues.append(issue(n + 1, t2, b2, [cls], "open", dup_created, None, 0,
                            rng.choice(AUTHORS)))
        pairs.append([n, n + 1])
        n += 2
    # 80 more distinct issues: 30 labelled, 50 unlabelled or excluded label combinations.
    combos = [(cls, ti, c) for cls in TEMPLATES for ti in range(3) for c in COMPONENTS]
    rng.shuffle(combos)
    others = ["documentation", "help wanted", "good first issue", "wontfix"]
    for k, (cls, ti, comp) in enumerate(combos[:80]):
        title, body = (s.format(c=comp) for s in TEMPLATES[cls][ti])
        title = title[0].upper() + title[1:]
        if k < 30:
            labels = [cls]
        elif k < 36:
            labels = ["bug", "enhancement"]  # conflicting classes → excluded from training
        else:
            labels = [rng.choice(others)] if rng.random() < 0.6 else []
        created = rand_time()
        closed = created + timedelta(days=rng.randrange(1, 60)) if rng.random() < 0.5 else None
        if closed and closed > END:
            closed = None
        issues.append(issue(n, title, body if k % 9 else "", labels,
                            "closed" if closed else "open", created, closed,
                            rng.randrange(0, 4) if rng.random() < 0.6 else 0,
                            rng.choice(AUTHORS)))
        n += 1
    return issues, pairs


def build_pulls() -> tuple[list[dict], dict, dict, list[dict]]:
    pulls, details, reviews, feed_items = [], {}, {}, []
    for number in range(1, 41):
        author = AUTHORS[number % len(AUTHORS)]
        created = rand_time(START, END - timedelta(days=20))
        additions = rng.randrange(5, 1500)
        deletions = rng.randrange(0, additions)
        files = max(1, additions // 120 + rng.randrange(0, 3))
        n_reviews = rng.randrange(0, 5)
        if number <= 30:
            days = 0.3 + additions / 250 + n_reviews * 0.6 + rng.random()
            merged = created + timedelta(days=days)
            state, closed = "closed", merged
        elif number <= 35:
            merged, state, closed = None, "closed", created + timedelta(days=rng.randrange(1, 9))
        else:
            merged, state, closed = None, "open", None
        base = {
            "id": 700000 + number,
            "number": number,
            "title": f"{rng.choice(['Fix', 'Add', 'Refactor', 'Update'])} "
                     f"{rng.choice(COMPONENTS)} (#{number})",
            "state": state,
            "user": {"login": author},
            "html_url": f"{WEB}/pull/{number}",
            "created_at": iso(created),
            "updated_at": iso(closed or created + timedelta(days=1)),
            "closed_at": iso(closed) if closed else None,
            "merged_at": iso(merged) if merged else None,
        }
        pulls.append(base)
        details[str(number)] = {**base, "additions": additions, "deletions": deletions,
                                "changed_files": files}
        reviews[str(number)] = [
            {"id": 800000 + number * 10 + r, "user": {"login": rng.choice(AUTHORS)},
             "state": "APPROVED"}
            for r in range(n_reviews)
        ]
        if number <= 15:
            feed_items.append({
                **issue(number, base["title"], "PR body", [], state, created, closed, 0, author),
                "id": base["id"],
                "html_url": base["html_url"],
                "pull_request": {"url": f"https://api.github.com/repos/{REPO}/pulls/{number}"},
            })
    pulls.sort(key=lambda p: p["created_at"], reverse=True)
    return pulls, details, reviews, feed_items


def build_commits() -> list[dict]:
    weights = [50, 20, 12, 8, 6, 4]
    commits = []
    for i in range(300):
        author = rng.choices(AUTHORS, weights)[0]
        t = rand_time()
        commits.append({
            "sha": f"{rng.getrandbits(160):040x}",
            "commit": {"author": {"name": author.title(), "email": f"{author}@example.com",
                                  "date": iso(t)}},
            "author": {"login": author} if i % 25 else None,  # some commits lack a GitHub user
        })
    commits.sort(key=lambda c: c["commit"]["author"]["date"], reverse=True)
    return commits


DOCS = {
    "README.md": "# Demo\n\nDemo is a sample app.\n\n## Installation\n\nRun `npm install` then"
                 " `npm start`.\n\n## Upload limits\n\nThe maximum upload size defaults to 10 MB."
                 " Set `UPLOAD_MAX_MB` in the environment to raise it.\n",
    "docs/faq.md": "# FAQ\n\n## Saving\n\nIf saving fails for filenames with emoji, upgrade to"
                   " 2.3 where filenames are normalised before writing.\n\n## Databases\n\n"
                   "PostgreSQL 15+ is supported in production. MySQL is not supported.\n",
    "docs/setup.md": "# Setup\n\n## Proxy\n\nSet `HTTPS_PROXY` before starting the app to run it"
                     " behind a corporate proxy.\n\n## Windows\n\nThe CLI runs on Windows 10+"
                     " via PowerShell.\n",
}


def contents(path: str, text: str) -> dict:
    return {
        "type": "file",
        "path": path,
        "name": path.rsplit("/", 1)[-1],
        "size": len(text.encode()),
        "encoding": "base64",
        "content": base64.b64encode(text.encode()).decode(),
        "html_url": f"{WEB}/blob/main/{path}",
    }


def main() -> None:
    issues, pairs = build_issues()
    pulls, details, reviews, feed_items = build_pulls()
    feed = sorted(issues + feed_items, key=lambda i: i["created_at"], reverse=True)
    repo = {"id": REPO_ID, "full_name": REPO, "name": "demo", "owner": {"login": "octo"},
            "private": False, "default_branch": "main", "html_url": WEB,
            "description": "Synthetic fixture repository"}
    tree = {"sha": "t", "truncated": False, "tree": [
        {"path": p, "type": "blob", "size": len(t.encode())} for p, t in DOCS.items()
    ] + [{"path": "docs", "type": "tree"}, {"path": "src/main.js", "type": "blob", "size": 10}]}
    files = {
        "repo.json": repo,
        "user_repos.json": [repo, {**repo, "id": 654321, "full_name": "octo/other",
                                   "name": "other", "html_url": "https://github.com/octo/other"}],
        "issues.json": feed,
        "duplicate_pairs.json": pairs,
        "pulls.json": pulls,
        "pull_details.json": details,
        "pull_reviews.json": reviews,
        "commits.json": build_commits(),
        "readme.json": contents("README.md", DOCS["README.md"]),
        "tree.json": tree,
        "contents.json": {p: contents(p, t) for p, t in DOCS.items()},
    }
    for name, data in files.items():
        (OUT / name).write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n",
                                encoding="utf-8", newline="\n")
    print(f"wrote {len(files)} files; {len(issues)} issues, {len(pulls)} PRs")


if __name__ == "__main__":
    main()

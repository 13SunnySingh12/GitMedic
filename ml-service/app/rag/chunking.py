"""§5.8 chunking: Markdown split on headings, then ~800-char windows with 100-char overlap."""

import re

WINDOW = 800
OVERLAP = 100
MAX_ISSUE_CHARS = 8000
_HEADING = re.compile(r"^#{1,6}\s")
_FENCE = re.compile(r"^\s*(```|~~~)")


def sections(markdown: str) -> list[str]:
    """Splits before each heading line, ignoring '#' lines inside fenced code blocks."""
    out: list[list[str]] = [[]]
    in_fence = False
    for line in markdown.splitlines():
        if _FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence and _HEADING.match(line) and any(s.strip() for s in out[-1]):
            out.append([])
        out[-1].append(line)
    return [s for s in ("\n".join(lines).strip() for lines in out) if s]


def windows(text: str) -> list[str]:
    if len(text) <= WINDOW:
        return [text]
    step = WINDOW - OVERLAP
    return [text[i : i + WINDOW] for i in range(0, len(text) - OVERLAP, step)]


def chunk(source_type: str, text: str) -> list[str]:
    text = text.strip()
    if not text:
        return []
    if source_type == "issue":
        # Spring sends title + first 1,500 body chars; an issue is a single chunk.
        return [text[:MAX_ISSUE_CHARS]]
    return [w for s in sections(text) for w in windows(s)]


def heading_anchor(chunk_text: str) -> str | None:
    """GitHub-style anchor for a chunk that starts with a heading (e.g. '## Saving' → 'saving')."""
    first = chunk_text.split("\n", 1)[0]
    if not _HEADING.match(first):
        return None
    title = first.lstrip("#").strip().lower()
    slug = re.sub(r"[^\w\- ]", "", title).replace(" ", "-")
    return slug or None

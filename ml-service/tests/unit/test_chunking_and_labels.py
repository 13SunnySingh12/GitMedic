from app.ml.label_classifier import MAX_CHARS, class_of, text_of
from app.rag.chunking import OVERLAP, WINDOW, chunk, heading_anchor, sections


def test_sections_split_on_headings_but_not_inside_code_fences():
    md = "# Title\nintro\n## Install\nrun it\n```bash\n# not a heading\n```\n## Usage\nuse it"
    parts = sections(md)
    assert [p.split("\n", 1)[0] for p in parts] == ["# Title", "## Install", "## Usage"]
    assert "# not a heading" in parts[1]


def test_long_sections_use_overlapping_windows():
    text = "## Big\n" + "x" * 2000
    chunks = chunk("doc", text)
    assert all(len(c) <= WINDOW for c in chunks)
    assert chunks[0][-OVERLAP:] == chunks[1][:OVERLAP]
    assert "".join(c[:-OVERLAP] for c in chunks[:-1]) + chunks[-1] == text


def test_issue_is_a_single_chunk_and_empty_text_has_none():
    assert chunk("issue", "Title\nbody " * 50) == [("Title\nbody " * 50).strip()]
    assert chunk("doc", "   ") == []


def test_heading_anchor():
    assert heading_anchor("## Saving files!\ntext") == "saving-files"
    assert heading_anchor("plain text") is None


def test_alias_map_and_exclusions():
    assert class_of(["bug"]) == "bug"
    assert class_of(["Enhancement"]) == "feature"
    assert class_of(["feature request", "good first issue"]) == "feature"
    assert class_of(["question"]) == "question"
    assert class_of(["help wanted"]) is None  # TBD-04 decision
    assert class_of(["bug", "enhancement"]) is None  # >1 class → excluded (BR-04)
    assert class_of([]) is None


def test_classifier_text_is_capped():
    assert len(text_of("t", "b" * 10_000)) == MAX_CHARS
    assert text_of("only title", None) == "only title\n"


def test_snippets_turn_a_leading_heading_into_a_label():
    from app.rag.search import readable

    assert (
        readable("## Saving\n\nIf saving fails,\nupgrade.") == "Saving: If saving fails, upgrade."
    )
    assert readable("plain  text\nhere") == "plain text here"

"""FR-015/016/017: idempotent pgvector indexing, duplicate search, retrieval-only answer sources."""

import hashlib

from app.db.pool import connection, one, vector_literal
from app.rag.chunking import chunk, heading_anchor
from app.rag.embedder import Embedder

SNIPPET_CHARS = 300
# ponytail: fixed relevance floor for answer sources; make it configurable if tuning shows a need.
MIN_ANSWER_SCORE = 0.30


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def index(repo_id: int, documents: list[dict], embedder: Embedder) -> dict[str, int]:
    upserted = skipped = 0
    with connection() as c, c.transaction():
        for doc in documents:
            chunks = chunk(doc["source_type"], doc["text"])
            key = (repo_id, doc["source_type"], doc["source_ref"])
            existing = {
                r["chunk_index"]: r["content_hash"]
                for r in c.execute(
                    "select chunk_index, content_hash from gitmedic.embeddings"
                    " where repo_id = %s and source_type = %s and source_ref = %s",
                    key,
                ).fetchall()
            }
            todo = [
                (i, text, h)
                for i, text in enumerate(chunks)
                if existing.get(i) != (h := _sha(text))
            ]
            skipped += len(chunks) - len(todo)
            if todo:
                vectors = embedder.embed([t for _, t, _ in todo])
                for (i, text, h), vec in zip(todo, vectors, strict=True):
                    c.execute(
                        """insert into gitmedic.embeddings
                             (repo_id, source_type, source_ref, source_url, chunk_index,
                              content, content_hash, embedding)
                           values (%s, %s, %s, %s, %s, %s, %s, %s::extensions.vector)
                           on conflict (repo_id, source_type, source_ref, chunk_index) do update
                           set source_url = excluded.source_url, content = excluded.content,
                               content_hash = excluded.content_hash,
                               embedding = excluded.embedding, created_at = now()""",
                        (*key, doc["source_url"], i, text, h, vector_literal(vec)),
                    )
                upserted += len(todo)
            # A shorter document leaves no stale tail chunks behind.
            c.execute(
                "delete from gitmedic.embeddings where repo_id = %s and source_type = %s"
                " and source_ref = %s and chunk_index >= %s",
                (*key, len(chunks)),
            )
    return {"chunks_upserted": upserted, "chunks_skipped": skipped}


def _query_vector(
    repo_id: int, issue_number: int, title: str, body: str | None, embedder: Embedder
) -> str:
    """Reuses the issue's stored embedding when present, so search matches what was indexed."""
    with connection() as c:
        row = c.execute(
            "select embedding::text as v from gitmedic.embeddings where repo_id = %s"
            " and source_type = 'issue' and source_ref = %s and chunk_index = 0",
            (repo_id, str(issue_number)),
        ).fetchone()
    if row:
        return row["v"]
    text = chunk("issue", f"{title}\n{(body or '')[:1500]}") or [title]
    return vector_literal(embedder.embed(text)[:1][0])


def _nearest(sql_filter: str, params: dict, k: int) -> list[dict]:
    sql = f"""select source_type, source_ref, source_url, content,
                     1 - (embedding operator(extensions.<=>) %(q)s::extensions.vector) as score
              from gitmedic.embeddings e where e.repo_id = %(repo)s and {sql_filter}
              order by embedding operator(extensions.<=>) %(q)s::extensions.vector limit %(k)s"""  # noqa: S608 - sql_filter is a module constant
    with connection() as c, c.transaction():
        # pgvector ≥ 0.8: keep scanning the HNSW graph until enough rows pass the repo filter.
        c.execute("set local hnsw.iterative_scan = relaxed_order")
        rows = c.execute(sql, {**params, "k": k}).fetchall()
    return sorted(rows, key=lambda r: -r["score"])


_DUPLICATE_FILTER = "e.source_type = 'issue' and e.source_ref <> %(self)s"
_ANSWER_FILTER = """(e.source_type in ('readme', 'doc') or (e.source_type = 'issue'
    and e.source_ref <> %(self)s and exists (select 1 from gitmedic.issues i
    where i.repo_id = e.repo_id and i.number::text = e.source_ref and i.state = 'closed')))"""


def duplicates(
    repo_id: int,
    issue_number: int,
    title: str,
    body: str | None,
    top_k: int,
    threshold: float,
    embedder: Embedder,
) -> dict:
    q = _query_vector(repo_id, issue_number, title, body, embedder)
    rows = _nearest(_DUPLICATE_FILTER, {"repo": repo_id, "q": q, "self": str(issue_number)}, top_k)
    matches = [{"issue_number": int(r["source_ref"]), "score": float(r["score"])} for r in rows]
    top = matches[0]["score"] if matches else None
    return {
        "matches": matches,
        "top_score": top,
        "is_possible_duplicate": top is not None and top >= threshold,
    }


def answer_sources(
    repo_id: int, issue_number: int, title: str, body: str | None, embedder: Embedder
) -> list[dict]:
    q = _query_vector(repo_id, issue_number, title, body, embedder)
    rows = _nearest(_ANSWER_FILTER, {"repo": repo_id, "q": q, "self": str(issue_number)}, 5)
    sources = []
    for r in rows:
        if r["score"] < MIN_ANSWER_SCORE:
            continue
        ref, url = r["source_ref"], r["source_url"]
        if r["source_type"] != "issue" and (anchor := heading_anchor(r["content"])):
            ref, url = f"{ref}#{anchor}", f"{url}#{anchor}"
        sources.append(
            {
                "type": "issue" if r["source_type"] == "issue" else "doc",
                "ref": ref,
                "url": url,
                "snippet": r["content"][:SNIPPET_CHARS],
                "score": float(r["score"]),
            }
        )
    return sources


def retrieval_draft(sources: list[dict]) -> str:
    """FR-017: an evidence-only draft. It never invents a fix; it points at what was retrieved."""
    if not sources:
        return (
            "Insufficient context: no closed issues or documentation closely match this issue,"
            " so no answer can be drafted from the repository's own material."
        )
    lines = [
        "These closed issues and documentation sections look related (retrieved automatically"
        " by similarity; verify before replying):",
        "",
    ]
    for n, s in enumerate(sources, 1):
        label = f"closed issue #{s['ref']}" if s["type"] == "issue" else s["ref"]
        excerpt = " ".join(s["snippet"].split())
        lines.append(f"{n}. {label} ({s['url']}): {excerpt}")
    return "\n".join(lines)


def delete_repo(repo_id: int) -> None:
    with connection() as c:
        c.execute("delete from gitmedic.embeddings where repo_id = %s", (repo_id,))


def chunk_count(repo_id: int) -> int:
    with connection() as c:
        return one(
            c, "select count(*) as n from gitmedic.embeddings where repo_id = %s", (repo_id,)
        )["n"]

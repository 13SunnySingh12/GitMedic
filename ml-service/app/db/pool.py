from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import psycopg
from psycopg.rows import DictRow, dict_row
from psycopg_pool import ConnectionPool

_pool: ConnectionPool[psycopg.Connection[DictRow]] | None = None


def open_pool(dsn: str) -> None:
    global _pool
    # open=False + explicit open: the service still starts (and /health reports db=false) when the
    # database is paused or unreachable.
    _pool = ConnectionPool(
        dsn,
        connection_class=psycopg.Connection[DictRow],
        min_size=1,
        max_size=5,
        open=False,
        kwargs={"row_factory": dict_row},
    )
    _pool.open(wait=False)


def close_pool() -> None:
    if _pool is not None:
        _pool.close()


@contextmanager
def connection() -> Iterator[psycopg.Connection[dict[str, Any]]]:
    if _pool is None:
        raise RuntimeError("database pool not opened")
    with _pool.connection(timeout=10) as conn:
        yield conn


def one(conn: psycopg.Connection[dict[str, Any]], sql: str, params: Any) -> dict[str, Any]:
    """The single row of an aggregate query (which always yields one)."""
    row = conn.execute(sql, params).fetchone()
    if row is None:
        raise RuntimeError("query returned no row")
    return row


def vector_literal(vec: list[float]) -> str:
    """pgvector text form; passed as a bound parameter and cast with ::extensions.vector."""
    return "[" + ",".join(f"{x:.7f}" for x in vec) + "]"

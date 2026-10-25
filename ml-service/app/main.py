"""GitMedic ML/RAG service. Run bound to loopback only:
uvicorn app.main:app --host 127.0.0.1 --port 8000
"""

import logging
import re
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from contextvars import ContextVar

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.core import errors
from app.core.config import get_settings
from app.db import pool, queries
from app.ml import store
from app.rag import embedder

MAX_BODY = 2 * 1024 * 1024
_SAFE_ID = re.compile(r"[A-Za-z0-9-]{1,64}")
log = logging.getLogger("gitmedic.ml")
# NFR-013: the request id of the current request, attached to every log line it produces.
_request_id: ContextVar[str] = ContextVar("request_id", default="-")


class _RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = _request_id.get()
        return True


logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s [%(request_id)s] %(name)s %(message)s"
)
for _handler in logging.getLogger().handlers:
    _handler.addFilter(_RequestIdFilter())


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    settings.model_dir.mkdir(parents=True, exist_ok=True)
    pool.open_pool(settings.ml_db_dsn)
    try:
        removed = store.sweep_orphans(settings.model_dir, queries.repo_ids())
        if removed:
            log.info("Removed model files of untracked repos %s", removed)
    except Exception as e:
        # E.g. DB unreachable, or the backend has not run its migrations yet.
        log.warning(
            "Skipped orphan model sweep (%s); it runs again on next start", type(e).__name__
        )
    yield
    pool.close_pool()


# No CORS middleware on purpose: the browser must never reach this service (SEC-07).
app = FastAPI(title="GitMedic ML", lifespan=lifespan, docs_url=None, redoc_url=None)
errors.install(app)
app.include_router(router)


@app.middleware("http")
async def request_context(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    rid = request.headers.get("x-request-id", "")
    request.state.request_id = rid if _SAFE_ID.fullmatch(rid) else str(uuid.uuid4())
    token = _request_id.set(request.state.request_id)
    try:
        return await _handle(request, call_next)
    finally:
        _request_id.reset(token)


async def _handle(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    if request.method in ("POST", "PUT", "PATCH"):
        length = request.headers.get("content-length")
        if length is None or not length.isdigit() or int(length) > MAX_BODY:
            body = {
                "code": "PAYLOAD_TOO_LARGE",
                "message": "Request body must declare a Content-Length of at most 2 MB.",
                "request_id": request.state.request_id,
            }
            return JSONResponse(body, status_code=413)
    response = await call_next(request)
    response.headers["X-Request-Id"] = request.state.request_id
    log.info("%s %s -> %s", request.method, request.url.path, response.status_code)
    return response


@app.get("/health")
def health() -> dict:
    """ML-01: no token required; reports DB reachability, model dir and embedder state."""
    db_ok = True
    try:
        with pool.connection() as c:
            c.execute("select 1")
    except Exception:
        db_ok = False
    model_dir = get_settings().model_dir
    dir_ok = model_dir.is_dir()
    return {
        "status": "ok" if db_ok and dir_ok else "degraded",
        "db": db_ok,
        "model_dir": dir_ok,
        "embedder_loaded": embedder.is_loaded(),
    }

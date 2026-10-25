"""GitMedic ML/RAG service. Run bound to loopback only:
uvicorn app.main:app --host 127.0.0.1 --port 8000
"""

import logging
import re
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.core import errors
from app.core.config import get_settings
from app.db import pool
from app.rag import embedder

MAX_BODY = 2 * 1024 * 1024
_SAFE_ID = re.compile(r"[A-Za-z0-9-]{1,64}")
log = logging.getLogger("gitmedic.ml")
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s [%(request_id)s] %(name)s %(message)s"
)
_old_factory = logging.getLogRecordFactory()


def _record_factory(*args, **kwargs):  # type: ignore[no-untyped-def]
    record = _old_factory(*args, **kwargs)
    if not hasattr(record, "request_id"):
        record.request_id = "-"
    return record


logging.setLogRecordFactory(_record_factory)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    settings.model_dir.mkdir(parents=True, exist_ok=True)
    pool.open_pool(settings.ml_db_dsn)
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
    log.info(
        "%s %s -> %s",
        request.method,
        request.url.path,
        response.status_code,
        extra={"request_id": request.state.request_id},
    )
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

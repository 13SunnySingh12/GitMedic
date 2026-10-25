from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


class MLError(Exception):
    """An error with a stable code, rendered as {code, message, request_id}."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def _body(request: Request, code: str, message: str) -> dict[str, str | None]:
    return {"code": code, "message": message, "request_id": request.state.request_id}


def install(app: FastAPI) -> None:
    @app.exception_handler(MLError)
    async def ml_error(request: Request, exc: MLError) -> JSONResponse:
        return JSONResponse(_body(request, exc.code, exc.message), status_code=exc.status)

    @app.exception_handler(RequestValidationError)
    async def validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        fields = ", ".join(".".join(str(p) for p in e["loc"][1:]) for e in exc.errors())
        return JSONResponse(
            _body(request, "VALIDATION_ERROR", f"Invalid request: {fields}"), status_code=400
        )

    @app.exception_handler(Exception)
    async def unexpected(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(
            _body(request, "INTERNAL_ERROR", "Unexpected error. See request_id."), status_code=500
        )

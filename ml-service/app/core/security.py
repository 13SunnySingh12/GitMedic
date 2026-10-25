import hmac
from typing import Annotated

from fastapi import Depends, Header

from app.core.config import Settings, get_settings
from app.core.errors import MLError


def require_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_token: Annotated[str | None, Header()] = None,
) -> None:
    """SEC-07: constant-time check of the shared X-Internal-Token."""
    expected = settings.internal_api_token.encode()
    if x_internal_token is None or not hmac.compare_digest(x_internal_token.encode(), expected):
        raise MLError(401, "UNAUTHENTICATED", "Missing or invalid internal token.")

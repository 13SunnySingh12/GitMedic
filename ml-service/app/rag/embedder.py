"""Local sentence-transformers embeddings (384-dim, normalized, CPU, batch 32)."""

import threading
from typing import Any, Protocol

from app.core.config import get_settings

DIM = 384


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> list[list[float]]: ...


class MiniLMEmbedder:
    def __init__(self, model_name: str) -> None:
        self._name = model_name
        self._model: Any = None
        self._lock = threading.Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self._model is None:
            with self._lock:
                if self._model is None:
                    # First use downloads (~90 MB) or loads from the Hugging Face cache.
                    from sentence_transformers import SentenceTransformer

                    self._model = SentenceTransformer(self._name, device="cpu")
        vectors = self._model.encode(texts, batch_size=32, normalize_embeddings=True)
        return [v.tolist() for v in vectors]


_default: MiniLMEmbedder | None = None


def get_embedder() -> Embedder:
    """FastAPI dependency; tests override it with a deterministic fake."""
    global _default
    if _default is None:
        _default = MiniLMEmbedder(get_settings().embedding_model)
    return _default


def is_loaded() -> bool:
    return _default is not None and _default.loaded

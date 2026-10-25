"""ML-01 … ML-11 request/response contracts (Pydantic validates types, lengths and enums)."""

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

RepoId = Field(gt=0)


class RepoRequest(BaseModel):
    repo_id: int = RepoId


class IssueText(BaseModel):
    id: int
    title: str = Field(max_length=1000)
    body: str | None = Field(default=None, max_length=65535)


class PredictLabelRequest(BaseModel):
    repo_id: int = RepoId
    issues: list[IssueText] = Field(max_length=500)


class PrFeatures(BaseModel):
    id: int
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    changed_files: int = Field(ge=0)
    review_count: int = Field(ge=0)
    author_login: str | None = Field(default=None, max_length=100)


class PredictMergeRequest(BaseModel):
    repo_id: int = RepoId
    prs: list[PrFeatures] = Field(max_length=500)


class RiskRequest(BaseModel):
    repo_id: int = RepoId
    as_of: datetime


class Document(BaseModel):
    source_type: Literal["readme", "doc", "issue"]
    source_ref: str = Field(min_length=1, max_length=500)
    source_url: str = Field(min_length=1, max_length=2000)
    text: str = Field(max_length=200_000)


class IndexRequest(BaseModel):
    repo_id: int = RepoId
    documents: list[Document] = Field(max_length=2000)


class IssueQuery(BaseModel):
    repo_id: int = RepoId
    issue_number: int = Field(gt=0)
    title: str = Field(max_length=1000)
    body: str | None = Field(default=None, max_length=65535)


class DuplicatesRequest(IssueQuery):
    top_k: int | None = Field(default=None, ge=1, le=20)


class SuggestRequest(IssueQuery):
    use_llm: bool = False

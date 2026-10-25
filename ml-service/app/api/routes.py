from typing import Annotated

from fastapi import APIRouter, Depends, Path, Response

from app.api.schemas import (
    DuplicatesRequest,
    IndexRequest,
    PredictLabelRequest,
    PredictMergeRequest,
    RepoRequest,
    RiskRequest,
    SuggestRequest,
)
from app.core.config import Settings, get_settings
from app.core.security import require_internal_token
from app.db import queries
from app.ml import label_classifier, merge_time, risk, store
from app.rag import search
from app.rag.embedder import Embedder, get_embedder

router = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_internal_token)])
SettingsDep = Annotated[Settings, Depends(get_settings)]
EmbedderDep = Annotated[Embedder, Depends(get_embedder)]
RepoPath = Annotated[int, Path(gt=0)]


@router.post("/train/label")
def train_label(req: RepoRequest, settings: SettingsDep) -> dict:
    meta = label_classifier.train(
        settings.model_dir, req.repo_id, queries.labelled_issues(req.repo_id)
    )
    return {k: meta[k] for k in ("trained_at", "n_samples", "class_counts", "macro_f1")}


@router.post("/predict/label")
def predict_label(req: PredictLabelRequest, settings: SettingsDep) -> dict:
    issues = [i.model_dump() for i in req.issues]
    return {"predictions": label_classifier.predict(settings.model_dir, req.repo_id, issues)}


@router.post("/train/merge-time")
def train_merge_time(req: RepoRequest, settings: SettingsDep) -> dict:
    meta = merge_time.train(settings.model_dir, req.repo_id, queries.merged_prs(req.repo_id))
    return {k: meta[k] for k in ("trained_at", "n_samples", "mae_days")}


@router.post("/predict/merge-time")
def predict_merge_time(req: PredictMergeRequest, settings: SettingsDep) -> dict:
    prs = [p.model_dump() for p in req.prs]
    logins = sorted({p["author_login"] for p in prs if p["author_login"]})
    history = queries.author_merge_history(req.repo_id, logins)
    return {"predictions": merge_time.predict(settings.model_dir, req.repo_id, prs, history)}


@router.post("/risk")
def compute_risk(req: RiskRequest) -> dict:
    return risk.score(queries.risk_facts(req.repo_id, req.as_of))


@router.post("/index")
def index(req: IndexRequest, embedder: EmbedderDep) -> dict:
    return search.index(req.repo_id, [d.model_dump() for d in req.documents], embedder)


@router.post("/duplicates")
def duplicates(req: DuplicatesRequest, settings: SettingsDep, embedder: EmbedderDep) -> dict:
    return search.duplicates(
        req.repo_id,
        req.issue_number,
        req.title,
        req.body,
        req.top_k or settings.duplicate_top_k,
        settings.duplicate_threshold,
        embedder,
    )


@router.post("/suggest")
def suggest(req: SuggestRequest, embedder: EmbedderDep) -> dict:
    sources = search.answer_sources(req.repo_id, req.issue_number, req.title, req.body, embedder)
    # FR-018 (optional LLM polish, P2) is not enabled: TBD-05 (Groq terms/limits) is unverified.
    return {
        "sources": sources,
        "draft": search.retrieval_draft(sources),
        "mode": "RETRIEVAL_ONLY",
        "llm_status": "DISABLED",
    }


@router.get("/repos/{repo_id}/models")
def model_status(repo_id: RepoPath, settings: SettingsDep) -> dict:
    """ML-11 (added in spec v1.1.0): model metadata + training readiness for the dashboard."""
    return {
        "label": store.meta(settings.model_dir, repo_id, label_classifier.NAME),
        "label_data": label_classifier.readiness(queries.labelled_issues(repo_id)),
        "merge_time": store.meta(settings.model_dir, repo_id, merge_time.NAME),
        "merge_time_data": merge_time.readiness(len(queries.merged_prs(repo_id))),
        "chunks": search.chunk_count(repo_id),
    }


@router.delete("/repos/{repo_id}", status_code=204)
def delete_repo(repo_id: RepoPath, settings: SettingsDep) -> Response:
    store.delete_repo(settings.model_dir, repo_id)
    search.delete_repo(repo_id)
    return Response(status_code=204)

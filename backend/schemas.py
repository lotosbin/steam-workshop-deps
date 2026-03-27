"""Pydantic schemas for API request/response models."""
from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: str
    timestamp: str


class ErrorResponse(BaseModel):
    detail: str


class GameInfo(BaseModel):
    """A game (Steam App ID) with mod count."""
    app_id: str
    mod_count: int


class ModSearchResult(BaseModel):
    """Summary of a mod for search results."""
    workshop_id: str
    title: str
    app_id: Optional[str] = None
    obsolete: Optional[bool] = False


class ModDetail(BaseModel):
    """Full mod properties from Neo4j."""
    id: Optional[str] = None
    workshop_id: Optional[str] = None
    title: Optional[str] = None
    author: Optional[str] = None
    author_id: Optional[str] = None
    author_profile_url: Optional[str] = None
    canonical_url: Optional[str] = None
    preview_url: Optional[str] = None
    posted: Optional[str] = None
    updated: Optional[str] = None
    file_size: Optional[str] = None
    description: Optional[str] = None
    obsolete: Optional[bool] = False
    imported_at: Optional[str] = None
    app_id: Optional[str] = None
    source: Optional[str] = None


class GraphNode(BaseModel):
    """A node in the dependency graph (for Cytoscape rendering)."""
    workshop_id: str
    title: Optional[str] = None
    obsolete: Optional[bool] = False


class GraphEdge(BaseModel):
    """An edge in the dependency graph."""
    from_workshop_id: str
    to_workshop_id: str
    edge_type: str = "REQUIRES"


class GraphData(BaseModel):
    """Response for /api/graph/{workshop_id}."""
    nodes: list[GraphNode]
    edges: list[GraphEdge]
    total_nodes: int
    truncated: bool


class PathResult(BaseModel):
    """Response for /api/graph/path."""
    path: list[GraphNode]
    edges: list[GraphEdge]

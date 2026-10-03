# Backend API — FastAPI proxy over Neo4j (read-only)
from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from backend import neo4j_client
from backend.neo4j_client import rows_as_dicts
from backend.schemas import (
    GameInfo,
    GraphData,
    GraphEdge,
    GraphNode,
    ModDetail,
    ModSearchResult,
    PathResult,
)

app = FastAPI(title="Steam Workshop Graph API", version="0.1.0")

# CORS — allow all origins in development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(neo4j_client.Neo4jError)
async def neo4j_exception_handler(request, exc: neo4j_client.Neo4jError):
    """Map Neo4jError to HTTP 500 with JSON error body."""
    return JSONResponse(
        status_code=500,
        content={"detail": f"Neo4j error: {exc.errors}"},
    )


# ─── Helpers ──────────────────────────────────────────────────────────────────


def as_str(value: Any) -> Optional[str]:
    """Neo4j may return strings or numbers; the response schemas declare str."""
    return None if value is None else str(value)


def millis_to_iso(value: Any) -> Optional[str]:
    """`imported_at` is stored as epoch milliseconds (a Neo4j integer)."""
    if value is None:
        return None
    try:
        return datetime.fromtimestamp(int(value) / 1000, tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OSError):
        return str(value)


# ─── Endpoints ────────────────────────────────────────────────────────────────


@app.get("/api/health")
async def health():
    """Health check — confirms service is running."""
    return {"status": "ok", "timestamp": datetime.now(timezone.utc).isoformat()}


@app.get("/api/games", response_model=list[GameInfo])
async def list_games():
    """List all distinct game appIds with mod counts (API-03)."""
    try:
        result = await neo4j_client.run_query(
            """
            MATCH (m:Mod)
            WHERE m.app_id IS NOT NULL
            RETURN m.app_id AS app_id, count(m) AS mod_count
            ORDER BY mod_count DESC
            """
        )
        return [
            GameInfo(app_id=as_str(row["app_id"]) or "", mod_count=int(row["mod_count"]))
            for row in rows_as_dicts(result)
        ]
    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e


@app.get("/api/mods/search", response_model=list[ModSearchResult])
async def search_mods(
    q: str = Query(..., min_length=1, description="Search prefix"),
    game: str | None = Query(None, description="Filter by app_id"),
):
    """Search mods by title prefix (API-04). Returns up to 20 results."""
    try:
        # Steam page titles carry a "Steam Workshop::" / "Steam Community ::"
        # prefix, which would make a raw prefix match useless. Match against the
        # part after the last "::" so users can search by the actual mod name.
        result = await neo4j_client.run_query(
            """
            MATCH (m:Mod)
            WITH m, toLower(coalesce(m.title, '')) AS lowered
            WITH m, CASE
                       WHEN lowered STARTS WITH 'steam' AND lowered CONTAINS '::'
                         THEN trim(split(lowered, '::')[-1])
                       ELSE lowered
                     END AS clean_title
            WHERE clean_title STARTS WITH toLower($prefix)
              AND ($game IS NULL OR m.app_id = $game)
            RETURN m.workshop_id AS workshop_id, m.title AS title,
                   m.app_id AS app_id, m.obsolete AS obsolete
            ORDER BY clean_title
            LIMIT 20
            """,
            {"prefix": q, "game": game},
        )
        return [
            ModSearchResult(
                workshop_id=as_str(row.get("workshop_id")) or "",
                title=as_str(row.get("title")) or "",
                app_id=as_str(row.get("app_id")),
                obsolete=bool(row.get("obsolete")),
            )
            for row in rows_as_dicts(result)
        ]
    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e


# NOTE: declared BEFORE /api/graph/{workshop_id}; otherwise the parameterised
# route matches first and swallows "path" as a workshop_id.
@app.get("/api/graph/path", response_model=PathResult)
async def get_shortest_path(
    from_: str = Query(..., alias="from", description="Source workshop_id"),
    to: str = Query(..., description="Target workshop_id"),
):
    """Find shortest path between two mods via REQUIRES edges (API-07, API-09)."""
    try:
        result = await neo4j_client.run_query(
            """
            MATCH (a:Mod {workshop_id: $from}), (b:Mod {workshop_id: $to})
            MATCH path = shortestPath((a)-[:REQUIRES*1..20]-(b))
            RETURN [n IN nodes(path) | {
                       workshop_id: n.workshop_id,
                       title: n.title,
                       obsolete: n.obsolete
                   }] AS path_nodes,
                   [r IN relationships(path) | {
                       from_workshop_id: startNode(r).workshop_id,
                       to_workshop_id: endNode(r).workshop_id
                   }] AS path_edges
            """,
            {"from": from_, "to": to},
        )
        rows = rows_as_dicts(result)
        if not rows:
            return PathResult(path=[], edges=[])

        row = rows[0]
        return PathResult(
            path=[
                GraphNode(
                    workshop_id=as_str(n.get("workshop_id")) or "",
                    title=as_str(n.get("title")),
                    obsolete=bool(n.get("obsolete")),
                )
                for n in (row.get("path_nodes") or [])
            ],
            edges=[
                GraphEdge(
                    from_workshop_id=as_str(e.get("from_workshop_id")) or "",
                    to_workshop_id=as_str(e.get("to_workshop_id")) or "",
                    edge_type="REQUIRES",
                )
                for e in (row.get("path_edges") or [])
            ],
        )
    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e


@app.get("/api/graph/{workshop_id}", response_model=GraphData)
async def get_graph(
    workshop_id: str,
    depth: int = Query(default=2, ge=1, le=3),
):
    """Get mod dependency graph neighborhood (API-06, API-09).

    Returns the mod plus its N-depth REQUIRES neighborhood,
    bounded to 200 nodes max, deduplicated.
    """
    # Neo4j does not accept parameters as variable-length path bounds, so the
    # (already range-validated) integer is inlined. Guard it anyway.
    depth_int = int(depth)
    if not 1 <= depth_int <= 3:
        raise HTTPException(status_code=400, detail="depth must be between 1 and 3")

    try:
        # Step 1: collect bounded neighborhood
        nodes_result = await neo4j_client.run_query(
            f"""
            MATCH (start:Mod {{workshop_id: $workshop_id}})
            MATCH path = (start)-[:REQUIRES*0..{depth_int}]-(neighbor:Mod)
            WITH neighbor, min(length(path)) AS pathLength
            ORDER BY pathLength
            LIMIT 200
            RETURN collect(DISTINCT {{
                workshop_id: neighbor.workshop_id,
                title: neighbor.title,
                obsolete: neighbor.obsolete
            }}) AS nodes
            """,
            {"workshop_id": workshop_id},
        )
        node_rows = rows_as_dicts(nodes_result)
        raw_nodes: list[dict] = (node_rows[0].get("nodes") or []) if node_rows else []

        # An existing mod always yields at least itself (path length 0), so an
        # empty collection means the workshop_id is unknown.
        if not raw_nodes:
            raise HTTPException(
                status_code=404, detail=f"Mod not found: {workshop_id}"
            )

        nodes = [
            GraphNode(
                workshop_id=as_str(n.get("workshop_id")) or "",
                title=as_str(n.get("title")),
                obsolete=bool(n.get("obsolete")),
            )
            for n in raw_nodes
        ]
        workshop_ids = {n.workshop_id for n in nodes}

        # Step 2: REQUIRES edges between the collected nodes, in their true
        # direction so the UI can draw dependency arrows (UI-10).
        edges: list[GraphEdge] = []
        if workshop_ids:
            edges_result = await neo4j_client.run_query(
                """
                UNWIND $workshop_ids AS wid
                MATCH (m:Mod {workshop_id: wid})-[:REQUIRES]->(other:Mod)
                WHERE other.workshop_id IN $workshop_ids
                RETURN DISTINCT m.workshop_id AS from_workshop_id,
                       other.workshop_id AS to_workshop_id
                """,
                {"workshop_ids": list(workshop_ids)},
            )
            edges = [
                GraphEdge(
                    from_workshop_id=as_str(row.get("from_workshop_id")) or "",
                    to_workshop_id=as_str(row.get("to_workshop_id")) or "",
                    edge_type="REQUIRES",
                )
                for row in rows_as_dicts(edges_result)
                if row.get("from_workshop_id") and row.get("to_workshop_id")
            ]

        total = len(nodes)
        return GraphData(
            nodes=nodes,
            edges=edges,
            total_nodes=total,
            truncated=(total == 200),
        )
    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e


@app.get("/api/mods/{workshop_id}", response_model=ModDetail)
async def get_mod(workshop_id: str):
    """Get full mod details by workshop_id (API-05, API-09)."""
    if not re.match(r"^\d+$", workshop_id):
        raise HTTPException(status_code=400, detail="Invalid workshop_id")
    try:
        result = await neo4j_client.run_query(
            """
            MATCH (m:Mod {workshop_id: $workshop_id})
            RETURN m.id AS id, m.workshop_id AS workshop_id, m.title AS title,
                   m.author AS author, m.author_id AS author_id,
                   m.author_profile_url AS author_profile_url,
                   m.canonical_url AS canonical_url, m.preview_url AS preview_url,
                   m.posted AS posted, m.updated AS updated,
                   m.file_size AS file_size, m.description AS description,
                   m.obsolete AS obsolete, m.imported_at AS imported_at,
                   m.app_id AS app_id, m.source AS source
            """,
            {"workshop_id": workshop_id},
        )
        rows = rows_as_dicts(result)
        if not rows:
            raise HTTPException(
                status_code=404, detail=f"Mod not found: {workshop_id}"
            )
        row = rows[0]
        return ModDetail(
            id=as_str(row.get("id")),
            workshop_id=as_str(row.get("workshop_id")),
            title=as_str(row.get("title")),
            author=as_str(row.get("author")),
            author_id=as_str(row.get("author_id")),
            author_profile_url=as_str(row.get("author_profile_url")),
            canonical_url=as_str(row.get("canonical_url")),
            preview_url=as_str(row.get("preview_url")),
            posted=as_str(row.get("posted")),
            updated=as_str(row.get("updated")),
            file_size=as_str(row.get("file_size")),
            description=as_str(row.get("description")),
            obsolete=bool(row.get("obsolete")),
            imported_at=millis_to_iso(row.get("imported_at")),
            app_id=as_str(row.get("app_id")),
            source=as_str(row.get("source")),
        )
    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e


# ─── Static frontend (mounted last so /api/* routes win) ──────────────────────
WEB_DIR = Path(__file__).resolve().parent.parent / "web"
if WEB_DIR.is_dir():
    app.mount("/", StaticFiles(directory=str(WEB_DIR), html=True), name="web")

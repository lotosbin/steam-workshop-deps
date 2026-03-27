# Backend API — FastAPI proxy over Neo4j (read-only)
from __future__ import annotations

import re
from datetime import datetime, timezone

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from backend import neo4j_client
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
            RETURN DISTINCT m.app_id AS app_id, count(m) AS mod_count
            ORDER BY mod_count DESC
            """
        )
        return [
            GameInfo(app_id=row["app_id"], mod_count=int(row["mod_count"]))
            for row in (d["row"] for d in result.get("data", []))
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
        # Case-insensitive prefix match
        result = await neo4j_client.run_query(
            """
            MATCH (m:Mod)
            WHERE toLower(m.title) STARTS WITH toLower($prefix)
              AND ($game IS NULL OR m.app_id = $game)
            RETURN m.workshop_id AS workshop_id, m.title AS title,
                   m.app_id AS app_id, m.obsolete AS obsolete
            ORDER BY m.title
            LIMIT 20
            """,
            {"prefix": q, "game": game},
        )
        return [
            ModSearchResult(
                workshop_id=row["workshop_id"] or "",
                title=row["title"] or "",
                app_id=row.get("app_id"),
                obsolete=bool(row.get("obsolete")),
            )
            for row in (d["row"] for d in result.get("data", []))
        ]
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
        rows = result.get("data", [])
        if not rows:
            raise HTTPException(
                status_code=404, detail=f"Mod not found: {workshop_id}"
            )
        row = rows[0]["row"]
        return ModDetail(
            id=row.get("id"),
            workshop_id=row.get("workshop_id"),
            title=row.get("title"),
            author=row.get("author"),
            author_id=row.get("author_id"),
            author_profile_url=row.get("author_profile_url"),
            canonical_url=row.get("canonical_url"),
            preview_url=row.get("preview_url"),
            posted=row.get("posted"),
            updated=row.get("updated"),
            file_size=row.get("file_size"),
            description=row.get("description"),
            obsolete=bool(row.get("obsolete")),
            imported_at=row.get("imported_at"),
            app_id=row.get("app_id"),
            source=row.get("source"),
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
    try:
        # Step 1: collect bounded neighborhood
        nodes_result = await neo4j_client.run_query(
            """
            MATCH (start:Mod {workshop_id: $workshop_id})
            MATCH path = (start)-[:REQUIRES*0..$depth]-(neighbor:Mod)
            WITH neighbor, min(length(path)) AS pathLength
            ORDER BY pathLength
            LIMIT 200
            RETURN collect(DISTINCT {
                workshop_id: neighbor.workshop_id,
                title: neighbor.title,
                obsolete: neighbor.obsolete
            }) AS nodes
            """,
            {"workshop_id": workshop_id, "depth": depth},
        )
        node_data = nodes_result.get("data", [])
        if not node_data:
            raise HTTPException(
                status_code=404, detail=f"Mod not found: {workshop_id}"
            )
        raw_nodes: list[dict] = node_data[0]["row"]["nodes"]
        nodes = [
            GraphNode(
                workshop_id=n.get("workshop_id", ""),
                title=n.get("title"),
                obsolete=bool(n.get("obsolete")),
            )
            for n in raw_nodes
        ]
        workshop_ids = {n.workshop_id for n in nodes}

        # Step 2: collect edges between collected nodes
        if workshop_ids:
            edges_result = await neo4j_client.run_query(
                """
                UNWIND $workshop_ids AS wid
                MATCH (m:Mod {workshop_id: wid})
                MATCH (m)-[r:REQUIRES]-(other:Mod)
                WHERE other.workshop_id IN $workshop_ids
                RETURN DISTINCT m.workshop_id AS from_workshop_id,
                       other.workshop_id AS to_workshop_id,
                       'REQUIRES' AS edge_type
                """,
                {"workshop_ids": list(workshop_ids)},
            )
            edges = [
                GraphEdge(
                    from_workshop_id=d["row"]["from_workshop_id"],
                    to_workshop_id=d["row"]["to_workshop_id"],
                    edge_type=d["row"]["edge_type"],
                )
                for d in edges_result.get("data", [])
                if d["row"].get("from_workshop_id") and d["row"].get("to_workshop_id")
            ]
        else:
            edges = []

        total = len(nodes)
        return GraphData(
            nodes=nodes,
            edges=edges,
            total_nodes=total,
            truncated=(total == 200),
        )
    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e


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
            CALL {
                WITH a, b
                MATCH path = shortestPath((a)-[:REQUIRES*1..20]-(b))
                RETURN path
            }
            RETURN path
            """,
            {"from": from_, "to": to},
        )
        rows = result.get("data", [])
        if not rows:
            return PathResult(path=[], edges=[])

        # Extract path from Neo4j path representation
        raw_path = rows[0]["row"]
        if raw_path is None or raw_path == []:
            return PathResult(path=[], edges=[])

        # Neo4j path response: list of nodes then relationships
        # Handle both list format and path object format
        if isinstance(raw_path, list):
            # Path is a list of node/relationship pairs
            nodes_out: list[GraphNode] = []
            edges_out: list[GraphEdge] = []
            for item in raw_path:
                if isinstance(item, dict):
                    # Node dict
                    if "properties" in item:
                        props = item["properties"]
                        nodes_out.append(
                            GraphNode(
                                workshop_id=props.get("workshop_id", ""),
                                title=props.get("title"),
                                obsolete=bool(props.get("obsolete")),
                            )
                        )
                    # Relationship dict
                    elif "type" in item:
                        edges_out.append(
                            GraphEdge(
                                from_workshop_id=item.get("start", ""),
                                to_workshop_id=item.get("end", ""),
                                edge_type=item.get("type", "REQUIRES"),
                            )
                        )
        else:
            nodes_out, edges_out = [], []

        return PathResult(path=nodes_out, edges=edges_out)

    except neo4j_client.Neo4jError as e:
        raise HTTPException(status_code=500, detail=str(e.errors)) from e

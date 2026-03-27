# Plan 02 Summary: API-ENDPOINTS

**Phase:** 01-backend-api
**Plan:** 02-API-ENDPOINTS
**Wave:** 2
**Completed:** 2026-03-27

## Tasks Executed

| # | Task | Status |
|---|------|--------|
| 1 | Define Pydantic response schemas | ✓ |
| 2 | Implement /api/games and /api/mods/search | ✓ |
| 3 | Implement /api/mods/{workshop_id} | ✓ |
| 4 | Implement /api/graph/{workshop_id} (BFS neighborhood) | ✓ |
| 5 | Implement /api/graph/path (shortest path) | ✓ |

## What Was Built

- `backend/schemas.py` — Pydantic models:
  - `ModSearchResult`, `ModDetail`, `GameInfo`, `GraphNode`, `GraphEdge`, `GraphData`, `PathResult`, `HealthResponse`, `ErrorResponse`
- `backend/app.py` — All 6 REST endpoints:
  - `GET /api/health` — health check
  - `GET /api/games` — distinct app_ids with mod counts via Cypher `MATCH (m:Mod) WHERE m.app_id IS NOT NULL RETURN DISTINCT m.app_id, count(m)`
  - `GET /api/mods/search?q=&game=` — prefix search via `toLower(m.title) STARTS WITH toLower($prefix)`, limit 20
  - `GET /api/mods/{workshop_id}` — full mod detail; HTTP 400 for invalid ID, HTTP 404 for missing
  - `GET /api/graph/{workshop_id}?depth=N` — BFS neighborhood, bounded to 200 nodes, deduplicated, `truncated` flag
  - `GET /api/graph/path?from=&to=` — shortest path via `shortestPath()` Cypher function

## Key Design Decisions

- Two-step graph query: first collect nodes (LIMIT 200), then collect edges between them
- `shortestPath` wrapped in `CALL {}` subquery for Neo4j 5 compatibility
- `toLower(STARTS WITH toLower())` for case-insensitive prefix search
- All endpoints raise `Neo4jError` → HTTP 500 via FastAPI exception handler

## Verification

- ✓ grep "api/mods/search" backend/app.py
- ✓ grep "api/games" backend/app.py
- ✓ grep "STARTS WITH" backend/app.py
- ✓ grep "api/mods/{workshop_id}" backend/app.py
- ✓ grep "404" backend/app.py
- ✓ grep "ModDetail" backend/app.py
- ✓ grep "api/graph/{workshop_id}" backend/app.py
- ✓ grep "REQUIRES" backend/app.py
- ✓ grep "shortestPath" backend/app.py
- ✓ grep "PathResult" backend/app.py
- ✓ grep "class ModSearchResult" backend/schemas.py
- ✓ grep "class GraphData" backend/schemas.py

## Issues Encountered

None.

## Commits

- `f447f27` — feat(phase-1): backend project scaffold and Neo4j HTTP client
- `e034a37` — feat(phase-1): add all REST API endpoints

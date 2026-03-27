---
phase: 01-backend-api
plan: 02
type: execute
wave: 2
depends_on: ["01-PROJECT-INIT"]
files_modified:
  - backend/app.py
  - backend/schemas.py
autonomous: true
requirements:
  - API-02
  - API-03
  - API-04
  - API-05
  - API-06
  - API-07
  - API-09
must_haves:
  truths:
    - "GET /api/games returns distinct app_ids from Neo4j as JSON array"
    - "GET /api/mods/search?q=&game= returns up to 20 mod results via prefix match"
    - "GET /api/mods/{workshop_id} returns full mod properties or HTTP 404"
    - "GET /api/graph/{workshop_id}?depth=N returns N-depth neighborhood bounded to 200 nodes"
    - "GET /api/graph/path?from=&to= returns shortest path via REQUIRES edges"
    - "All Neo4j failures return HTTP 500 with error message in JSON body"
  artifacts:
    - path: "backend/schemas.py"
      provides: "Pydantic models for Mod, ModSearchResult, GraphNode, GraphEdge, PathResult"
      min_lines: 30
    - path: "backend/app.py"
      provides: "All REST endpoints with correct Cypher queries and error handling"
      min_lines: 100
  key_links:
    - from: "backend/app.py"
      to: "backend/neo4j_client.py"
      via: "get_neo4j() async dependency calling run_query()"
    - from: "backend/app.py"
      to: "backend/schemas.py"
      via: "Pydantic response_model parameters"
    - from: "backend/app.py"
      to: "Neo4j"
      via: "Cypher queries: MATCH (m:Mod), graph neighborhood, shortestPath"
---

<objective>
Implement all 5 REST API endpoints (health already exists in Plan 01). Each endpoint queries Neo4j via the httpx HTTP client, returns typed JSON responses, and properly handles 404/500 errors. Cypher queries follow existing Neo4j schema (Mod nodes with workshop_id property, REQUIRES edges).
</objective>

<context>
@src/steam_workshop/neo4j.clj              # Reference: Cypher query patterns, data shape (node-row, edge-row)
@.planning/phases/01-backend-api/01-PROJECT-INIT.PLAN.md  # Project structure, neo4j_client API
</context>

<interfaces>
<!-- Key types and contracts the executor needs. Extracted from codebase. -->

Neo4j HTTP TX endpoint response format (from existing neo4j.clj):
```json
{
  "results": [{"columns": ["col1", "col2"], "data": [{"row": [val1, val2]}]}],
  "errors": []
}
```

Existing Neo4j node properties on :Mod (from neo4j.clj node-row):
```
id, workshop_id, title, author, author_id, author_profile_url,
canonical_url, preview_url, posted, updated, file_size,
description, obsolete, imported_at, source, app_id
```

Existing Neo4j edge type:
```
(m:Mod)-[:REQUIRES]->(n:Mod)
```

Backend neo4j_client.run_query() returns: `{"columns": [...list], "data": [...{"row": [...]}]}`

Plan 01 neo4j_client module interface:
```python
# In backend/neo4j_client.py:
settings = Settings()           # reads NEO4J_URI, NEO4J_AUTH, NEO4J_TX_URL
client = neo4j_client          # module with run_query(statement, parameters)
# Raises Neo4jError on Neo4j failure
```
</interfaces>

<tasks>

<task type="auto">
  <name>Task 1: Define Pydantic response schemas</name>
  <files>backend/schemas.py</files>
  <read_first>src/steam_workshop/neo4j.clj</read_first>
  <action>
    Create `backend/schemas.py` with Pydantic models.

    Define these models (all fields optional except where noted):
    - `ModSearchResult`: `workshop_id` (str), `title` (str), `app_id` (str | None), `obsolete` (bool) — used in search results
    - `ModDetail`: all fields from Mod node — `id`, `workshop_id`, `title`, `author`, `author_id`, `author_profile_url`, `canonical_url`, `preview_url`, `posted`, `updated`, `file_size`, `description`, `obsolete`, `imported_at`, `app_id`, `source` — all str | None
    - `GameInfo`: `app_id` (str), `mod_count` (int) — for /api/games response items
    - `GraphNode`: `workshop_id` (str), `title` (str | None), `obsolete` (bool) — for Cytoscape rendering
    - `GraphEdge`: `from_workshop_id` (str), `to_workshop_id` (str), `edge_type` (str = "REQUIRES")
    - `GraphData`: `nodes` (list[GraphNode]), `edges` (list[GraphEdge]), `total_nodes` (int), `truncated` (bool)
    - `PathResult`: `path` (list[GraphNode]), `edges` (list[GraphEdge])
    - `HealthResponse`: `status` (str), `timestamp` (str)
    - `ErrorResponse`: `detail` (str)

    Use `from typing import Optional, Any` and `from pydantic import BaseModel`.
  </action>
  <verify>
    <automated>grep -q "class ModSearchResult" backend/schemas.py && grep -q "class GraphData" backend/schemas.py && grep -q "class PathResult" backend/schemas.py && echo "OK"</automated>
  </verify>
  <done>schemas.py contains all required Pydantic models with correct field names matching Neo4j properties</done>
</task>

<task type="auto">
  <name>Task 2: Implement /api/games and /api/mods/search endpoints</name>
  <files>backend/app.py</files>
  <read_first>src/steam_workshop/neo4j.clj, backend/schemas.py</read_first>
  <action>
    Add to `backend/app.py` after the existing health endpoint.

    ## GET /api/games (API-03)
    - Path: `GET /api/games`
    - Cypher: `MATCH (m:Mod) WHERE m.app_id IS NOT NULL RETURN DISTINCT m.app_id AS app_id, count(m) AS mod_count ORDER BY mod_count DESC`
    - Response model: `list[GameInfo]`
    - On Neo4jError: raise HTTPException(500, detail=str(e.errors))

    ## GET /api/mods/search (API-04)
    - Path: `GET /api/mods/search`
    - Query params: `q` (str, required), `game` (str, optional)
    - Cypher (with parameters):
      ```
      MATCH (m:Mod)
      WHERE m.title STARTS WITH $prefix
        AND ($game IS NULL OR m.app_id = $game)
      RETURN m.workshop_id AS workshop_id, m.title AS title, m.app_id AS app_id, m.obsolete AS obsolete
      ORDER BY m.title
      LIMIT 20
      ```
      Note: Use `STARTS WITH` (case-insensitive via `toLower(m.title) STARTS WITH toLower($prefix))`) for prefix match per API-04 spec.
    - Response model: `list[ModSearchResult]`
    - If no results: return empty list (not 404 — valid search with zero hits)
    - On Neo4jError: raise HTTPException(500, detail=str(e.errors))
  </action>
  <verify>
    <automated>grep -q "api/mods/search" backend/app.py && grep -q "api/games" backend/app.py && grep -q "STARTS WITH" backend/app.py && echo "OK"</automated>
  </verify>
  <done>/api/games returns distinct app_ids with mod counts; /api/mods/search accepts q and optional game, returns up to 20 prefix-matched results</done>
</task>

<task type="auto">
  <name>Task 3: Implement /api/mods/{workshop_id} endpoint</name>
  <files>backend/app.py</files>
  <read_first>backend/schemas.py</read_first>
  <action>
    Add to `backend/app.py`.

    ## GET /api/mods/{workshop_id} (API-05, API-09)
    - Path: `GET /api/mods/{workshop_id}`
    - Path param: `workshop_id` (str) — numeric string from Steam
    - Validate: if not digits-only, raise HTTPException(400, "Invalid workshop_id")
    - Cypher:
      ```
      MATCH (m:Mod {workshop_id: $workshop_id})
      RETURN m.id AS id, m.workshop_id AS workshop_id, m.title AS title,
             m.author AS author, m.author_id AS author_id,
             m.author_profile_url AS author_profile_url,
             m.canonical_url AS canonical_url, m.preview_url AS preview_url,
             m.posted AS posted, m.updated AS updated,
             m.file_size AS file_size, m.description AS description,
             m.obsolete AS obsolete, m.imported_at AS imported_at,
             m.app_id AS app_id, m.source AS source
      ```
    - If result is empty: raise HTTPException(404, f"Mod not found: {workshop_id}") — per API-09
    - Response model: `ModDetail`
    - On Neo4jError: raise HTTPException(500, detail=str(e.errors))
  </action>
  <verify>
    <automated>grep -q "api/mods/{workshop_id}" backend/app.py && grep -q "404" backend/app.py && grep -q "ModDetail" backend/app.py && echo "OK"</automated>
  </verify>
  <done>GET /api/mods/{workshop_id} returns full mod properties or HTTP 404 for unknown mod IDs</done>
</task>

<task type="auto">
  <name>Task 4: Implement /api/graph/{workshop_id} endpoint (BFS neighborhood)</name>
  <files>backend/app.py</files>
  <read_first>src/steam_workshop/neo4j.clj</read_first>
  <action>
    Add to `backend/app.py`.

    ## GET /api/graph/{workshop_id} (API-06, API-09)
    - Path: `GET /api/graph/{workshop_id}`
    - Query params: `depth` (int, default=2, range 1-3) — per UI-07 spec (depth slider 1-3)
    - Cypher — BFS neighborhood with bounded nodes and deduplication:
      ```
      MATCH (start:Mod {workshop_id: $workshop_id})
      CALL {{
        WITH start
        MATCH path = (start)-[:REQUIRES*0..$depth]-(neighbor:Mod)
        WITH neighbor, min(length(path)) AS pathLength
        ORDER BY pathLength
        LIMIT 200
        RETURN neighbor
      }}
      WITH collect(DISTINCT neighbor) AS nodes
      UNWIND nodes AS m
      OPTIONAL MATCH (m)-[r:REQUIRES]-(other:Mod)
      WHERE other IN nodes
      WITH m, collect(DISTINCT {{from: m.workshop_id, to: other.workshop_id}}) AS raw_edges
      UNWIND raw_edges AS edge
      WITH m, edge
      WHERE edge.from IS NOT NULL AND edge.to IS NOT NULL
      WITH collect({{workshop_id: m.workshop_id, title: m.title, obsolete: m.obsolete}}) AS nodeSet,
           collect(DISTINCT {{from_workshop_id: edge.from, to_workshop_id: edge.to, edge_type: 'REQUIRES'}}) AS edgeSet
      RETURN nodeSet, edgeSet
      ```
      If the above is too complex for Neo4j 5 compat, use a simpler approach:
      1. `MATCH (start:Mod {workshop_id: $workshop_id})`
      2. `MATCH path = (start)-[:REQUIRES*0..$depth]-(neighbor:Mod)`
      3. `WITH collect(DISTINCT neighbor) AS nodes LIMIT 200`
      4. `UNWIND nodes AS m ... MATCH (m)-[r:REQUIRES]-(other) WHERE other IN nodes`
      5. Return nodes and deduplicated edges.
    - Build `GraphData` response: `nodes` list, `edges` list, `total_nodes` count, `truncated = (total_nodes == 200)`
    - If starting mod not found: HTTPException(404, f"Mod not found: {workshop_id}")
    - On Neo4jError: HTTPException(500, detail=str(e.errors))
  </action>
  <verify>
    <automated>grep -q "api/graph/{workshop_id}" backend/app.py && grep -q "REQUIRES" backend/app.py && grep -q "truncated" backend/app.py && echo "OK"</automated>
  </verify>
  <done>GET /api/graph/{workshop_id}?depth=N returns graph data bounded to 200 nodes max with truncated flag</done>
</task>

<task type="auto">
  <name>Task 5: Implement /api/graph/path endpoint (shortest path)</name>
  <files>backend/app.py</files>
  <action>
    Add to `backend/app.py`.

    ## GET /api/graph/path (API-07, API-09)
    - Path: `GET /api/graph/path`
    - Query params: `from` (str, required, workshop_id), `to` (str, required, workshop_id)
    - Cypher — shortest path via REQUIRES edges:
      ```
      MATCH (a:Mod {workshop_id: $from}), (b:Mod {workshop_id: $to})
      CALL {{
        WITH a, b
        MATCH path = shortestPath((a)-[:REQUIRES*1..20]-(b))
        RETURN path
      }}
      ```
      Or if shortestPath is not available:
      ```
      MATCH (a:Mod {workshop_id: $from}), (b:Mod {workshop_id: $to})
      MATCH path = (a)-[:REQUIRES*1..20]-(b)
      WITH path ORDER BY length(path) ASC LIMIT 1
      RETURN path
      ```
    - Extract nodes and edges from path:
      - Nodes: `[{"workshop_id": n.workshop_id, "title": n.title, "obsolete": n.obsolete} for n in path.nodes]`
      - Edges: `[{"from_workshop_id": r.start_node.workshop_id, "to_workshop_id": r.end_node.workshop_id, "edge_type": "REQUIRES"} for r in path.relationships]`
    - If `a` or `b` not found: HTTPException(404, f"Mod not found: {from}") or 404 for `to`
    - If no path found: return `{"path": [], "edges": []}` (not an error — valid result for disconnected mods)
    - On Neo4jError: HTTPException(500, detail=str(e.errors))
    - Response model: `PathResult`
  </action>
  <verify>
    <automated>grep -q "api/graph/path" backend/app.py && grep -q "shortestPath" backend/app.py && grep -q "PathResult" backend/app.py && echo "OK"</automated>
  </verify>
  <done>GET /api/graph/path?from=&to= returns shortest path nodes and edges or empty path if disconnected</done>
</task>

</tasks>

<verification>
- `curl http://localhost:8000/api/health` returns 200
- `curl http://localhost:8000/api/games` returns JSON array
- `curl "http://localhost:8000/api/mods/search?q=Brittany"` returns up to 20 results
- `curl "http://localhost:8000/api/mods/3689745069"` returns mod detail or 404
- `curl "http://localhost:8000/api/graph/3689745069?depth=2"` returns graph data
- `curl "http://localhost:8000/api/graph/path?from=3689745069&to=3688270372"` returns path
- Unknown mod ID returns HTTP 404
- Simulated Neo4j error returns HTTP 500 with detail
</verification>

<success_criteria>
All 5 new endpoints (plus health from Plan 01) respond correctly with valid JSON. HTTP 404 for unknown workshop_ids. HTTP 500 with error message for Neo4j failures. Response shapes match Pydantic schemas. All Cypher queries use read-only operations (MATCH only, no CREATE/MERGE/SET).
</success_criteria>

<output>
After completion, create `.planning/phases/01-backend-api/02-API-ENDPOINTS-SUMMARY.md`
</output>

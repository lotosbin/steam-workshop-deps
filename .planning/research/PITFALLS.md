# Pitfalls Research

**Domain:** Interactive graph visualization web app — Cytoscape.js + Neo4j + Docker
**Confidence:** HIGH

## Critical Pitfalls

### 1. Fetching the entire graph at once
**Warning:** Neo4j can return thousands of nodes for a connected mod community — rendering all at once freezes the browser.

**Prevention:** Always lazy-load with bounded depth (default depth=2). Never expose an "export entire graph" button without pagination.

**Phase:** Phase 1 (lazy-load neighborhood query)

---

### 2. Neo4j query performance — deep BFS
**Warning:** BFS `MATCH (m)-[:REQUIRES*0..N]-(d)` grows exponentially. Depth=3 on a popular mod can return 1000+ nodes.

**Prevention:** Set `maxNodes=200` as a hard limit on the backend. Return partial results with a "truncated" flag if limit exceeded.

**Phase:** Phase 1 (API design)

---

### 3. Neo4j credentials in frontend
**Warning:** Any credential embedded in frontend JS is public. If backend is the only component that knows the Neo4j password, a read-only user is not sufficient protection.

**Prevention:** Backend holds credentials. Frontend only calls backend API. Use read-only Neo4j user in production.

**Phase:** Phase 1 (backend API design)

---

### 4. Cytoscape layout instability
**Warning:** Force-directed layouts (cose, cola) can produce different results on each render, causing the graph to "jump" when data refreshes.

**Prevention:** Use a stable layout (dagre for hierarchical) or persist node positions in localStorage. If using force-directed, run layout once and freeze positions.

**Phase:** Phase 2 (layout implementation)

---

### 5. Missing node deduplication in BFS
**Warning:** BFS from a mod's dependencies AND dependents can return the same mod twice via different paths, creating duplicate nodes in the graph.

**Prevention:** Backend deduplicates by `workshop_id` before returning. Use Cypher `WITH collect(DISTINCT node) AS nodes`.

**Phase:** Phase 1 (API query design)

---

### 6. Steam workshop ID vs internal ID confusion
**Warning:** Existing Neo4j schema has both `id` (internal) and `workshop_id` (Steam numeric ID). External links need `workshop_id`. Queries need to handle both.

**Prevention:** API always returns `workshop_id` in response. Use `workshop_id` for external URLs (`steamcommunity.com/sharedfiles/filedetails/?id={workshop_id}`).

**Phase:** Phase 1 (API response shape)

---

### 7. Docker image size bloat
**Warning:** Installing Node.js + Python + nginx in one Dockerfile can produce a 1GB+ image.

**Prevention:** Use multi-stage build. Build stage: Node + Python → Frontend build. Runtime stage: Alpine nginx. Or use pre-built frontend served by FastAPI's static file middleware in dev.

**Phase:** Phase 4 (Docker deployment)

---

### 8. CORS misconfiguration
**Warning:** Backend needs CORS headers to allow frontend (if served on different port in dev).

**Prevention:** Configure `CORSMiddleware` in FastAPI with specific origin in production.

**Phase:** Phase 1 (backend setup)

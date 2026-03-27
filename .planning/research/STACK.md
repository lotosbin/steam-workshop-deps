# Stack Research

**Domain:** Interactive graph visualization web app — Cytoscape.js + Neo4j + Docker
**Confidence:** HIGH

## Recommended Stack

### Frontend

| Layer | Choice | Version | Rationale |
|-------|--------|---------|-----------|
| Graph library | Cytoscape.js | 3.x (latest) | Built-in layouts, algorithms, interaction handlers. Best graph lib for this use case. |
| Bundler | Vite | 5.x | Fast HMR, minimal config, modern ESM |
| Language | Vanilla JS or TypeScript | — | No SPA framework needed for this scope; keeps bundle small |
| Styling | Plain CSS with CSS custom properties | — | Zomboid dark theme needs full control; no utility-class overhead |
| HTTP client | Fetch API (native) | — | No extra dependency |

**Avoid:** D3.js (steeper learning, same outcome), React/Vue (overkill for a single-page tool), Sigma.js (better for 10k+ nodes, not needed here).

### Backend

| Layer | Choice | Version | Rationale |
|-------|--------|---------|-----------|
| Language | Python (Flask or FastAPI) | 3.12+ | Matches existing project language, easy to integrate with Neo4j driver |
| Neo4j driver | `neo4j` official Python driver | 5.x | Official, well-maintained, supports Bolt protocol |
| HTTP server | FastAPI + Uvicorn | — | Async, fast, auto OpenAPI docs |
| CORS | FastAPI CORSMiddleware | — | Allow frontend on different port in dev |

**Alternative:** Node.js (Express) — equally valid, Python chosen for consistency with existing codebase.

### Infrastructure

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Container | Docker, single image | Frontend (static files) + Backend in one container; `nginx` serves frontend + proxies `/api/*` to FastAPI |
| Nginx | Alpine-based | Lightweight, serves static files + reverse proxy |
| Env vars | Via `docker run -e` or `.env` file | Neo4j URI, credentials, port config |

## Version Constraints

- **Node.js**: 20+ (for Vite compatibility)
- **Python**: 3.12+ (for async driver)
- **Neo4j**: 4.x or 5.x (Bolt protocol compatibility)
- **Cytoscape.js**: 3.28+ (latest stable)

## What NOT to Use

- **Neo4j HTTP API directly from frontend** — exposes credentials; use backend proxy
- **Gephi / external graph tool** — desktop-only, not web-deployable
- **Playwright in browser** — not for graph visualization
- **GraphQL** — unnecessary complexity; REST covers all query patterns

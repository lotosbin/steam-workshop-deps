# Roadmap — Steam Workshop Graph Explorer v1.0

**Granularity:** coarse (4 phases)
**Coverage:** 29/29 v1 requirements mapped

---

## Phases

- [x] **Phase 1: Backend API** — FastAPI proxy over Neo4j, all REST endpoints, read-only security
- [ ] **Phase 2: Core UI** — Cytoscape graph, search, detail panel, depth control
- [ ] **Phase 3: Polish & Theme** — Zomboid dark industrial aesthetic, CSS custom properties, URL state
- [ ] **Phase 4: Deployment** — Docker image, nginx config, README

---

## Phase Details

### Phase 1: Backend API

**Goal:** Backend exposes a secure read-only REST API that proxies Neo4j queries.

**Depends on:** Nothing

**Requirements:** API-01, API-02, API-03, API-04, API-05, API-06, API-07, API-08, API-09

**Success Criteria** (what must be TRUE):

1. User can call `GET /api/health` and receive a JSON response with service status
2. User can call `GET /api/games` and receive all distinct game appIds from Neo4j
3. User can call `GET /api/mods/search?q=&game=` and receive up to 20 mod results with workshop_id, title, app_id, obsolete fields via prefix match
4. User can call `GET /api/mods/{workshop_id}` and receive the full mod properties from Neo4j
5. User can call `GET /api/graph/{workshop_id}?depth=N` and receive the mod plus its N-depth neighborhood, deduplicated and bounded to 200 nodes max
6. User can call `GET /api/graph/path?from=&to=` and receive the shortest path between two mods via REQUIRES edges
7. All endpoints fail with HTTP 404 for unknown mod IDs and HTTP 500 with error message for Neo4j failures
8. All Neo4j connections use read-only credentials from environment variables only

**Plans:** 2 plans

Plans:
- [x] 01-PROJECT-INIT-PLAN.md — Project scaffold: FastAPI, httpx, Neo4j client, CORS, health endpoint
- [x] 02-API-ENDPOINTS-PLAN.md — All 5 REST endpoints: /api/games, /api/mods/search, /api/mods/{id}, /api/graph/{id}, /api/graph/path

---

### Phase 2: Core UI

**Goal:** Users can search for mods, visualize their dependency graph, and inspect mod details.

**Depends on:** Phase 1

**Requirements:** UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-07, UI-08, UI-09, UI-10

**Success Criteria** (what must be TRUE):

1. User can load a single-page application served as static files with no SPA routing
2. User can select a game from a dropdown populated by `/api/games`
3. User can type a search query and see up to 20 clickable mod results from `/api/mods/search`
4. User can click a search result and see the mod's dependency graph rendered via `/api/graph/{id}?depth=2` with up to 200 nodes
5. User can see a Cytoscape.js canvas rendering mod nodes and directed REQUIRES edges with arrow indicators
6. User can click any node to open a detail panel showing title, author, stats, workshop link, and an obsolete warning if applicable
7. User can adjust a depth slider (range 1-3) to re-fetch and re-render the graph at a different neighborhood depth
8. User can see at least one stable hierarchical layout (dagre) that does not reflow unexpectedly

**Plans:** TBD

**UI hint:** yes

---

### Phase 3: Polish & Theme

**Goal:** The application matches Project Zomboid's gritty industrial aesthetic and supports shareable URLs.

**Depends on:** Phase 2

**Requirements:** UI-11, UI-12, THEME-01, THEME-02, THEME-03, THEME-04

**Success Criteria** (what must be TRUE):

1. User pressing Escape closes the open detail panel
2. User can share a URL that encodes the selected game, search query, and selected mod; loading that URL restores the exact UI state
3. User sees a dark industrial visual theme that matches Project Zomboid's aesthetic
4. User sees low-saturation grays and browns throughout the interface with no bright accent colors
5. User sees gritty typography and utilitarian UI elements reinforcing the post-apocalyptic theme
6. User can re-theme the application by overriding CSS custom properties

**Plans:** TBD

**UI hint:** yes

---

### Phase 4: Deployment

**Goal:** The application ships as a single Docker container that anyone can run with a Neo4j instance.

**Depends on:** Phase 1, Phase 2

**Requirements:** DEPLOY-01, DEPLOY-02, DEPLOY-03, DEPLOY-04

**Success Criteria** (what must be TRUE):

1. User can run `docker build` to produce a single image containing both frontend and backend
2. User can run the container with `docker run` and provide Neo4j connection details via environment variables
3. The containerized application listens on port 80 (HTTP) and serves the frontend and API from the same host
4. User can read `README.md` and find Docker run instructions and the complete environment variables reference

**Plans:** TBD

---

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Backend API | 2/2 | Complete | 2026-03-27 |
| 2. Core UI | 0/N | Not started | - |
| 3. Polish & Theme | 0/N | Not started | - |
| 4. Deployment | 0/N | Not started | - |

---

*Last updated: 2026-03-27*

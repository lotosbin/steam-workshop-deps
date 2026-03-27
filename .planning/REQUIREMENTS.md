# Requirements

**Project:** Steam Workshop Graph Explorer
**Phase:** v1.0

---

## v1 Requirements

### API

- [ ] **API-01**: Backend exposes REST API at `/api/*` with JSON responses
- [ ] **API-02**: `GET /api/health` returns service status
- [ ] **API-03**: `GET /api/games` returns all distinct game appIds from Neo4j
- [ ] **API-04**: `GET /api/mods/search?q=&game=` searches mods by title (prefix match), returns top 20 results with `workshop_id`, `title`, `app_id`, `obsolete`
- [ ] **API-05**: `GET /api/mods/{workshop_id}` returns full mod properties from Neo4j
- [ ] **API-06**: `GET /api/graph/{workshop_id}?depth=N` returns mod + N-depth neighborhood (bounded to 200 nodes max), deduplicated
- [ ] **API-07**: `GET /api/graph/path?from=&to=` returns shortest path between two mods via `:REQUIRES` edges
- [ ] **API-08**: All endpoints use read-only Neo4j user; credentials from environment variables only
- [ ] **API-09**: Backend returns HTTP 404 for unknown mod IDs; 500 with error message for Neo4j failures

### Frontend

- [ ] **UI-01**: Single-page application served as static files (no SPA routing)
- [ ] **UI-02**: Game selector dropdown populated from `/api/games`
- [ ] **UI-03**: Search input that calls `/api/mods/search`, shows results as clickable list
- [ ] **UI-04**: Clicking a search result loads the mod's graph via `/api/graph/{id}?depth=2`
- [ ] **UI-05**: Cytoscape.js renders nodes (mods) and directed edges (:REQUIRES)
- [ ] **UI-06**: Clicking a node shows detail panel with title, author, stats, workshop link, obsolete warning
- [ ] **UI-07**: Depth control slider (1–3) to adjust neighborhood fetch depth
- [ ] **UI-08**: At least one stable layout (dagre for hierarchical)
- [ ] **UI-09**: Visual indicator for obsolete mods (distinct node color/style)
- [ ] **UI-10**: Node shows mod title as label; edge arrows show dependency direction
- [ ] **UI-11**: Keyboard navigation: Escape closes detail panel
- [ ] **UI-12**: URL reflects current state (selected game, search query, selected mod) for shareability

### Visual Theme

- [ ] **THEME-01**: Dark industrial aesthetic matching Project Zomboid's style
- [ ] **THEME-02**: Low-saturation grays and browns, no bright accent colors
- [ ] **THEME-03**: Gritty typography, utilitarian UI elements
- [ ] **THEME-04**: CSS custom properties for all colors to enable easy theming

### Deployment

- [ ] **DEPLOY-01**: Dockerfile builds single image containing frontend + backend
- [ ] **DEPLOY-02**: `docker run` starts the app with configurable Neo4j connection via environment variables
- [ ] **DEPLOY-03**: App listens on port 80 (http) inside container
- [ ] **DEPLOY-04**: `README.md` includes Docker run instructions and environment variables reference

---

## v2 Requirements (Deferred)

- Multiple Cytoscape layouts (force-directed, tree) with user toggle
- Mod name filters (by author, by subscriber count threshold)
- Keyboard shortcuts for graph navigation
- Expand-in-place: double-click node to load its dependencies without replacing the graph
- Shortest path between two arbitrary nodes (select two nodes)
- Author pages: click author name to see all their mods
- Breadcrumb trail showing navigation history
- Graph summary stats (node count, edge count, cycle warning)
- Workshop thumbnail previews on node hover
- Export graph as PNG

---

## Out of Scope

- **Write access to Neo4j from web UI** — read-only only; data ingestion stays in existing Babashka pipeline
- **User authentication / API keys** — fully public access
- **Mod download or installation** — read-only exploration only
- **Mobile-optimized UI** — desktop-first; responsive layout only
- **Full-text description search** — mod title search only for v1
- **Real-time WebSocket sync** — polling / on-demand fetching only
- **Built-in Steam API integration** — relies on existing Babashka importer for data
- **Graph editing or annotation** — read-only visualization only

---

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| API-01 | Phase 1 | Done |
| API-02 | Phase 1 | Done |
| API-03 | Phase 1 | Done |
| API-04 | Phase 1 | Done |
| API-05 | Phase 1 | Done |
| API-06 | Phase 1 | Done |
| API-07 | Phase 1 | Done |
| API-08 | Phase 1 | Done |
| API-09 | Phase 1 | Done |
| UI-01 | Phase 2 | Pending |
| UI-02 | Phase 2 | Pending |
| UI-03 | Phase 2 | Pending |
| UI-04 | Phase 2 | Pending |
| UI-05 | Phase 2 | Pending |
| UI-06 | Phase 2 | Pending |
| UI-07 | Phase 2 | Pending |
| UI-08 | Phase 2 | Pending |
| UI-09 | Phase 2 | Pending |
| UI-10 | Phase 2 | Pending |
| UI-11 | Phase 3 | Pending |
| UI-12 | Phase 3 | Pending |
| THEME-01 | Phase 3 | Pending |
| THEME-02 | Phase 3 | Pending |
| THEME-03 | Phase 3 | Pending |
| THEME-04 | Phase 3 | Pending |
| DEPLOY-01 | Phase 4 | Pending |
| DEPLOY-02 | Phase 4 | Pending |
| DEPLOY-03 | Phase 4 | Pending |
| DEPLOY-04 | Phase 4 | Pending |

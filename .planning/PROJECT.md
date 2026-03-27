# Steam Workshop Graph Explorer

## What This Is

An interactive web application for exploring Steam Workshop mod dependency graphs. Users browse, search, and visualize mod relationships across any Steam Workshop game in real-time via Neo4j graph queries. Built as a deployable Docker container for easy self-hosting.

## Core Value

Users can find any mod's dependencies and dependents in seconds, with a visual graph that makes complex mod relationships immediately clear.

## Requirements

### Validated

- ✓ Local mod dependency analysis (tree, reverse, cycle detection) — existing Python CLI
- ✓ Steam Workshop mod info parsing — existing `mod.info` / `workshop.txt` parser
- ✓ Neo4j graph storage — existing import pipeline for mods, authors, collections
- ✓ Steam Workshop page scraping — existing Playwright CLI pipeline
- ✓ **Real-time Neo4j Queries** — Backend FastAPI proxy, all REST endpoints live (Phase 1)
- ✓ **Game/Mod Search API** — `/api/games` and `/api/mods/search` (Phase 1)
- ✓ **Mod Detail API** — `/api/mods/{workshop_id}` with 404/500 handling (Phase 1)
- ✓ **Graph Neighborhood API** — `/api/graph/{id}?depth=N` BFS, bounded 200 nodes (Phase 1)
- ✓ **Shortest Path API** — `/api/graph/path?from=&to=` via Cypher shortestPath (Phase 1)

### Active

- [ ] **Graph Visualization UI** — Interactive Cytoscape.js frontend (Phase 2)
- [ ] **Mod Detail Panel UI** — Click node to see mod details in panel (Phase 2)
- [ ] **Docker Deployment** — Single container serving frontend + backend (Phase 4)
- [ ] **Zomboid Visual Theme** — Dark industrial aesthetic (Phase 3)

### Out of Scope

- Write access to Neo4j from the web UI — read-only only
- User authentication / API keys — fully public access
- Mod installation or download — read-only exploration only
- Mobile-optimized UI — desktop-first
- Multiple simultaneous queries queuing — single-user serverless model
- Built-in Steam API integration — relies on existing Babashka import pipeline for data ingestion

## Context

**Existing System:** This project extends the existing `steam-workshop-deps` tool which has a Python CLI for local analysis and a Clojure/Babashka pipeline for importing Steam Workshop data into Neo4j. The `web/` directory contains an MVP static page with demo data only.

**Data Flow:**
```
Steam Workshop → Babashka Importer → Neo4j → New Backend API → Cytoscape.js UI
```

**Domain:** Steam Workshop mods for any game (initially Project Zomboid), with dependency relationships stored as `(Mod)-[:REQUIRES]->(Mod)` edges in Neo4j.

**Users:** Modders, server admins, and players who want to understand mod compatibility before installing.

## Constraints

- **Tech Stack**: Cytoscape.js frontend + lightweight backend (Node.js or Python) + Neo4j — no React/Vue SPA complexity
- **Deployment**: Docker single container, simple `docker run` — no Kubernetes
- **Security**: Neo4j credentials via environment variable, read-only Neo4j user only
- **Performance**: Frontend fetches on-demand; no pre-loading entire graph (lazy load neighborhood)
- **Browser**: Modern browsers only (ES2020+), no IE11

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Cytoscape.js over D3.js | Built-in graph algorithms (shortest path, layouts), easier interaction API | — Pending |
| Docker single container | Simplest deployment for self-hosters; Railway/Render compatible | — Pending |
| Backend API between frontend and Neo4j | Hides Neo4j credentials; enables caching and query shaping | — Pending |
| Read-only Neo4j user | Public deployment safety; data ingestion stays in existing Babashka pipeline | — Pending |
| Zomboid industrial dark theme | Ties to the game's aesthetic; low-saturation grays and browns | — Pending |
| All Steam Workshop games | Generic framework from day one, not just Project Zomboid | — Pending |

---

*Last updated: 2026-03-27 after Phase 1 completion*

---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: unknown
last_updated: "2026-03-27T14:42:25.130Z"
progress:
  total_phases: 4
  completed_phases: 0
  total_plans: 0
  completed_plans: 2
---

# State — Steam Workshop Graph Explorer

**Project:** Steam Workshop Graph Explorer
**Core Value:** Users can find any mod's dependencies and dependents in seconds, with a visual graph that makes complex mod relationships immediately clear.
**Current Focus:** Phase 01 — backend-api

---

## Current Position

Phase: 2
Plan: Not started
| Field | Value |
|-------|-------|
| **Milestone** | v1.0 |
| **Phase** | Not started |
| **Plan** | None |
| **Status** | Not started |
| **Progress** | 0% |

---

## Performance Metrics

| Metric | Value |
|--------|-------|
| Requirements mapped | 0/29 |
| Plans complete | 0 |
| Phases complete | 0/4 |

---

## Accumulated Context

### Key Decisions

- **Cytoscape.js over D3.js:** Built-in graph algorithms (shortest path, layouts), easier interaction API
- **Docker single container:** Simplest deployment for self-hosters; Railway/Render compatible
- **Backend API between frontend and Neo4j:** Hides Neo4j credentials; enables caching and query shaping
- **Read-only Neo4j user:** Public deployment safety; data ingestion stays in existing Babashka pipeline
- **Zomboid industrial dark theme:** Ties to the game's aesthetic; low-saturation grays and browns
- **All Steam Workshop games:** Generic framework from day one, not just Project Zomboid

### Data Flow

```
Steam Workshop → Babashka Importer → Neo4j → FastAPI Backend → Cytoscape.js UI
```

### Domain

Steam Workshop mods for any game (initially Project Zomboid), with dependency relationships stored as `(Mod)-[:REQUIRES]->(Mod)` edges in Neo4j.

### Tech Stack (from research)

- **Frontend:** Cytoscape.js + Vite
- **Backend:** FastAPI + neo4j Python driver
- **Infra:** Docker + nginx

### Known Pitfalls (from research)

- Fetching entire graph at once (browser freeze) — mitigated by BFS bounded to 200 nodes
- Deep BFS query performance (exponential growth) — mitigated by depth slider limiting to 1-3
- Neo4j credentials in frontend — always proxy through backend
- Cytoscape layout instability — freeze positions or use dagre for hierarchical layout
- Node deduplication in BFS queries — backend deduplicates before returning

### Out of Scope

- Write access to Neo4j from web UI
- User authentication / API keys
- Mod download or installation
- Mobile-optimized UI
- Real-time WebSocket sync

### Blocker / Awaiting

Nothing — roadmap approved, ready to plan Phase 1.

---

## Session Continuity

| Session | Last Phase | Last Plan | Notes |
|---------|------------|-----------|-------|
| — | — | — | Project initialized 2026-03-27 |

---

*Last updated: 2026-03-27*

# Research Summary

**Synthesized from:** STACK.md · FEATURES.md · ARCHITECTURE.md · PITFALLS.md

---

## Key Findings

**Stack:** Cytoscape.js + Vite (frontend) / FastAPI + neo4j Python driver (backend) / Docker + nginx (infra)

**Table Stakes:** Interactive graph canvas, lazy-load neighborhood, mod search, depth control, live Neo4j data, game switching, detail panel, cycle detection

**Differentiators:** Shortest path between two nodes, expand-in-place, bookmarks/deep-links, author pages, impact analysis

**Watch Out For:**
- Fetching entire graph at once (browser freeze)
- Deep BFS query performance (exponential growth)
- Neo4j credentials in frontend (always proxy through backend)
- Cytoscape layout instability (freeze positions or use dagre)
- Node deduplication in BFS queries

---

## Phase Structure (from features research)

| Phase | Focus | Key Deliverables |
|-------|-------|-----------------|
| 1 | Backend + Core UI | FastAPI proxy, lazy-load graph, mod search, detail panel |
| 2 | Layouts + Polish | Multiple layouts, filters, obsolete warning, keyboard nav |
| 3 | Advanced Features | Shortest path, expand-in-place, bookmarks |
| 4 | Docker + Distribution | Multi-stage Dockerfile, nginx config, README |

---

## Confidence

- **Stack:** HIGH — well-established, proven combination
- **Features:** MEDIUM — no competitor data (WebSearch blocked)
- **Architecture:** HIGH — straightforward 3-tier pattern
- **Pitfalls:** HIGH — derived from known common mistakes

# Architecture Research

**Domain:** Interactive graph visualization web app — Cytoscape.js + Neo4j + Docker
**Confidence:** HIGH

## Component Boundaries

```
Browser (Cytoscape.js UI)
        ↓ HTTP/JSON
Backend API (FastAPI)          ← Credentials live here
        ↓ Bolt protocol
Neo4j Graph Database            ← Read-only user
```

## Components

### Frontend (Vanilla JS + Cytoscape.js)
- **Single HTML page** with embedded Cytoscape canvas
- **Search bar**: searches mods by title prefix/SIMILAR via backend
- **Graph canvas**: renders nodes + edges; handles pan/zoom/click
- **Detail panel**: slides in on node click, shows mod metadata
- **Control bar**: depth slider, layout switcher, filter toggles
- **No routing**: single-page, state via URL query params for shareability

### Backend API (FastAPI)
Endpoints:
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Health check |
| GET | `/api/games` | List all games (distinct appIds) |
| GET | `/api/mods/search?q=&game=` | Search mods by name |
| GET | `/api/mods/{id}` | Mod detail (properties + stats) |
| GET | `/api/graph/{id}?depth=N` | Fetch mod + N-depth neighborhood |
| GET | `/api/graph/path?from=&to=` | Shortest path between two mods |
| GET | `/api/authors/{id}` | Author info + their mods |

All endpoints: JSON response, read-only Neo4j queries only.

### Neo4j Schema (existing, unchanged)
- `:Mod` nodes — `workshop_id`, `title`, `author`, `app_id`, `obsolete`, etc.
- `:Game` nodes — `app_id`, `name`
- `:Author` nodes
- `(Mod)-[:REQUIRES]->(Mod)` edges
- `(Mod)-[:BELONGS_TO]->(Game)` edges
- `(Author)-[:AUTHORED]->(Mod)` edges

### Docker Container
```
FROM python:3.12-slim
  → install Node.js (for Vite build)
  → COPY frontend/  → run `npm install && npm run build`
  → COPY backend/   → install Python deps
  → install nginx
  → nginx config: serve /dist as static, proxy /api/* to :8000
  → CMD: start uvicorn + nginx
```

## Data Flow

1. User opens page → browser loads Cytoscape.js
2. User searches "Brittany" → frontend `fetch /api/mods/search?q=brittany`
3. Backend queries Neo4j: `MATCH (m:Mod) WHERE m.title CONTAINS 'brittany' RETURN m LIMIT 20`
4. User clicks a mod node → frontend `fetch /api/graph/{id}?depth=2`
5. Backend BFS query: `MATCH path = (m:Mod {id: $id})-[:REQUIRES*0..2]-(dep) RETURN path`
6. Cytoscape.js renders nodes + edges from JSON response
7. User clicks edge → detail panel shows relationship metadata

## Build Order (dependencies)

1. **Neo4j schema** — already exists from existing import pipeline
2. **Backend API** — must be done first; frontend depends on it
3. **Frontend shell** — static page that calls `/api/health`
4. **Graph rendering** — Cytoscape integration with mock data
5. **Live data wiring** — connect frontend to real backend endpoints
6. **Docker** — last, package everything together

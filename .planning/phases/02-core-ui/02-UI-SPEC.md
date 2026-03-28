# UI-SPEC — Phase 2: Core UI

**Project:** Steam Workshop Graph Explorer
**Phase:** 2 of 4
**Status:** approved
**Stack:** Vanilla JS + Cytoscape.js 3.29 + Vite (build only, no SPA routing)
**Backend:** FastAPI at `/api/*` (Phase 1 complete)

---

## Design Contract Summary

This phase establishes the **structural and interaction contract** for the single-page application.
Full Zomboid dark industrial theming (CSS custom properties, palette refinement) is deferred to Phase 3.
Phase 2 uses a working dark base so the UI is functional and legible without bright color distractions.

**Scope (Phase 2):** Game selector, mod search, Cytoscape graph render, detail panel, depth control.
**Out of scope:** URL state (Phase 3), theme refinement (Phase 3), keyboard nav (Phase 3).

---

## File Structure

```
web/
  index.html          # Single page shell; loads Vite bundle
  src/
    main.js           # App bootstrap; wires UI components
    state.js          # Single source of truth (game, search, graph, selected)
    api.js            # Fetch wrappers for all /api/* endpoints
    search.js         # Search dropdown UI + event wiring
    graph.js          # Cytoscape init, layout, node-click, render
    detail.js         # Detail panel open/close/populate
    depth.js          # Depth slider event wiring
  styles/
    base.css          # CSS custom properties (Phase 2 base; refined in Phase 3)
    layout.css        # Two-column grid: sidebar + canvas
    search.css        # Search input + results dropdown
    graph.css         # Cytoscape container
    detail.css        # Detail panel overlay
  vite.config.js     # Vite config: entry = src/main.js, outDir = dist
```

**Phase 2 builds on top of Phase 1's `backend/`.** No backend changes in this phase.

---

## Spacing

Scale: multiples of 4px only.

| Token   | Value | Usage                                      |
|---------|-------|--------------------------------------------|
| `--sp-1` | 4px   | Icon padding, tight gaps                   |
| `--sp-2` | 8px   | Between form elements, badge padding       |
| `--sp-3` | 12px  | Input padding horizontal                   |
| `--sp-4` | 16px  | Standard padding, section gaps              |
| `--sp-5` | 20px  | Detail panel internal padding               |
| `--sp-6` | 24px  | Panel padding, stat row gaps               |
| `--sp-8` | 32px  | Sidebar width (320px), major section gaps |

**No exceptions.** Touch targets for any future interactive elements must be at least 44px.

---

## Typography

3 sizes, 2 weights, body line-height 1.5, heading line-height 1.2.

| Role        | Size | Weight | Line-height | Usage                         |
|-------------|------|--------|-------------|-------------------------------|
| Heading H1  | 16px | 700    | 1.2         | Sidebar title, panel headers  |
| Body        | 14px | 400    | 1.5         | Labels, descriptions, stats    |
| Caption     | 12px | 400    | 1.4         | Badges, metadata, muted text   |

**Font stack** (from existing `web/index.html`): `"Avenir Next", "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`
This is confirmed as the existing project font choice. Do not change.

**Forbidden:** Size 13px, size 15px, weight 300, weight 500 — outside the declared system.

---

## Color — Phase 2 Base (refined in Phase 3)

Phase 2 uses a **functional dark base** with the CSS custom properties already present in `web/index.html`. Full Zomboid industrial palette refinement (low-saturation grays and browns, accent restrictions) is Phase 3 work. Phase 2 ensures the base is dark enough that Phase 3 overrides are purely cosmetic.

All Phase 2 colors declared as CSS custom properties:

| Property             | Value                    | Usage                            |
|---------------------|--------------------------|----------------------------------|
| `--bg`              | `#101714`                | Page background                  |
| `--panel`           | `rgba(247,242,231,0.08)` | Sidebar background               |
| `--panel2`          | `rgba(247,242,231,0.12)` | Input/select background          |
| `--text`            | `rgba(248,244,236,0.94)` | Primary text                    |
| `--muted`           | `rgba(248,244,236,0.68)` | Secondary text, labels          |
| `--border`          | `rgba(255,255,255,0.14)` | All borders                      |
| `--accent`          | `#7cdbff`                | Focus rings, mod node border     |
| `--accent-dim`      | `rgba(124,219,255,0.14)` | Mod node fill                    |
| `--danger`          | `#ff5c7a`                | Obsolete mod node border+fill   |
| `--danger-dim`      | `rgba(255,92,122,0.18)`  | Obsolete node background         |
| `--canvas-bg`       | `#0d1210`                | Cytoscape canvas background     |

**Phase 3 overrides (deferred):** `--bg` and `--accent` will be refined to Zomboid grays/browns per THEME-01/02.

**Color application rules:**
- `--accent` (blue) reserved for: focus rings, selected mod node border, active depth slider track
- `--danger` (red) reserved for: obsolete mod node border and fill
- No other colors may be added without Phase 3 scope expansion

---

## Cytoscape Visual Contract

### Node Styles

| Kind      | Shape          | Border color         | Fill color              |
|-----------|----------------|----------------------|-------------------------|
| Mod       | `round-rectangle` | `#7cdbff`            | `rgba(124,219,255,0.14)` |
| Obsolete  | `round-rectangle` | `#ff5c7a` (danger)   | `rgba(255,92,122,0.18)`  |
| Collection| `hexagon`      | `#ffc46b` (deferred Phase 3) | `rgba(255,196,107,0.18)` |
| Author    | `ellipse`      | `#7bf0b2` (deferred Phase 3) | `rgba(123,240,178,0.18)` |

**Node label:** mod title, font-size 11px, `rgba(255,255,255,0.92)`, `text-wrap: wrap`, max-width 150px, centered.

**Node size:** default (Cytoscape `node.width/height: undefined` — auto-sized by content).

**Obsolete warning:** `--danger` border + fill applied to mod nodes where `obsolete === true`.

### Edge Styles

| Edge type   | Line color             | Arrow  | Style     |
|-------------|------------------------|--------|-----------|
| `REQUIRES`  | `rgba(124,219,255,0.4)` | triangle | solid, width 2 |
| `CONTAINS`  | `rgba(255,196,107,0.55)` | triangle | dashed, width 1.5 |
| `AUTHORED`  | `rgba(123,240,178,0.55)` | triangle | solid, width 1.5 |
| `ASSEMBLED` | `rgba(255,152,152,0.6)` | triangle | dotted, width 1.5 |

### Layout

**Primary layout (Phase 2):** `dagre` — hierarchical, stable, directed.
Configuration:
```
{
  name: 'dagre',
  directed: true,
  padding: 18,
  animate: true,
  fit: true,
  spacingFactor: 1.15,
  nodeSep: 50,
  rankSep: 80
}
```

**Secondary layouts available via layout switcher (Phase 2):** `breadthfirst`, `cose`. Do not use `cose` as default (unstable).

**Canvas pan/zoom:** `wheelSensitivity: 0.15`. Pan by drag. Fit on initial render.

---

## Component Inventory

### Game Selector (`<select id="gameSelect">`)
- Populated on page load via `GET /api/games`
- Shows `app_id` as value, `app_id (N mods)` as label
- Default: first game in list
- On change: clears search results, clears graph

### Search Input (`<input id="searchInput" type="text">`)
- Placeholder: `"Search mods..."`
- Debounce: 300ms before firing `GET /api/mods/search?q=&game=`
- Results dropdown: max 20 items, each `<button>` with title + `(obsolete)` badge if applicable
- Empty query: hide dropdown
- Loading state: show `"Searching..."` in dropdown

### Search Results Dropdown
- Positioned below input, `width: 100%`, `max-height: 320px`, overflow scroll
- Each result: mod title (14px, regular weight) + obsolete badge if applicable
- Click result: calls `GET /api/graph/{workshop_id}?depth=N`, renders graph, clears dropdown

### Depth Slider (`<input type="range" min="1" max="3" step="1">`)
- Default value: 2
- Label shows current value: `"Depth: N"`
- On change: re-fetches graph at new depth, re-renders

### Graph Canvas (`<div id="cy">`)
- Takes remaining viewport width and height (fills `main.graph` container)
- Renders via Cytoscape 3.29 from CDN (`unpkg.com/cytoscape@3.29.2/dist/cytoscape.min.js`)
- On node click: opens detail panel for that node
- Node click does NOT re-fetch graph (view-only navigation)

### Detail Panel (overlay, not sidebar replacement)
- Slides in from right or top on mobile
- Triggered by node click
- Shows: title, author (link to profile URL), stats (posted, updated, subscribers via description), canonical URL (link to workshop page), obsolete warning banner
- Close button (`X`) in top-right corner with `aria-label="Close panel"`
- Phase 3 adds: Escape key to close (UI-11)

### Stats Bar (sidebar bottom)
- Displays: node count, edge count, mod count, cycle count
- Updates after each graph render

### Loading State
- During API fetch: disable search input, show spinner or `"Loading..."` text in graph canvas
- Use `cursor: wait` on body during fetch

### Error State
- API error: show error message inside graph canvas: `"Failed to load: {error.message}. Is the backend running?"`
- No mod results: show `"No mods found for '{query}'"` in dropdown area

---

## API Integration

All API calls via `fetch` (native Fetch API). Base URL: `/api` (same origin, proxied by Vite dev server and nginx in prod).

### Endpoints Used in Phase 2

| Method | Path                              | When Called                    |
|--------|-----------------------------------|--------------------------------|
| GET    | `/api/games`                      | On page load                  |
| GET    | `/api/mods/search?q=&game=`       | On search input (debounced 300ms) |
| GET    | `/api/graph/{id}?depth=N`         | On search result click + depth change |
| GET    | `/api/mods/{id}`                  | On graph node click (detail panel) |

### Request Shape (from `backend/schemas.py`)

```
GET /api/mods/search?q=brittany&game=108600
Response: ModSearchResult[] — {workshop_id, title, app_id, obsolete}

GET /api/graph/{workshop_id}?depth=2
Response: GraphData — {nodes: GraphNode[], edges: GraphEdge[], total_nodes, truncated}

GET /api/mods/{workshop_id}
Response: ModDetail — {id, workshop_id, title, author, author_id, author_profile_url,
         canonical_url, preview_url, posted, updated, file_size, description,
         obsolete, imported_at, app_id, source}
```

### Cytoscape Mapping

`GraphData` nodes and edges map directly to Cytoscape elements:

```
API GraphNode       → Cytoscape node: {data: {id: workshop_id, label: title, obsolete}}
API GraphEdge       → Cytoscape edge: {data: {source: from_workshop_id, target: to_workshop_id, kind: edge_type}}
```

`GraphData.total_nodes` and `truncated` used to show a warning if graph was cut at 200 nodes.

---

## Copywriting Contract

| Element              | Copy                                              |
|---------------------|---------------------------------------------------|
| Page title          | `"Workshop Graph Explorer"`                      |
| Sidebar heading     | `"Workshop Dependency Graph"`                    |
| Search placeholder  | `"Search mods..."`                               |
| Empty graph message | `"Select a mod to view its dependency graph"`   |
| Loading graph       | `"Loading graph..."`                             |
| Error (API fail)    | `"Failed to load graph: {error}. Is the backend running?"` |
| No results          | `"No mods found for '{query}'"`                  |
| Obsolete badge      | `"[Obsolete]"` displayed inline in search results and detail panel |
| Obsolete banner     | `"This mod is marked obsolete"` in red at top of detail panel |
| Depth label         | `"Depth: N"` where N is current slider value     |
| Stats — nodes       | `"Nodes: N"`                                     |
| Stats — edges       | `"Edges: N"`                                     |
| Stats — mod count   | `"Mods: N"`                                      |
| Detail — author     | `"Author:" label + name as link if profile URL available |
| Detail — workshop   | `"View on Steam"` CTA link opening canonical_url in new tab |
| Detail — posted     | `"Posted: {date}"`                               |
| Detail — updated    | `"Updated: {date}"`                              |
| Close button        | `X` icon with `aria-label="Close panel"` (WCAG 2.1 Criterion 1.1.1) |
| Truncated graph     | `"Graph truncated: showing {N} of {total} nodes (max 200)"` warning banner |

**No destructive actions in Phase 2.** Read-only application. No confirmations needed.

---

## Tooling

| Tool        | Version  | Role                                      |
|-------------|----------|-------------------------------------------|
| Cytoscape.js | 3.29.2  | Graph rendering (CDN: unpkg.com)         |
| Vite        | 5.x      | Build tool only; no SPA routing           |
| JavaScript  | ES2020+  | Language (no TypeScript in Phase 2)       |
| CSS         | Plain CSS with custom properties          | Styling                                   |

**No component library, no shadcn, no Tailwind, no React/Vue.** Single HTML page + vanilla JS modules.

---

## Component Registry

**Registry:** none (no shadcn or third-party block registry)

**Safety Gate:** not applicable

---

## Pre-Populated From Upstream

| Source            | Decisions Used                                      |
|-------------------|----------------------------------------------------|
| `web/index.html`  | CSS custom property names and values, font stack, grid layout (320px sidebar), border radius, panel opacity |
| `web/demoData.js` | Node shapes (round-rectangle, hexagon, ellipse), edge styles (solid/dashed/dotted), arrow shape (triangle), Cytoscape selector syntax |
| `backend/schemas.py` | API response shapes, field names (`from_workshop_id`, `edge_type`, `obsolete`) |
| `ROADMAP.md`      | Phase 2 success criteria, scope boundaries, 200-node truncation rule |
| `REQUIREMENTS.md` | UI-01 through UI-10 verbatim requirements, THEME-01 deferred |
| `STACK.md`        | Cytoscape.js 3.29.2 CDN URL, Vite 5.x, no SPA routing |
| `ARCHITECTURE.md` | Single HTML page structure, component boundary descriptions |

---

## Decisions Made in This Phase

1. **Layout:** Two-column CSS grid: `320px` fixed sidebar + `1fr` graph canvas. Responsive to single column at 900px breakpoint (sidebar collapses to top bar, graph below).
2. **Detail panel:** Overlay panel (right side on desktop, top on mobile) — not inline sidebar replacement, so graph canvas size is preserved.
3. **Graph API call on node click:** Uses `GET /api/mods/{id}` separately from the graph fetch, because the graph endpoint returns only `{workshop_id, title, obsolete}` per node (not full detail). Two distinct API calls per node click is acceptable for v1.
4. **Dagre as default layout:** Explicitly chosen over `breadthfirst` and `cose` because it is stable, hierarchical, and respects the directed dependency flow.
5. **Debounce search:** 300ms to avoid flooding `/api/mods/search` on each keystroke.
6. **Node deduplication in frontend:** Not needed — backend deduplicates before returning (`GraphData` already deduped by backend per API-06 contract).

---

## Out of Scope (Deferred to Phase 3)

- URL state encoding (`/api/state` or query params)
- Escape key to close detail panel
- CSS custom property refinement (Zomboid grays/browns)
- Gritty typography, utilitarian UI accents
- Shareable/bookmarkable URLs

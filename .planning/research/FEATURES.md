# Features Research

**Domain:** Interactive graph visualization web app for Steam Workshop mod dependency analysis
**Confidence:** MEDIUM

**Three categories:**

## Table Stakes (must-have)

Interactive canvas, click-to-detail, lazy-load neighborhood, mod search by name, depth control, multiple layouts, cycle detection, live Neo4j data, game switching, mod stats, obsolete warnings, responsive layout, keyboard nav, visual legend.

## Differentiators

Two-node shortest path, expand-in-place (no re-render), collapse subgraph, breadcrumb trail, bookmark/share URL, workshop thumbnails, deep-links, author pages, impact analysis, collection membership, dep count badges, graph summary stats, edge-type/node-kind filters, subscriber threshold, highlight dependents, in-graph search.

## Anti-Features (do NOT build)

Write access to Neo4j, auth/accounts, mod download, mobile UI, full-text description search, real-time WebSocket sync, concurrent query queuing, built-in Steam API, graph editing.

## Phase Recommendation

- Phase 1: Backend API + live data + mod search + lazy-load + detail panel
- Phase 2: Layouts + obsolete warning + filters + keyboard nav + stats
- Phase 3: Shortest path + impact analysis + expand-in-place + bookmarks
- Phase 4: Thumbnails + author pages + subscriber filter

## Key Gaps

- No competitor audit possible (WebSearch blocked)
- Lazy-load dedup behavior needs UX decision

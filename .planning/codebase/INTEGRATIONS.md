# External Integrations

**Analysis Date:** 2026-03-27

## APIs & External Services

**Steam Web API:**
- Endpoint: `https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/`
- Purpose: Fetch `dependents` field for reverse-dependency lookup
- SDK/Client: `requests` library in `main.py`
- Auth: `STEAM_API_KEY` environment variable (optional - only needed for `--with-steam-web` flag)
- Usage: `main.py::get_dependent_mods_from_steam_web_api()` calls this API

**Steam Community (Web Scraping):**
- Target: `https://steamcommunity.com/sharedfiles/filedetails/?id=<id>`
- Purpose: Extract mod metadata (title, author, dependencies, file size, posted/updated dates, collection items)
- SDK/Client: `@playwright/cli` via `npx` subprocess (`src/steam_workshop/playwright_cli.clj`)
- Auth: Steam session cookie (optional - if not provided, uses anonymous session with random session ID)
- No official API key required for scraping - uses headless Chromium

**Steam Workshop Browse:**
- Target: `https://steamcommunity.com/workshop/browse/?appid=<appId>&requiredtags[]=<tag>&actualsort=<sort>&p=<page>`
- Purpose: Paginated listing to discover seed IDs for import
- SDK/Client: Direct HTTP GET via `java.net.http.HttpClient` in `src/steam_workshop/importer.clj`
- Auth: None required
- Page layout: React SSR. Results are embedded in the inline script `window.SSR.renderContext=JSON.parse("...")` under the dehydrated query cache (`queryData` -> `queries[] -> state.data.results[]`). Parsed by `steam-workshop.workshop/extract-ssr-browse-ids` (HTML path) and by `extract-workshop-list-ids-script` (browser path); legacy `.workshopBrowseItems .workshopItem` DOM selectors and the `filedetails/?id=` href regex remain as fallbacks for the old layout.
- Note: detail pages (`sharedfiles/filedetails`, collections included) still use the legacy DOM, so `extract-json-script` keeps its class-based selectors; `og:description` was dropped there, so description falls back to `.workshopItemDescription`.

## Data Storage

**Neo4j Graph Database:**
- Image: `neo4j:5` (Docker, via `compose.yml`)
- Connection (Bolt): `bolt://localhost:7687` - declared in env, used for driver connection info
- Connection (HTTP/Tx): `http://localhost:7474/db/neo4j/tx/commit` - actual wire protocol used
- Client: Direct HTTP POST/GET via `java.net.http.HttpClient` (no official Neo4j Java driver in Clojure; uses Cypher transaction endpoint directly)
- Schema:
  - Nodes: `(:Mod {id, workshop_id, title, obsolete, author, author_id, ...})`
  - Nodes: `(:Collection {id, workshop_id, title, ...})`
  - Nodes: `(:Author {id, name, profile_url, ...})`
  - Edges: `(:Mod)-[:REQUIRES]->(:Mod)`, `(:Collection)-[:CONTAINS]->(:Mod)`, `(:Author)-[:AUTHORED]->(:Mod)`, `(:Author)-[:ASSEMBLED]->(:Collection)`
- Auth: Basic Auth from `NEO4J_AUTH` env var (format: `user/password`)

**File Storage:**
- Local filesystem only - `mod.info` and `workshop.txt` files read from Steam Workshop download directory
- No cloud storage integration

**Caching:**
- In-memory cache (1-hour TTL) via `recent-mod-ids` query in `src/steam_workshop/importer.clj`
- If a mod was imported within the last hour, it is skipped on re-import

## Authentication & Identity

**Neo4j Authentication:**
- Method: Basic Auth header (Base64-encoded `user:password`)
- Env var: `NEO4J_AUTH` (format: `neo4j/password`)
- Config: `src/steam_workshop/neo4j.clj::basic-auth-header()` builds the header

**Steam API Authentication:**
- Method: Query parameter `?key=<STEAM_API_KEY>`
- Env var: `STEAM_API_KEY`
- Only required for `get_dependent_mods_from_steam_web_api()` in `main.py`

**Steam Web Scraping:**
- Method: None (anonymous) OR session cookie (not implemented in current code - uses random session IDs)
- Session management: `src/steam_workshop/playwright_cli.clj` creates named sessions (`sw-<uuid-prefix>`)

## Monitoring & Observability

**Error Tracking:**
- None (no Sentry, Bugsnag, or similar)

**Logs:**
- Console/stdout only - `println` in Clojure, `print` in Python
- No structured logging library

## CI/CD & Deployment

**Hosting:**
- No deployment - local CLI tool

**CI Pipeline:**
- None detected (no GitHub Actions, GitLab CI, or similar)

## Environment Configuration

**Required env vars:**
- `NEO4J_AUTH` (required) - Neo4j credentials, format `user/password`
- `NEO4J_URI` (optional) - Bolt connection URI; defaults to `bolt://localhost:7687`
- `NEO4J_TX_URL` (optional) - HTTP transaction endpoint; derived from `NEO4J_URI` if not set
- `STEAM_API_KEY` (optional) - only needed for `reverse --with-steam-web`

**Secrets location:**
- `.env` file in project root (gitignored)
- `.env.example` as checked-in template (no real secrets)

## Webhooks & Callbacks

**Incoming:**
- None

**Outgoing:**
- None

---

*Integration audit: 2026-03-27*

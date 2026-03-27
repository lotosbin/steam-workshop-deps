# Architecture

**Analysis Date:** 2026-03-27

## Pattern Overview

**Overall:** Multi-language pipeline architecture with two distinct execution paths sharing a common data model.

**Key Characteristics:**
- **Two-language split**: Python (`main.py`) for local filesystem analysis, Clojure (Babashka) for web scraping and graph database operations
- **Babashka as CLI scripting layer**: Top-level `.bb.clj` scripts are executable entry points that load shared Clojure namespaces from `src/steam_workshop/`
- **Graph-first data design**: All imported data flows into Neo4j; the graph is the primary knowledge store
- **BFS crawling with bounded discovery**: Import pipeline uses breadth-first search with `max-depth` and `max-nodes` limits, plus a 1-hour cache skip for recently imported mods
- **Batch writes to Neo4j**: Nodes, edges, authors, and collections are accumulated in buffers and flushed in batches (default 80 edges per batch) to reduce HTTP round-trips

## Layers

**Python CLI Layer (Local Analysis):**
- Purpose: Analyze a local Steam Workshop directory on disk (no network required)
- Location: `main.py`
- Contains: Local mod.info/workshop.txt parsing, dependency tree building, cycle detection, reverse dependency queries
- Depends on: Python stdlib + `requests`
- Used by: Developers analyzing installed mods offline

**Babashka Script Layer (CLI Entrypoints):**
- Purpose: Thin executable wrappers that parse CLI args and delegate to shared namespaces
- Location: `*.bb.clj` in project root
- Contains: Argument parsing, env loading, pipeline orchestration calls
- Depends on: `src/steam_workshop/` shared namespaces
- Used by: All production workflows (import, query, fetch)

**Shared Clojure Library (`src/steam_workshop/`):**
- Purpose: Core logic reused by all Babashka scripts
- Location: `src/steam_workshop/`
- Contains: Workshop scraping, Neo4j operations, Playwright CLI wrapper, .env handling
- Depends on: Babashka built-ins + Java stdlib (`java.net.http`, `java.util`, `java.time`)
- Used by: All `.bb.clj` scripts

**Web Scraping Layer:**
- Purpose: Fetch Steam Workshop pages via Playwright CLI and extract structured JSON
- Location: `src/steam_workshop/workshop.clj` (page fetching) + `src/steam_workshop/importer.clj` (session management + BFS crawl)
- Contains: `extract-json-script` (inline JS executed in browser), browser session lifecycle
- Depends on: `@playwright/cli` npm package, `npx`
- Used by: `importer/import-browse!`, `importer/import-single!`

**Neo4j Graph Storage Layer:**
- Purpose: Persist and query the dependency graph
- Location: `src/steam_workshop/neo4j.clj`
- Contains: HTTP client for Neo4j transactional endpoint, Cypher statement builders, node/edge row factories
- Depends on: Neo4j server (HTTP), Java `HttpClient`
- Used by: All import and query scripts

**Web UI Layer:**
- Purpose: MVP static visualization of the graph
- Location: `web/`
- Contains: `index.html`, `demoData.js`
- Depends on: Vanilla JS (no framework)
- Used by: Quick visual exploration of demo data

## Data Flow

**Local Analysis Path (Python CLI):**

1. `main.py` receives `tree`/`reverse`/`cycles` subcommand
2. `scan_local_workshop()` reads every subdirectory under `workshop/content/<appId>/`
3. Each subdirectory: `parse_zomboid_mod_info()` or `parse_workshop_txt()` extracts `WorkshopItem` dataclass
4. `items_by_internal_id` (Dict) + `internal_id_by_published_id` (Dict) built in memory
5. `build_dependency_tree()` / `build_reverse_edges()` / `find_cycles_from_root()` traverses in-memory graph
6. Results printed to stdout as ASCII tree or list

**Import Path (Babashka CLI):**

1. `steam_import_neo4j.bb.clj` / `steam_import_single_neo4j.bb.clj` parses args + loads `.env`
2. `importer/import-browse!` or `importer/import-single!` opens Playwright browser session
3. `workshop/fetch-info()` navigates to Steam page, executes `extract-json-script` inline JS, returns structured map
4. `importer/import-seeds!` runs BFS loop: for each `id` in queue, fetch deps via `workshop/fetch-info`, buffer nodes/edges
5. Batched POSTs to `NEO4J_TX_URL` via `neo4j/post-statement!`
6. 1-hour cache skip: `neo4j/recent-mod-ids()` queries Neo4j for recently imported mods, skips re-fetch

**Query Path (Babashka CLI):**

1. `steam_query_neo4j.bb.clj` loads `.env`, calls `neo4j/query!` with hand-written Cypher
2. `detect-kind()` tries Mod -> Collection -> Author node type detection
3. Dispatches to `query-mod` / `query-collection` / `query-author` which each run multiple Cypher queries
4. Aggregated result printed as JSON

**Neo4j Graph Schema:**

- `:Mod` nodes with properties: `id`, `workshop_id`, `title`, `author`, `author_id`, `canonical_url`, `preview_url`, `posted`, `updated`, `file_size`, `description`, `obsolete`, `imported_at`, `source`
- `:Collection` nodes with additional: `page_type`, `collection_item_ids`, `linked_workshop_ids`
- `:Author` nodes with: `id`, `name`, `profile_url`, `source`
- `(Mod)-[:REQUIRES]->(Mod)` edges
- `(Collection)-[:CONTAINS]->(Mod)` edges
- `(Author)-[:AUTHORED]->(Mod)` edges
- `(Author)-[:ASSEMBLED]->(Collection)` edges

## Key Abstractions

**WorkshopItem (Python):**
- Purpose: Immutable parsed representation of a local mod's `mod.info` / `workshop.txt`
- Location: `main.py` dataclass
- Pattern: `@dataclass(frozen=True)` for immutable value object

**TreeNode (Python):**
- Purpose: In-memory tree node for dependency visualization
- Location: `main.py` dataclass
- Pattern: Recursive tree with `children` list, cycle/missing flags

**Node/Edge Row Factories (Clojure):**
- Purpose: Build map payloads for Neo4j batch statements
- Location: `src/steam_workshop/neo4j.clj` (`node-row`, `edge-row`, `collection-row`, `author-row`, etc.)
- Pattern: Builder function that assembles `:id` + `:props` map for `UNWIND $rows AS row MERGE ...` Cypher

**Browser Session (Clojure):**
- Purpose: Stateful Playwright CLI session for page navigation
- Location: `src/steam_workshop/playwright_cli.clj` (`open-session!`, `goto!`, `eval!`, `close-session!`)
- Pattern: Named session string passed to `npx @playwright/cli` CLI with `-s=<session>` flag

**Import State Machine (Clojure):**
- Purpose: BFS crawl state held in Clojure atoms
- Location: `src/steam_workshop/importer.clj` (`import-seeds!`)
- Pattern: `visited` (set), `queue` (vec of [id depth]), `node-buf`, `edge-buf`, `author-buf`, `authored-edge-buf` all in atoms; flushed when buffer thresholds reached

**Environment Map (Clojure):**
- Purpose: Dotenv file loaded as map, merged with system env
- Location: `src/steam_workshop/dotenv.clj` (`load-file-map`, `getenv`)
- Pattern: Custom .env parser (no external lib), priority: System env > .env file

## Entry Points

**Python CLI:**
- Location: `main.py`
- Triggers: `python main.py tree|reverse|cycles --workshop-dir ... --root ...`
- Responsibilities: Arg parsing, local filesystem scan, in-memory graph computation, stdout output

**Babashka Import (Browse):**
- Location: `steam_import_neo4j.bb.clj`
- Triggers: `bb steam_import_neo4j.bb.clj --appid 108600 --required-tag "Build 42" ...`
- Responsibilities: Arg parsing, env loading, seed ID extraction from browse page, BFS import orchestration

**Babashka Import (Single):**
- Location: `steam_import_single_neo4j.bb.clj`
- Triggers: `bb steam_import_single_neo4j.bb.clj --id 3689745069`
- Responsibilities: Single item import with full dep tree

**Babashka Fetch Info:**
- Location: `steam_fetch_workshop_info.bb.clj`
- Triggers: `bb steam_fetch_workshop_info.bb.clj --id 3688270372`
- Responsibilities: Fetch one page, print JSON to stdout (no Neo4j write)

**Babashka Query:**
- Location: `steam_query_neo4j.bb.clj`
- Triggers: `bb steam_query_neo4j.bb.clj --id 3689745069`
- Responsibilities: Query Neo4j for node + its relationships, print JSON

## Error Handling

**Python CLI:**
- Exceptions for missing files (`FileNotFoundError`), unresolvable mod IDs (`ValueError`), HTTP errors (`requests.HTTPError`)
- CLI returns exit code 2 for argument errors

**Clojure (Babashka):**
- `ex-info` with map data for structured errors (`:exit`, `:stdout`, `:stderr`, `:hint`)
- Playwright CLI errors caught via `cli-ok!` which throws with `install-browser-hint`
- Neo4j HTTP errors caught via `http-post-json` checking status codes
- `cli-ok!` wraps shell results; `post-statement!` throws on non-2xx

**Logging:**
- All output via `println` to stdout/stderr (no logging library)
- Import progress printed inline: seed count, fetched item info, batch counts, BFS queue stats
- Error messages include contextual hint (e.g., "run `npx @playwright/cli install-browser`")

## Cross-Cutting Concerns

**Logging:** Plain `println` throughout Clojure and Python code. No structured logger.

**Validation:**
- Mod ID regex validation: `re-matches #"\d+"` on all numeric IDs
- Obsolete detection: `re-find #"(?i)(obsolete|deprecat)"` on title strings
- Arg validation in Babashka scripts: sort values, section values, missing required args

**Authentication:**
- Neo4j: HTTP Basic Auth from `NEO4J_AUTH=user/pass` parsed in `neo4j/split-auth`
- Steam Web API: `STEAM_API_KEY` env var (optional, used in Python reverse command)

**Configuration:**
- `.env` file (never committed with secrets), loaded by all Babashka scripts via `steam-workshop.dotenv/load-file-map`
- Python reads from system env only (no .env file loading)
- `bb.edn`: `{:paths ["src" "."]}` so Babashka resolves `steam-workshop.*` namespaces from `src/` and root

---

*Architecture analysis: 2026-03-27*

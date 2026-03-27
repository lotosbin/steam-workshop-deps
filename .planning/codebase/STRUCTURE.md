# Codebase Structure

**Analysis Date:** 2026-03-27

## Directory Layout

```
steam-workshop-deps/
├── main.py                          # Python CLI entry point (local analysis)
├── pyproject.toml                   # Python package manifest + dependencies
├── bb.edn                           # Babashka classpath config
├── .env                             # Environment variables (NOT committed)
├── .env.example                     # Template for .env
├── CLAUDE.md                        # Project documentation
├── steam_fetch_workshop_info.bb.clj  # Babashka: fetch single page as JSON
├── steam_import_neo4j.bb.clj         # Babashka: bulk import from browse
├── steam_import_single_neo4j.bb.clj  # Babashka: import single item + deps
├── steam_query_neo4j.bb.clj         # Babashka: query Neo4j node
├── src/                             # Shared Clojure library
│   └── steam_workshop/
│       ├── dotenv.clj              # .env file parser + getenv
│       ├── playwright_cli.clj      # Playwright CLI wrapper
│       ├── workshop.clj            # Steam page fetch + extract JSON
│       ├── neo4j.clj               # Neo4j HTTP client + Cypher builders
│       └── importer.clj            # BFS import orchestration
└── web/                            # Static Web UI MVP
    ├── index.html                   # Demo visualization
    └── demoData.js                  # Static sample data
```

## Directory Purposes

**Project Root (`/`):**
- Purpose: CLI entry points and configuration
- Contains: Python script, Babashka scripts, project config files
- Key files: `main.py`, `*.bb.clj`, `bb.edn`, `pyproject.toml`

**`src/steam_workshop/`:**
- Purpose: Shared Clojure library loaded by all Babashka scripts
- Contains: 5 namespace files, each focused on one external system
- Key files: All `.clj` files here are loaded by root-level `.bb.clj` scripts

**`web/`:**
- Purpose: MVP static visualization of dependency graph
- Contains: `index.html` + `demoData.js` (vanilla JS, no build step)
- Generated: No
- Committed: Yes

**`.env`:**
- Purpose: Local secrets (Neo4j auth, Steam API key)
- Generated: No (created from `.env.example`)
- Committed: No (in `.gitignore`)

## Key File Locations

**Entry Points:**

- `main.py`: Python CLI — `tree`, `reverse`, `cycles` subcommands for local analysis
- `steam_import_neo4j.bb.clj`: Bulk import from Steam browse page to Neo4j
- `steam_import_single_neo4j.bb.clj`: Single item import + recursive deps to Neo4j
- `steam_fetch_workshop_info.bb.clj`: Fetch one Steam page, output JSON
- `steam_query_neo4j.bb.clj`: Query Neo4j for a node and its relationships

**Configuration:**

- `bb.edn`: Babashka classpath — `{:paths ["src" "."]}` resolves `steam-workshop.*` namespaces
- `pyproject.toml`: Python dependencies (`requests`, `neo4j`) and entry point (`workshop-deps = "main:main"`)
- `.env.example`: Documents required env vars (`NEO4J_AUTH`, `NEO4J_URI`, `NEO4J_TX_URL`)
- `CLAUDE.md`: Project overview, tech stack, usage examples

**Core Logic (Shared Library):**

- `src/steam_workshop/workshop.clj`: Fetch Steam Workshop pages via Playwright CLI, extract structured JSON with inline JavaScript
- `src/steam_workshop/importer.clj`: BFS crawl orchestration, buffer management, batch Neo4j writes
- `src/steam_workshop/neo4j.clj`: Neo4j HTTP client, Cypher statement strings, node/edge row builder functions
- `src/steam_workshop/playwright_cli.clj`: `npx @playwright/cli` wrapper (session management, CLI result extraction)
- `src/steam_workshop/dotenv.clj`: Custom `.env` parser (no external lib dependency)

**Data Structures (Python):**

- `main.py`: `WorkshopItem` dataclass (frozen), `TreeNode` dataclass — primary data models for local analysis

## Naming Conventions

**Files:**

- Python: `snake_case.py` (`main.py`)
- Clojure: `kebab-case.clj` (`playwright_cli.clj`, `workshop.clj`)
- Babashka entry points: `snake_case.bb.clj` (`steam_import_neo4j.bb.clj`)
- JS/HTML: `kebab-case.js` / `kebab-case.html` (`demoData.js`, `index.html`)

**Namespaces (Clojure):**

- `steam-workshop.<module-name>` (e.g., `steam-workshop.workshop`, `steam-workshop.neo4j`)
- File naming: `kebab-case.clj` matches `snake_case` namespace segments

**Clojure Vars/Functions:**

- `kebab-case` throughout: `extract-json-script`, `post-statement!`, `http-get-str`, `cli-ok!`

**Python:**

- `snake_case` throughout: `scan_local_workshop`, `build_reverse_edges`, `get_dependent_mods_from_steam_web_api`
- Classes: `PascalCase` (`WorkshopItem`, `TreeNode`)

## Where to Add New Code

**New Steam Page Extractor:**
- Implementation: Add new function in `src/steam_workshop/workshop.clj` or new namespace `src/steam_workshop/<game>.clj`
- Extracted IDs flow into `importer.clj`'s BFS queue automatically
- No changes to entry points needed if function follows `fetch-info` signature

**New Neo4j Node Type:**
- Row builder: Add builder function in `src/steam_workshop/neo4j.clj` (e.g., `tag-row`, `game-row`)
- Statement: Add Cypher statement string and `post-<type>-batch!` function in `importer.clj`
- Query: Add Cypher query and query function in `steam_query_neo4j.bb.clj`
- Files to touch: `neo4j.clj`, `importer.clj`, `steam_query_neo4j.bb.clj`

**New Python CLI Subcommand:**
- Primary code: Add new `add_parser` block in `main.py`'s `main()` function
- Tests: Co-located test file or pytest suite

**New Babashka CLI Script:**
- Implementation: Create new `*.bb.clj` in project root
- Load shared namespaces: `(require '[steam-workshop.<module> :as <alias>])`
- Load env: Copy `.env` loading pattern from existing scripts
- Parse args: Copy `parse-args` / `validate-opts` pattern from `steam_import_neo4j.bb.clj`

**New Utility Function (Shared Clojure):**
- If Playwright-related: `src/steam_workshop/playwright_cli.clj`
- If Neo4j-related: `src/steam_workshop/neo4j.clj`
- If page extraction: `src/steam_workshop/workshop.clj`
- If import orchestration: `src/steam_workshop/importer.clj`
- If env/config: `src/steam_workshop/dotenv.clj`

**New Python Analysis Feature (Local Mods):**
- Primary code: `main.py` — add new function, register subcommand
- If adding new mod.info format support: Add parser in `main.py` alongside `parse_zomboid_mod_info` and `parse_workshop_txt`

## Special Directories

**`src/`:**
- Purpose: All shared Clojure library code (not a Python src directory)
- Generated: No
- Committed: Yes
- Note: Added to Babashka classpath via `bb.edn` `:paths ["src" "."]`

**`web/`:**
- Purpose: Static MVP web visualization (no build step, served directly)
- Generated: No
- Committed: Yes

**`.env`:**
- Purpose: Local secrets (Neo4j credentials)
- Generated: Copy from `.env.example`
- Committed: No (in `.gitignore`)

---

*Structure analysis: 2026-03-27*

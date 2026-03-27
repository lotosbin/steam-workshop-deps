# Steam Workshop Dependencies

Steam Workshop 依赖关系分析工具，主要支持 Project Zomboid。

## 项目概述

用于查找和浏览 Steam Workshop 创意工坊物品的依赖和被依赖关系的工具。

## 技术栈

- **Clojure**: Babashka (`.bb.clj`) 脚本，主要业务逻辑
- **Python**: CLI 入口 (`main.py`)
- **Playwright CLI**: 网页抓取 Steam 社区页面
- **Neo4j**: 图数据库存储

## 项目结构

```
steam-workshop-deps/
├── main.py                      # Python CLI 入口
├── pyproject.toml               # Python 依赖配置
├── bb.edn                       # Babashka 配置
├── .env                         # Neo4j/Steam API 配置
├── src/steam_workshop/          # Clojure 公共 namespace
│   ├── workshop.clj             # Steam workshop 抓取逻辑
│   ├── playwright_cli.clj       # Playwright CLI 封装
│   ├── dotenv.clj               # .env 解析
│   ├── neo4j.clj                # Neo4j 操作
│   └── importer.clj            # 导入逻辑
├── web/                         # Web MVP 静态页面
└── *.bb.clj                     # 顶层 Babashka 脚本
```

## 环境配置

### 必需依赖

- `bb` (Babashka)
- Python 3.12+
- Node.js (用于 playwright-cli)

### 环境变量 (.env)

```env
NEO4J_AUTH=neo4j/你的密码
NEO4J_URI=bolt://localhost:7687
NEO4J_TX_URL=http://localhost:7474/db/neo4j/tx/commit
STEAM_API_KEY=你的Steam API Key  # 可选
```

### 首次使用

```bash
# 安装 playwright 浏览器
npx @playwright/cli install-browser
```

## CLI 使用

### Python CLI

```bash
# 激活虚拟环境
source .venv/bin/activate

# 输出依赖树
workshop-deps tree --workshop-dir "/path/to/workshop/content/108600" --root "mod_id"

# 输出反向依赖
workshop-deps reverse --workshop-dir "/path/to/workshop/content/108600" --target "mod_id"

# 检测循环依赖
workshop-deps cycles --workshop-dir "/path/to/workshop/content/108600" --root "mod_id"
```

### Babashka CLI

```bash
# 获取单个 Workshop 信息
bb steam_fetch_workshop_info.bb.clj --id 3688270372

# 导入到 Neo4j
bb steam_import_neo4j.bb.clj \
  --appid 108600 \
  --required-tag "Build 42" \
  --sort totaluniquesubscribers \
  --page 1 \
  --page-limit 10 \
  --max-depth 5 \
  --max-nodes 300

# 导入单个 Item
bb steam_import_single_neo4j.bb.clj --id 3689745069

# 查询 Neo4j
bb steam_query_neo4j.bb.clj --id 3689745069
```

### 常用参数

- `--sort`: `lastupdated` (最近更新) | `totaluniquesubscribers` (最多订阅) | `trend` (热门)
- `--appid`: Steam App ID (Project Zomboid = 108600)
- `--user-workshop-url`: 指定用户的 Workshop 页面
- `--user-workshop-section`: `collections` 提取合集列表

## 核心功能

1. **依赖树解析**: 解析 `mod.info` / `workshop.txt`
2. **反向依赖查询**: 查找依赖某 mod 的所有 mod
3. **循环依赖检测**: 检测可达范围内的循环
4. **Steam 网页抓取**: 通过 Playwright CLI 抓取 Steam 社区页面
5. **Neo4j 图数据库**: 存储和查询依赖关系图

## 注意事项

- 模组标题包含 `obsolete` 或 `deprecate` (不区分大小写) 会标记为 obsolete
- Steam App ID: Project Zomboid = 108600

<!-- GSD:project-start source:PROJECT.md -->
## Project

**Steam Workshop Graph Explorer**

An interactive web application for exploring Steam Workshop mod dependency graphs. Users browse, search, and visualize mod relationships across any Steam Workshop game in real-time via Neo4j graph queries. Built as a deployable Docker container for easy self-hosting.

**Core Value:** Users can find any mod's dependencies and dependents in seconds, with a visual graph that makes complex mod relationships immediately clear.

### Constraints

- **Tech Stack**: Cytoscape.js frontend + lightweight backend (Node.js or Python) + Neo4j — no React/Vue SPA complexity
- **Deployment**: Docker single container, simple `docker run` — no Kubernetes
- **Security**: Neo4j credentials via environment variable, read-only Neo4j user only
- **Performance**: Frontend fetches on-demand; no pre-loading entire graph (lazy load neighborhood)
- **Browser**: Modern browsers only (ES2020+), no IE11
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Python 3.12+ - CLI entry point (`main.py`), mod.info parsing, dependency tree algorithms
- Clojure (Babashka) - Core business logic scripts, web scraping orchestration, Neo4j integration
- JavaScript (ES module) - Web MVP visualization (`web/demoData.js`, `web/index.html`)
- HTML/CSS - Static web UI
## Runtime
- Interpreter: Python 3.12+ (`.python-version`)
- Package manager: `uv` (lockfile: `uv.lock`, venv at `.venv/`)
- Executable: `main.py` CLI exposed as `workshop-deps` via `pyproject.toml` script entry point
- Runtime: `bb` (Babashka) - JVM-based Clojure scripting with fast startup
- Config: `bb.edn` - sets load path to `["src" "."]`
- JVM interop: Uses `java.net.http.HttpClient` (built-in Java 11+) for HTTP, no extra HTTP library needed in Clojure
## Frameworks
- No heavyweight web framework - pure CLI tools
- Babashka namespaces loaded as libraries: `steam-workshop.*`
- Standard library `argparse` - CLI argument parsing
- Standard library `pathlib`, `dataclasses`, `re`, `json` - core utilities
- `requests>=2.32.5` - HTTP client for Steam Web API calls (single endpoint only)
- `clojure.java.shell` - subprocess invocation of Playwright CLI and `npx`
- `cheshire.core` - JSON parsing/generation (used in both `neo4j.clj` and `importer.clj`)
- `clojure.string` - string utilities
- Cytoscape.js 3.29.2 (CDN: `unpkg.com`) - graph rendering in `web/index.html`
- Vanilla JS (ES module) - `web/demoData.js`
- No test framework detected (no pytest,clojure.test, or similar files)
- `uv` - Python package management and virtual environment
- `npx @playwright/cli` - headless browser automation (Node.js/npm)
- Docker Compose (`compose.yml`) - Neo4j container for local development
## Key Dependencies
- `requests>=2.32.5` - Steam Web API HTTP calls from `main.py`
- `neo4j>=5.26.0` - Neo4j driver (declared but not actively used in `main.py`; Neo4j HTTP API is used directly from Clojure)
- `cheshire` - JSON encoding/decoding (Neo4j wire protocol is JSON over HTTP)
- `clojure.java.shell` - subprocess for `npx @playwright/cli`
- `java.net.http.HttpClient` (JDK built-in) - HTTP POST to Neo4j transaction endpoint
- `java.util.UUID`, `java.time.Duration`, `java.util.Base64` (JDK built-ins)
- `@playwright/cli` - headless Chromium browser automation for scraping Steam community pages
- Installed via: `npx @playwright/cli install-browser`
- `neo4j:5` (Docker image) - Graph database, accessed via HTTP transaction endpoint (port 7474/7687)
## Configuration
- File: `.env` (gitignored, exists locally)
- Variables: `NEO4J_AUTH`, `NEO4J_URI`, `NEO4J_TX_URL`, `STEAM_API_KEY`
- `.env.example` checked in as template
- `bb.edn` - load path config only (`{:paths ["src" "."]}`)
- `compose.yml` - Neo4j container service definition
- `pyproject.toml` - project metadata + dependencies + CLI script entry point
- `uv.lock` - lockfile for reproducible installs
- `.python-version` - Python 3.12+ pin
## Platform Requirements
- macOS/Linux (shell scripts, path separators)
- Babashka (`bb`)
- Python 3.12+ with `uv`
- Node.js (for `npx playwright/cli`)
- Docker / Docker Compose (for Neo4j)
- CLI tool run as scripts (`bb *.bb.clj`, `python main.py`)
- Neo4j must be accessible via HTTP (7474) and Bolt (7687)
- Playwright CLI with Chromium browser for scraping
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Languages
- Version: Babashka (latest)
- Used for: All core business logic, CLI scripts, scraping, Neo4j operations
- Entry points: `*.bb.clj` top-level scripts
- Version: Python 3.12+
- Used for: CLI entry point (`main.py`), local mod.info parsing, tree/cycle/reverse queries
- Run via: `source .venv/bin/activate && workshop-deps <cmd>`
## Clojure Conventions
### Namespace Declaration
- External libraries: `:as json`, `:as str`
- Internal modules: `:as neo4j`, `:as dotenv`
- Java stdlib: `:import` block with fully-qualified class names
### Naming Conventions
### Formatters
### Comments
- Use `;;` for comments (standard Lisp style)
- Top-level `.bb.clj` scripts include usage header comment:
#!/usr/bin/env bb
- Source files use `;; Description` inline comments before functions when needed
## Python Conventions
### Imports
### Type Hints
### Naming Conventions
### Dataclasses
- Use `frozen=True` for immutable data
- Use mutable `@dataclass` for recursive/tree structures
### Error Handling
## CLI Scripts
### Babashka CLI Pattern
#!/usr/bin/env bb
### Python CLI Pattern
## Logging
- Normal output: `*out*`
- Errors/usage: `(binding [*out* *err*] (println ...))`
## Configuration
- Clojure: Custom `.env` parser in `src/steam_workshop/dotenv.clj`
- `.env` in project root, loaded relative to script location via `*file*`
- Never commit `.env` - use `.env.example` for template
- `bb.edn`: `{:paths ["src" "."]}` - adds `src/` and root to load path
- `pyproject.toml` with `[project.scripts]` entry point
## Pattern Examples
### Filtering and Transforming Sequences
### Safe Optional Extraction
### Validation with Throws
### Guard with Default
### Recursive Tree Building
## File Locations
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- **Two-language split**: Python (`main.py`) for local filesystem analysis, Clojure (Babashka) for web scraping and graph database operations
- **Babashka as CLI scripting layer**: Top-level `.bb.clj` scripts are executable entry points that load shared Clojure namespaces from `src/steam_workshop/`
- **Graph-first data design**: All imported data flows into Neo4j; the graph is the primary knowledge store
- **BFS crawling with bounded discovery**: Import pipeline uses breadth-first search with `max-depth` and `max-nodes` limits, plus a 1-hour cache skip for recently imported mods
- **Batch writes to Neo4j**: Nodes, edges, authors, and collections are accumulated in buffers and flushed in batches (default 80 edges per batch) to reduce HTTP round-trips
## Layers
- Purpose: Analyze a local Steam Workshop directory on disk (no network required)
- Location: `main.py`
- Contains: Local mod.info/workshop.txt parsing, dependency tree building, cycle detection, reverse dependency queries
- Depends on: Python stdlib + `requests`
- Used by: Developers analyzing installed mods offline
- Purpose: Thin executable wrappers that parse CLI args and delegate to shared namespaces
- Location: `*.bb.clj` in project root
- Contains: Argument parsing, env loading, pipeline orchestration calls
- Depends on: `src/steam_workshop/` shared namespaces
- Used by: All production workflows (import, query, fetch)
- Purpose: Core logic reused by all Babashka scripts
- Location: `src/steam_workshop/`
- Contains: Workshop scraping, Neo4j operations, Playwright CLI wrapper, .env handling
- Depends on: Babashka built-ins + Java stdlib (`java.net.http`, `java.util`, `java.time`)
- Used by: All `.bb.clj` scripts
- Purpose: Fetch Steam Workshop pages via Playwright CLI and extract structured JSON
- Location: `src/steam_workshop/workshop.clj` (page fetching) + `src/steam_workshop/importer.clj` (session management + BFS crawl)
- Contains: `extract-json-script` (inline JS executed in browser), browser session lifecycle
- Depends on: `@playwright/cli` npm package, `npx`
- Used by: `importer/import-browse!`, `importer/import-single!`
- Purpose: Persist and query the dependency graph
- Location: `src/steam_workshop/neo4j.clj`
- Contains: HTTP client for Neo4j transactional endpoint, Cypher statement builders, node/edge row factories
- Depends on: Neo4j server (HTTP), Java `HttpClient`
- Used by: All import and query scripts
- Purpose: MVP static visualization of the graph
- Location: `web/`
- Contains: `index.html`, `demoData.js`
- Depends on: Vanilla JS (no framework)
- Used by: Quick visual exploration of demo data
## Data Flow
- `:Mod` nodes with properties: `id`, `workshop_id`, `title`, `author`, `author_id`, `canonical_url`, `preview_url`, `posted`, `updated`, `file_size`, `description`, `obsolete`, `imported_at`, `source`
- `:Collection` nodes with additional: `page_type`, `collection_item_ids`, `linked_workshop_ids`
- `:Author` nodes with: `id`, `name`, `profile_url`, `source`
- `(Mod)-[:REQUIRES]->(Mod)` edges
- `(Collection)-[:CONTAINS]->(Mod)` edges
- `(Author)-[:AUTHORED]->(Mod)` edges
- `(Author)-[:ASSEMBLED]->(Collection)` edges
## Key Abstractions
- Purpose: Immutable parsed representation of a local mod's `mod.info` / `workshop.txt`
- Location: `main.py` dataclass
- Pattern: `@dataclass(frozen=True)` for immutable value object
- Purpose: In-memory tree node for dependency visualization
- Location: `main.py` dataclass
- Pattern: Recursive tree with `children` list, cycle/missing flags
- Purpose: Build map payloads for Neo4j batch statements
- Location: `src/steam_workshop/neo4j.clj` (`node-row`, `edge-row`, `collection-row`, `author-row`, etc.)
- Pattern: Builder function that assembles `:id` + `:props` map for `UNWIND $rows AS row MERGE ...` Cypher
- Purpose: Stateful Playwright CLI session for page navigation
- Location: `src/steam_workshop/playwright_cli.clj` (`open-session!`, `goto!`, `eval!`, `close-session!`)
- Pattern: Named session string passed to `npx @playwright/cli` CLI with `-s=<session>` flag
- Purpose: BFS crawl state held in Clojure atoms
- Location: `src/steam_workshop/importer.clj` (`import-seeds!`)
- Pattern: `visited` (set), `queue` (vec of [id depth]), `node-buf`, `edge-buf`, `author-buf`, `authored-edge-buf` all in atoms; flushed when buffer thresholds reached
- Purpose: Dotenv file loaded as map, merged with system env
- Location: `src/steam_workshop/dotenv.clj` (`load-file-map`, `getenv`)
- Pattern: Custom .env parser (no external lib), priority: System env > .env file
## Entry Points
- Location: `main.py`
- Triggers: `python main.py tree|reverse|cycles --workshop-dir ... --root ...`
- Responsibilities: Arg parsing, local filesystem scan, in-memory graph computation, stdout output
- Location: `steam_import_neo4j.bb.clj`
- Triggers: `bb steam_import_neo4j.bb.clj --appid 108600 --required-tag "Build 42" ...`
- Responsibilities: Arg parsing, env loading, seed ID extraction from browse page, BFS import orchestration
- Location: `steam_import_single_neo4j.bb.clj`
- Triggers: `bb steam_import_single_neo4j.bb.clj --id 3689745069`
- Responsibilities: Single item import with full dep tree
- Location: `steam_fetch_workshop_info.bb.clj`
- Triggers: `bb steam_fetch_workshop_info.bb.clj --id 3688270372`
- Responsibilities: Fetch one page, print JSON to stdout (no Neo4j write)
- Location: `steam_query_neo4j.bb.clj`
- Triggers: `bb steam_query_neo4j.bb.clj --id 3689745069`
- Responsibilities: Query Neo4j for node + its relationships, print JSON
## Error Handling
- Exceptions for missing files (`FileNotFoundError`), unresolvable mod IDs (`ValueError`), HTTP errors (`requests.HTTPError`)
- CLI returns exit code 2 for argument errors
- `ex-info` with map data for structured errors (`:exit`, `:stdout`, `:stderr`, `:hint`)
- Playwright CLI errors caught via `cli-ok!` which throws with `install-browser-hint`
- Neo4j HTTP errors caught via `http-post-json` checking status codes
- `cli-ok!` wraps shell results; `post-statement!` throws on non-2xx
- All output via `println` to stdout/stderr (no logging library)
- Import progress printed inline: seed count, fetched item info, batch counts, BFS queue stats
- Error messages include contextual hint (e.g., "run `npx @playwright/cli install-browser`")
## Cross-Cutting Concerns
- Mod ID regex validation: `re-matches #"\d+"` on all numeric IDs
- Obsolete detection: `re-find #"(?i)(obsolete|deprecat)"` on title strings
- Arg validation in Babashka scripts: sort values, section values, missing required args
- Neo4j: HTTP Basic Auth from `NEO4J_AUTH=user/pass` parsed in `neo4j/split-auth`
- Steam Web API: `STEAM_API_KEY` env var (optional, used in Python reverse command)
- `.env` file (never committed with secrets), loaded by all Babashka scripts via `steam-workshop.dotenv/load-file-map`
- Python reads from system env only (no .env file loading)
- `bb.edn`: `{:paths ["src" "."]}` so Babashka resolves `steam-workshop.*` namespaces from `src/` and root
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->

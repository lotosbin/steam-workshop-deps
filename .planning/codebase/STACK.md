# Technology Stack

**Analysis Date:** 2026-03-27

## Languages

**Primary:**
- Python 3.12+ - CLI entry point (`main.py`), mod.info parsing, dependency tree algorithms
- Clojure (Babashka) - Core business logic scripts, web scraping orchestration, Neo4j integration

**Secondary:**
- JavaScript (ES module) - Web MVP visualization (`web/demoData.js`, `web/index.html`)
- HTML/CSS - Static web UI

## Runtime

**Python:**
- Interpreter: Python 3.12+ (`.python-version`)
- Package manager: `uv` (lockfile: `uv.lock`, venv at `.venv/`)
- Executable: `main.py` CLI exposed as `workshop-deps` via `pyproject.toml` script entry point

**Clojure/Babashka:**
- Runtime: `bb` (Babashka) - JVM-based Clojure scripting with fast startup
- Config: `bb.edn` - sets load path to `["src" "."]`
- JVM interop: Uses `java.net.http.HttpClient` (built-in Java 11+) for HTTP, no extra HTTP library needed in Clojure

## Frameworks

**Core:**
- No heavyweight web framework - pure CLI tools
- Babashka namespaces loaded as libraries: `steam-workshop.*`

**Data Processing (Python):**
- Standard library `argparse` - CLI argument parsing
- Standard library `pathlib`, `dataclasses`, `re`, `json` - core utilities
- `requests>=2.32.5` - HTTP client for Steam Web API calls (single endpoint only)

**Data Processing (Clojure):**
- `clojure.java.shell` - subprocess invocation of Playwright CLI and `npx`
- `cheshire.core` - JSON parsing/generation (used in both `neo4j.clj` and `importer.clj`)
- `clojure.string` - string utilities

**Web Visualization:**
- Cytoscape.js 3.29.2 (CDN: `unpkg.com`) - graph rendering in `web/index.html`
- Vanilla JS (ES module) - `web/demoData.js`

**Testing:**
- No test framework detected (no pytest,clojure.test, or similar files)

**Build/Dev:**
- `uv` - Python package management and virtual environment
- `npx @playwright/cli` - headless browser automation (Node.js/npm)
- Docker Compose (`compose.yml`) - Neo4j container for local development

## Key Dependencies

**Python (`pyproject.toml`):**
- `requests>=2.32.5` - Steam Web API HTTP calls from `main.py`
- `neo4j>=5.26.0` - Neo4j driver (declared but not actively used in `main.py`; Neo4j HTTP API is used directly from Clojure)

**Clojure (loaded via Babashka + JVM):**
- `cheshire` - JSON encoding/decoding (Neo4j wire protocol is JSON over HTTP)
- `clojure.java.shell` - subprocess for `npx @playwright/cli`
- `java.net.http.HttpClient` (JDK built-in) - HTTP POST to Neo4j transaction endpoint
- `java.util.UUID`, `java.time.Duration`, `java.util.Base64` (JDK built-ins)

**Node.js/npm (runtime dependency, not in package.json):**
- `@playwright/cli` - headless Chromium browser automation for scraping Steam community pages
- Installed via: `npx @playwright/cli install-browser`

**Runtime Infrastructure:**
- `neo4j:5` (Docker image) - Graph database, accessed via HTTP transaction endpoint (port 7474/7687)

## Configuration

**Environment (.env):**
- File: `.env` (gitignored, exists locally)
- Variables: `NEO4J_AUTH`, `NEO4J_URI`, `NEO4J_TX_URL`, `STEAM_API_KEY`
- `.env.example` checked in as template

**Babashka:**
- `bb.edn` - load path config only (`{:paths ["src" "."]}`)

**Docker:**
- `compose.yml` - Neo4j container service definition

**Python:**
- `pyproject.toml` - project metadata + dependencies + CLI script entry point
- `uv.lock` - lockfile for reproducible installs
- `.python-version` - Python 3.12+ pin

## Platform Requirements

**Development:**
- macOS/Linux (shell scripts, path separators)
- Babashka (`bb`)
- Python 3.12+ with `uv`
- Node.js (for `npx playwright/cli`)
- Docker / Docker Compose (for Neo4j)

**Production:**
- CLI tool run as scripts (`bb *.bb.clj`, `python main.py`)
- Neo4j must be accessible via HTTP (7474) and Bolt (7687)
- Playwright CLI with Chromium browser for scraping

---

*Stack analysis: 2026-03-27*

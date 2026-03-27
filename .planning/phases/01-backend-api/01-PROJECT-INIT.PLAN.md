---
phase: 01-backend-api
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - backend/__init__.py
  - backend/config.py
  - backend/neo4j_client.py
  - backend/app.py
  - backend/pyproject.toml
  - .env.example
autonomous: true
requirements:
  - API-01
  - API-08
must_haves:
  truths:
    - "Backend runs with `uvicorn backend.app:app` and responds to HTTP requests"
    - "All Neo4j credentials come from environment variables only, never hardcoded"
    - "CORS is configured to allow frontend origin in development"
  artifacts:
    - path: "backend/config.py"
      provides: "Pydantic settings for NEO4J_URI, NEO4J_AUTH env vars"
      min_lines: 15
    - path: "backend/neo4j_client.py"
      provides: "Async httpx wrapper for Neo4j HTTP TX endpoint"
      min_lines: 30
    - path: "backend/app.py"
      provides: "FastAPI app skeleton with CORS, JSON response, error handlers"
      min_lines: 30
  key_links:
    - from: "backend/app.py"
      to: "backend/neo4j_client.py"
      via: "import and dependency injection"
    - from: "backend/neo4j_client.py"
      to: "backend/config.py"
      via: "reads NEO4J_TX_URL and NEO4J_AUTH"
    - from: "backend/app.py"
      to: "NEO4J HTTP endpoint"
      via: "neo4j_client module"
---

<objective>
Bootstrap the FastAPI backend project: project structure, Python dependencies, Neo4j HTTP client (httpx), environment config (pydantic-settings), and FastAPI app skeleton with CORS and error handlers. This is the foundation that all subsequent endpoint tasks depend on.
</objective>

<context>
@src/steam_workshop/neo4j.clj          # Reference: existing Neo4j HTTP call pattern (http-post-json, split-auth, basic-auth-header)
@src/steam_workshop/dotenv.clj        # Reference: existing env loading pattern (priority: system env > .env file)
@.env.example                         # Reference: existing env var names (NEO4J_URI, NEO4J_AUTH, NEO4J_TX_URL)
@pyproject.toml                       # Reference: existing Python dependency declarations
</context>

<interfaces>
<!-- Key types and contracts the executor needs. Extracted from codebase. -->

From existing neo4j.clj (pattern to replicate in Python with httpx):
```clojure
;; HTTP POST to Neo4j TX endpoint with Basic Auth
;; POST https://host:7474/db/neo4j/tx/commit
;; Body: {"statements": [{"statement": "...", "parameters": {...}}]}
;; Response: {"results": [{"columns": [...], "data": [...] }], "errors": [...]}
```

From dotenv.clj pattern:
```
Env priority: System env > .env file
NEO4J_AUTH format: "user/password"
NEO4J_TX_URL fallback: derive from NEO4J_URI if not set
```

Existing env vars:
- NEO4J_AUTH=neo4j/你的密码
- NEO4J_URI=bolt://localhost:7687
- NEO4J_TX_URL=http://localhost:7474/db/neo4j/tx/commit
</interfaces>

<tasks>

<task type="auto">
  <name>Task 1: Create backend project scaffold</name>
  <files>backend/__init__.py, backend/pyproject.toml</files>
  <action>
    Create `backend/` directory with `__init__.py` (empty).

    Create `backend/pyproject.toml`:
    ```toml
    [project]
    name = "steam-workshop-api"
    version = "0.1.0"
    requires-python = ">=3.12"
    dependencies = [
        "fastapi>=0.115.0",
        "uvicorn[standard]>=0.30.0",
        "httpx>=0.27.0",
        "pydantic-settings>=2.0.0",
    ]
    ```

    Note: Do NOT add `neo4j` Python driver — use httpx for HTTP calls to Neo4j (same approach as existing Clojure code). `requests` is also NOT needed — use `httpx` only.
  </action>
  <verify>
    <automated>grep -q "httpx" backend/pyproject.toml && grep -q "pydantic-settings" backend/pyproject.toml && echo "OK"</automated>
  </verify>
  <done>backend/pyproject.toml exists with fastapi, uvicorn, httpx, pydantic-settings; backend/__init__.py exists</done>
</task>

<task type="auto">
  <name>Task 2: Implement Neo4j HTTP client</name>
  <files>backend/neo4j_client.py</files>
  <read_first>src/steam_workshop/neo4j.clj</read_first>
  <action>
    Create `backend/neo4j_client.py` implementing async HTTP client for Neo4j transaction endpoint.

    Implementation requirements:
    - Use `httpx.AsyncClient` for async HTTP POST calls
    - `Settings` class (pydantic-settings) reads from env vars: `NEO4J_URI`, `NEO4J_AUTH`, `NEO4J_TX_URL`
    - `derive_tx_url(uri_str)`: if NEO4J_TX_URL is not set, derive it as `http://{host}:7474/db/neo4j/tx/commit` (same logic as existing `derive-tx-url` in neo4j.clj)
    - `split_auth(auth_str)`: parse "user/password" -> (user, pass)
    - `basic_auth_header(user, pass)`: Base64 encode "user:pass", return "Basic ..." string (same as existing `basic-auth-header` in neo4j.clj)
    - `post_statement(statement, parameters)`: POST JSON to NEO4J_TX_URL with Basic Auth, return parsed JSON response
    - `run_query(statement, parameters)`: call post_statement, raise `Neo4jError` on non-empty errors list, return `{"columns": [...], "data": [...]}`
    - `Neo4jError` exception class with `errors` and `statement` attributes
    - Connection timeout: 20 seconds; request timeout: 60 seconds

    Do NOT import the Clojure neo4j.clj — implement in Python only.
  </action>
  <verify>
    <automated>grep -q "class Settings" backend/neo4j_client.py && grep -q "httpx.AsyncClient" backend/neo4j_client.py && grep -q "class Neo4jError" backend/neo4j_client.py && echo "OK"</automated>
  </verify>
  <done>neo4j_client.py has Settings (reads NEO4J_URI, NEO4J_AUTH, NEO4J_TX_URL), split_auth, basic_auth_header, post_statement, run_query, Neo4jError — all using httpx</done>
</task>

<task type="auto">
  <name>Task 3: Create FastAPI app skeleton</name>
  <files>backend/app.py</files>
  <read_first>backend/neo4j_client.py, backend/config.py</read_first>
  <action>
    Create `backend/app.py` with FastAPI application skeleton.

    Implementation requirements:
    - Import `FastAPI`, `CORSMiddleware` from fastapi
    - Import `Settings` from `backend.config` (or define inline if Task 2 not ready — use backend.neo4j_client if config is there)
    - Import `neo4j_client` module
    - Configure CORS with `allow_origins=["*"]` in dev (can be tightened in deployment phase)
    - Add custom exception handler for `neo4j_client.Neo4jError` that returns HTTP 500 with JSON `{"detail": "Neo4j error: <message>"}`
    - Create `get_neo4j()` async dependency that yields the neo4j_client module instance
    - Add `GET /api/health` endpoint returning `{"status": "ok", "timestamp": "<ISO8601>"}`
    - Include docstring comment: `# Backend API — FastAPI proxy over Neo4j (read-only)`
    - `uvicorn backend.app:app` must start without errors
  </action>
  <verify>
    <automated>grep -q "CORSMiddleware" backend/app.py && grep -q "api/health" backend/app.py && grep -q "Neo4jError" backend/app.py && echo "OK"</automated>
  </verify>
  <done>FastAPI app starts, /api/health returns 200 with JSON, Neo4jError maps to 500, CORS middleware active</done>
</task>

</tasks>

<verification>
- `cd backend && uv sync` completes without error
- `uvicorn backend.app:app --port 8000` starts and `curl http://localhost:8000/api/health` returns 200
- `grep -q "httpx" backend/neo4j_client.py` passes
- No hardcoded Neo4j credentials anywhere in backend/ directory
</verification>

<success_criteria>
Backend project scaffold is runnable. `uvicorn backend.app:app` starts on port 8000. `GET /api/health` returns HTTP 200 with `{"status": "ok", "timestamp": "..."}`. Neo4j credentials are read from environment variables only. CORS is configured.
</success_criteria>

<output>
After completion, create `.planning/phases/01-backend-api/01-PROJECT-INIT-SUMMARY.md`
</output>

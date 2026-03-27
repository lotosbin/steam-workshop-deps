# Codebase Concerns

**Analysis Date:** 2026-03-27

## Tech Debt

**Custom argument parsing duplicated across every .bb.clj script:**
- Issue: Each of the 4 top-level babashka scripts (`steam_import_neo4j.bb.clj`, `steam_import_single_neo4j.bb.clj`, `steam_query_neo4j.bb.clj`, `steam_fetch_workshop_info.bb.clj`) re-implements `parse-args` and `getenv*` identically. `usage!` is also duplicated in two of them.
- Files: `steam_import_neo4j.bb.clj`, `steam_import_single_neo4j.bb.clj`, `steam_query_neo4j.bb.clj`, `steam_fetch_workshop_info.bb.clj`
- Fix approach: Extract into a shared `steam-workshop.cli` namespace in `src/steam_workshop/`.

**No test coverage:**
- Issue: Zero test files exist in the repository. All logic (parsers, Neo4j operations, importers, CLI scripts) is untested.
- Files: Entire codebase
- Fix approach: Add `clojure.test` or `kaocha` tests; at minimum cover arg parsing, `_parse_kv_text`, `_parse_list_like`, `obsolete-title?`, and Neo4j query helpers.

**Bare metal HTTP client in two places:**
- Issue: Both `neo4j.clj` and `importer.clj` independently build `HttpClient` instances instead of sharing a single configured client (e.g., with retry logic or connection pool settings).
- Files: `src/steam_workshop/neo4j.clj`, `src/steam_workshop/importer.clj`
- Fix approach: Define a single shared HTTP client in `neo4j.clj` and remove the duplicate in `importer.clj`.

**Unbounded cache growth in importer:**
- Issue: The `details-cache` atom in `import-seeds!` accumulates every fetched item's dependency list for the entire run with no eviction. For large imports this will OOM.
- Files: `src/steam_workshop/importer.clj` (line ~194)
- Fix approach: Use a bounded LRU cache or clear entries after use.

**Hardcoded Neo4j HTTP port:**
- Issue: `derive-tx-url` in `neo4j.clj` always uses `http://{host}:7474` regardless of the scheme/port in `NEO4J_URI`. This breaks when Neo4j runs on a non-standard port.
- Files: `src/steam_workshop/neo4j.clj` (line 42-47)
- Fix approach: Parse the URI properly and use the actual port, or document that `NEO4J_TX_URL` must be set explicitly.

**Mixing JSON and JSON-like string parsing:**
- Issue: `cheshire.core` (`json/parse-string`) is used to parse JSON returned by Playwright CLI, but then the raw text is sometimes re-parsed as Clojure data with `json/parse-string` again. This creates confusing double-parse patterns in `workshop.clj` (line 60-61) and `importer.clj` (line 98-99).
- Files: `src/steam_workshop/workshop.clj`, `src/steam_workshop/importer.clj`
- Fix approach: Normalize to a single parse step.

---

## Known Bugs

**`detect-kind` makes 3 sequential Neo4j queries instead of one:**
- Symptom: Slow startup when querying a node. Each `detect-kind` call fires 3 separate HTTP POSTs to Neo4j.
- Files: `steam_query_neo4j.bb.clj` (line 148-153)
- Fix approach: Use a single Cypher query with `OPTIONAL MATCH` for all three node types.

**Positional argument mixed with `--` options causes confusing errors:**
- Symptom: In `steam_import_single_neo4j.bb.clj` (line 51), if a positional arg is followed by a `--` option, the positional may be consumed incorrectly. The condition `(nil? (:id opts)) (nil? (:url opts))` allows a bare numeric string to be treated as `--id`, but this overlaps with `--url`.
- Files: `steam_import_single_neo4j.bb.clj` (line 51)
- Trigger: `bb steam_import_single_neo4j.bb.clj 3689745069 --max-depth 3`
- Workaround: Always use `--id` explicitly.

**Missing error handling for `parse-line` in dotenv:**
- Symptom: Malformed `.env` lines (e.g., missing `=` sign) are silently skipped. This makes misconfigured environments hard to debug.
- Files: `src/steam_workshop/dotenv.clj` (line 4-13)
- Fix approach: Log or warn on unparseable lines.

---

## Security Considerations

**Neo4j credentials in `.env` file (not in .gitignore checked):**
- Risk: If `.env` is accidentally committed, Neo4j credentials are exposed. The `.env` file exists in the project root but is not listed in any `.gitignore` shown.
- Files: `.env`
- Current mitigation: None
- Recommendations: Add `.env` to `.gitignore` explicitly; document that secrets must come from environment, not the file.

**Basic Auth sends credentials in every HTTP request:**
- Risk: Neo4j username/password is sent Base64-encoded (not encrypted) on every request. Over HTTP this is plaintext.
- Files: `src/steam_workshop/neo4j.clj`
- Current mitigation: Assumes localhost or TLS
- Recommendations: Document that `NEO4J_URI` / `NEO4J_TX_URL` must use `https://` in production; validate TLS in production.

**Steam API key stored in environment:**
- Risk: `STEAM_API_KEY` environment variable is used by `main.py` for `requests.get`. If the environment is shared or logged, the key can leak.
- Files: `main.py`
- Recommendations: Warn users not to log or print API key; add masking in error messages.

**Hardcoded `npx` call for Playwright CLI:**
- Risk: `playwright-cli` is executed via shell with no version pinning. A breaking change in a future version of `@playwright/cli` could silently break the scraper.
- Files: `src/steam_workshop/playwright_cli.clj`
- Recommendations: Pin to a specific version in package.json or use a checked-in binary instead of `npx`.

---

## Performance Bottlenecks

**Sequential browser-based scraping with no parallelism:**
- Problem: `import-seeds!` fetches each Steam workshop page one at a time through Playwright CLI. A single import with 300 nodes could take 300 * (page load time + sleep) seconds.
- Files: `src/steam_workshop/importer.clj` (BFS loop, line 203-261)
- Improvement path: Use Playwright's multi-session or async API (if available) to fetch multiple pages concurrently.

**Coarse-grained `Thread/sleep` rate limiting:**
- Problem: `Thread/sleep sleep-ms` (line 221) applies uniformly to every request, even fast responses. This is the simplest possible rate limiter but is inefficient.
- Files: `src/steam_workshop/importer.clj`
- Improvement path: Implement a token-bucket or sliding-window rate limiter to maximize throughput while respecting Steam's limits.

**BFS makes a Neo4j query per `recent-mod-ids` batch:**
- Problem: Every loop iteration calls `recent-mod-ids` which fires a Neo4j query. With thousands of nodes, this means thousands of HTTP round-trips to Neo4j.
- Files: `src/steam_workshop/importer.clj` (lines 184, 229)
- Improvement path: Cache recent IDs at import start or use a single batched query.

**Workshop browse page scraping via regex on raw HTML:**
- Problem: `fetch-seed-ids` fetches raw HTML and uses a regex to extract IDs. This is slow (full HTML) and fragile.
- Files: `src/steam_workshop/importer.clj` (lines 109-117)
- Improvement path: Use Playwright CLI's `eval!` to extract IDs from the DOM, consistent with other scraping.

---

## Fragile Areas

**DOM selector strings for Steam community pages:**
- Why fragile: `extract-json-script` in `workshop.clj` and both collection/workshop list scripts in `importer.clj` use CSS selectors like `.collectionChildren`, `.detailsStatLeft`, `.detailsStatRight`, `.friendBlockContent`, `.panel`, `.workshopBrowseItems .workshopItem`. Steam regularly redesigns their community pages and these selectors will break silently.
- Files: `src/steam_workshop/workshop.clj` (lines 7-31), `src/steam_workshop/importer.clj` (lines 40-68)
- Safe modification: Add a smoke test that fetches a known workshop item and validates the output structure; fail fast if critical fields are nil.
- Test coverage: None

**HTML regex scraping in `fetch-seed-ids`:**
- Why fragile: `extract-browse-ids` uses `re-seq` on raw HTML. Any change to Steam's browse page HTML breaks seed discovery.
- Files: `src/steam_workshop/importer.clj` (lines 32-38)
- Safe modification: Migrate to Playwright DOM evaluation (consistent with `extract-workshop-list-ids!`).

**`mod.info` / `workshop.txt` parsing assumes fixed structure:**
- Why fragile: `_parse_kv_text` splits on first `=` and ignores whitespace. Mod files with unusual formatting (trailing spaces, unusual line endings, BOM) may not parse correctly.
- Files: `main.py` (lines 48-66)
- Test coverage: None; edge cases not covered.

**Playwright CLI output parsing via regex:**
- Why fragile: `extract-result` in `playwright_cli.clj` uses `re-find` to extract content between `### Result` and `###` markers. If Playwright CLI changes its output format, this silently returns nil.
- Files: `src/steam_workshop/playwright_cli.clj` (lines 35-39)
- Safe modification: Validate that `extract-result` returns non-nil before passing to `json/parse-string`.

---

## Scaling Limits

**In-memory BFS state:**
- Current capacity: All visited nodes, queue, buffers are held in atoms (`visited`, `queue`, `node-buf`, `edge-buf`, `author-buf`, `authored-edge-buf`, `details-cache`). With `max-nodes=300` this is manageable, but the design does not scale beyond a few thousand.
- Limit: Memory exhaustion on large imports
- Scaling path: Stream batches to disk or use a pipeline process model instead of loading all state into memory.

**Single-threaded Neo4j writer:**
- Current capacity: One HTTP connection writing to Neo4j
- Limit: Write throughput capped by single HTTP connection latency
- Scaling path: Partition import across multiple processes or threads.

**No pagination handling in `recent-mod-ids`:**
- Current capacity: Single Cypher query with `WHERE m.id IN $ids`
- Limit: Neo4j has a parameter size limit (~2MB per transaction). Very large `$ids` lists will fail.
- Scaling path: Chunk the IDs and make multiple queries.

---

## Dependencies at Risk

**`@playwright/cli` (npx):**
- Risk: No version constraint. `npx @playwright/cli` always resolves to latest.
- Impact: A breaking change to the CLI's `open`, `goto`, `eval`, `close`, or `s=` flag syntax silently breaks all scraping.
- Migration plan: Pin to a specific version: `npx @playwright/cli@1.x.x`.

**Steam community page HTML structure:**
- Risk: Not a package but an external service. CSS class names and page structure are not versioned and can change at any time.
- Impact: All DOM-based scraping (`extract-json-script`, list ID extractors) returns empty or wrong data.
- Migration plan: Monitor scraping results; when title/description fields become nil, the page structure has likely changed.

**`cheshire` (JSON parsing):**
- Risk: Stable library, low risk. However, double-parsing (JSON string -> Clojure map -> JSON string -> Clojure map) adds overhead and potential for subtle bugs.
- Impact: Performance degradation in large imports.
- Migration plan: Remove double-parse by keeping data in Clojure maps from the start.

---

## Missing Critical Features

**Idempotent imports:**
- Problem: Re-running an import creates duplicate nodes/edges or overwrites `imported_at` without preserving history. No upsert semantics based on Steam's `updated` timestamp.
- Blocks: Incremental sync / delta imports.

**No retry / circuit breaker on network calls:**
- Problem: A single HTTP timeout or Playwright CLI failure aborts the entire import.
- Blocks: Reliable large-scale imports over unstable connections.

**No CLI progress reporting:**
- Problem: Imports run silently with only `println` statements. For long-running imports there is no ETA, percentage, or checkpoint resume.
- Blocks: Usability for non-interactive environments.

---

## Test Coverage Gaps

**Untested: Arg parsing in all 4 babashka scripts:**
- What's not tested: All `parse-args` functions accept invalid inputs, unknown flags, missing required args.
- Files: All `*.bb.clj` files
- Risk: Invalid user input causes confusing exceptions rather than friendly error messages.

**Untested: `main.py` CLI subcommands:**
- What's not tested: `tree`, `reverse`, `cycles` subcommands with valid and invalid inputs.
- Files: `main.py`
- Risk: Broken CLI silently prints nothing or crashes.

**Untested: Neo4j query functions:**
- What's not tested: `query!`, `rows`, `recent-mod-ids`, `post-statement!` with success/error responses.
- Files: `src/steam_workshop/neo4j.clj`
- Risk: API changes in Neo4j response format silently break queries.

**Untested: `parse_zomboid_mod_info` and `parse_workshop_txt`:**
- What's not tested: Empty files, binary files, files with unusual encodings, complex dependency list formats.
- Files: `main.py`
- Risk: Mods with non-standard `mod.info` format are silently skipped.

**Untested: `find_cycles_from_root` and cycle deduplication:**
- What's not tested: Multiple overlapping cycles, deeply nested cycles, self-referential mods.
- Files: `main.py`
- Risk: Cycles are not reported correctly.

---

*Concerns audit: 2026-03-27*

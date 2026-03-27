# Coding Conventions

**Analysis Date:** 2026-03-27

## Languages

**Primary - Clojure (Babashka):**
- Version: Babashka (latest)
- Used for: All core business logic, CLI scripts, scraping, Neo4j operations
- Entry points: `*.bb.clj` top-level scripts

**Secondary - Python:**
- Version: Python 3.12+
- Used for: CLI entry point (`main.py`), local mod.info parsing, tree/cycle/reverse queries
- Run via: `source .venv/bin/activate && workshop-deps <cmd>`

## Clojure Conventions

### Namespace Declaration

Use `ns` with `:require` for imports. Alias external libs with `:as`, qualify internal modules:

```clojure
(ns steam-workshop.neo4j
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [steam-workshop.dotenv :as dotenv])
  (:import [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.net URI]
           [java.time Duration]
           [java.util Base64]))
```

- External libraries: `:as json`, `:as str`
- Internal modules: `:as neo4j`, `:as dotenv`
- Java stdlib: `:import` block with fully-qualified class names

### Naming Conventions

**Functions and vars:** `kebab-case` (e.g., `http-get-str`, `basic-auth-header`, `split-auth`)

**Namespaces:** `kebab-case` (e.g., `steam-workshop.neo4j`, `steam-workshop.dotenv`)

**Files:** `kebab-case.clj` (e.g., `playwright_cli.clj`, `dotenv.clj`)

**Constants (def without args):** `kebab-case` with docstring (e.g., `node-statement`, `edge-statement`)

**Java interop methods:** Use `.method` dot notation (e.g., `(.send http-client req ...)`)

**Atom/deref vars:** Use `(swap! ...)`, `(reset! ...)`, `(deref @atom)` pattern

### Formatters

**Parentheses:** Clojure standard - parens on their own line, closing parens aligned with opening:

```clojure
(defn http-get-str [^String url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.GET)
                (.timeout (Duration/ofSeconds 60))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    ...))
```

**Threading:** Prefer `->` (thread-first) and `->>` (thread-last) for readability:

```clojure
(->> (re-seq re html)
     (map second)
     (filter #(and % (re-matches #"\d+" %)))
     distinct
     vec)
```

**Cond->:** Use for conditional assoc in maps:

```clojure
(cond-> {:source "steamcommunity-playwright-cli"
        :workshop_id (str id)
        :obsolete (obsolete-title? (:title info))}
  info (assoc :imported_at (System/currentTimeMillis))
  (:title info) (assoc :title (:title info)))
```

### Comments

- Use `;;` for comments (standard Lisp style)
- Top-level `.bb.clj` scripts include usage header comment:

```clojure
#!/usr/bin/env bb
;; Babashka 脚本：说明文字
;;
;; 用法：
;;   bb script.bb.clj --arg value
```

- Source files use `;; Description` inline comments before functions when needed

## Python Conventions

### Imports

Standard library first, third-party next, project modules last:

```python
from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import requests
```

### Type Hints

Use `from __future__ import annotations` and full type hints:

```python
def _parse_kv_text(text: str) -> Dict[str, str]:
def _parse_list_like(value: str) -> List[str]:
def build_dependency_tree(
    root_internal_id: str,
    items_by_internal_id: Dict[str, WorkshopItem],
    max_depth: int,
) -> TreeNode:
```

### Naming Conventions

**Functions and variables:** `snake_case` (e.g., `parse_zomboid_mod_info`, `build_reverse_edges`)

**Classes:** `PascalCase` (e.g., `WorkshopItem`, `TreeNode`)

**Files:** `snake_case.py` (e.g., `main.py`)

**Constants:** `SCREAMING_SNAKE_CASE` (e.g., `STEAM_WEB_API_BASE`, `STEAM_API_KEY_ENV`)

**Private helpers:** Leading underscore (e.g., `_strip_quotes`, `_parse_kv_text`)

### Dataclasses

Use `@dataclass` for data containers:

```python
@dataclass(frozen=True)
class WorkshopItem:
    internal_id: str
    published_id: str
    title: str
    description: Optional[str]
    dependencies: List[str]
    author: Optional[str] = None

@dataclass
class TreeNode:
    internal_id: str
    title: str
    published_id: Optional[str]
    children: List["TreeNode"]
    depth: int
    is_circular: bool = False
    missing: bool = False
```

- Use `frozen=True` for immutable data
- Use mutable `@dataclass` for recursive/tree structures

### Error Handling

**Python:** Raise exceptions with descriptive messages, handle gracefully:

```python
if not workshop_dir.exists():
    raise FileNotFoundError(f"workshop_dir 不存在: {workshop_dir}")

def get_dependent_mods_from_steam_web_api(target_mod_id: str) -> List[str]:
    api_key = os.getenv(STEAM_API_KEY_ENV, "").strip()
    if not api_key:
        raise RuntimeError(f"缺少 Steam API Key：请设置环境变量 {STEAM_API_KEY_ENV}")
```

**Clojure:** Use `ex-info` with data maps:

```clojure
(throw (ex-info "HTTP GET failed"
                {:url url
                 :status status
                 :body (subs body 0 (min 500 (count body)))}))

(throw (ex-info "缺少必需环境变量 NEO4J_AUTH"
                {:env "NEO4J_AUTH"}))
```

## CLI Scripts

### Babashka CLI Pattern

Top-level scripts follow this structure:

```clojure
#!/usr/bin/env bb
;; Description
;;
;; Usage:
;;   bb script.bb.clj --arg value

(require '[clojure.string :as str]
         '[steam-workshop.dotenv :as dotenv]
         '[steam-workshop.neo4j :as neo4j])

;; Load .env relative to script
(def script-dir
  (.getParent (java.io.File. *file*)))
(def dotenv-env
  (dotenv/load-file-map (str script-dir "/.env")))

(defn getenv* [k default]
  (dotenv/getenv dotenv-env k default))

(defn usage! []
  (binding [*out* *err*]
    (println "用法: ...")
    (System/exit 1)))

(defn parse-args [argv]
  ...)

(defn -main [& argv]
  (let [opts (parse-args argv)]
    ...))

(apply -main *command-line-args*)
```

### Python CLI Pattern

Use `argparse` with subparsers:

```python
def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="workshop-deps",
        description="...")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_tree = sub.add_parser("tree", help="...")
    p_tree.add_argument("--workshop-dir", required=True, type=Path, ...)
    p_tree.add_argument("--root", required=True, ...)

    args = parser.parse_args(argv)

    if args.cmd == "tree":
        ...
        return 0

    return 2

if __name__ == "__main__":
    raise SystemExit(main())
```

## Logging

**Clojure:** Use `println` for progress output (no structured logging library):

```clojure
(println "browse-url=" url)
(println "POST nodes batch, count=" (count nodes))
(println "done")
(println "visited nodes=" (count @visited))
```

**Output streams:**
- Normal output: `*out*`
- Errors/usage: `(binding [*out* *err*] (println ...))`

**Python:** Print directly:

```python
print(f"Cycles reachable from root: {len(cycles)}")
print(f"Local reverse-deps: {len(dependers)} 个（target={...}）")
```

## Configuration

**Environment variables:**
- Clojure: Custom `.env` parser in `src/steam_workshop/dotenv.clj`
- `.env` in project root, loaded relative to script location via `*file*`
- Never commit `.env` - use `.env.example` for template

**Babashka config:**
- `bb.edn`: `{:paths ["src" "."]}` - adds `src/` and root to load path

**Python deps:**
- `pyproject.toml` with `[project.scripts]` entry point

## Pattern Examples

### Filtering and Transforming Sequences

Clojure threading pattern:

```clojure
(->> (re-seq re html)
     (map second)
     (filter #(and % (re-matches #"\d+" %)))
     distinct
     vec)
```

### Safe Optional Extraction

```clojure
(some->> (re-find #"(?s)### Result\s+(.*?)\s+###" (or s ""))
         second
         str/trim
         not-empty)

(some-> data :author_profile_url not-empty)
```

### Validation with Throws

```clojure
(when (empty? seed-ids)
  (println "没有可导入的 seed ids")
  (throw (ex-info "empty seed ids" {:opts opts})))
```

### Guard with Default

```clojure
(defn tx-url [env-map]
  (or (dotenv/getenv env-map "NEO4J_TX_URL" nil)
      (derive-tx-url (dotenv/getenv env-map "NEO4J_URI" nil))
      "http://localhost:7474/db/neo4j/tx/commit"))
```

### Recursive Tree Building

Python nested function pattern:

```python
def build_dependency_tree(
    root_internal_id: str,
    items_by_internal_id: Dict[str, WorkshopItem],
    max_depth: int,
) -> TreeNode:
    def rec(current_id: str, depth: int, path_on_stack: List[str]) -> TreeNode:
        if current_id in path_on_stack:
            # circular dependency detected
            ...
        item = items_by_internal_id.get(current_id)
        if not item:
            ...
        if depth >= max_depth:
            ...
        node = TreeNode(...)
        for dep_id in item.dependencies:
            node.children.append(rec(dep_id, depth + 1, new_path))
        return node
    return rec(root_internal_id, 0, [])
```

## File Locations

**Clojure shared lib:** `src/steam_workshop/<module>.clj`

**Babashka CLI:** `*.bb.clj` in project root

**Python CLI:** `main.py` in project root

**Config:** `bb.edn`, `pyproject.toml`, `.env.example`

---

*Convention analysis: 2026-03-27*

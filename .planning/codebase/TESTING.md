# Testing Patterns

**Analysis Date:** 2026-03-27

## Test Framework

**No formal test framework detected.**

The codebase has no test files, test directories, or test configuration. This is a critical gap.

### Current State

- No `*.test.clj`, `*_test.clj`, or `test/` directory for Clojure
- No `test_*.py`, `*_test.py`, or `tests/` directory for Python
- No `jest.config.*`, `vitest.config.*`, `pytest.ini`, `bb.edn` test configuration

### Implications

All validation is performed manually:
- Clojure scripts run via `bb <script>.bb.clj <args>`
- Python CLI tested via `workshop-deps <cmd> --workshop-dir <path> --root <id>`
- Neo4j data verified via `steam_query_neo4j.bb.clj --id <node-id>`

## Testing Recommendations

### For Clojure (Babashka)

Add a `test/` directory with Babashka-compatible test structure:

```
test/
├── steam_workshop/
│   ├── dotenv_test.clj
│   ├── neo4j_test.clj
│   ├── workshop_test.clj
│   └── importer_test.clj
└── runner.clj
```

Example test file pattern:

```clojure
(ns steam-workshop.dotenv-test
  (:require [clojure.test :refer [deftest is testing]]
            [steam-workshop.dotenv :as dotenv]))

(deftest parse-line-test
  (testing "空行和注释"
    (is (nil? (dotenv/parse-line "")))
    (is (nil? (dotenv/parse-line "   ")))
    (is (nil? (dotenv/parse-line "# comment")))
    (is (nil? (dotenv/parse-line "// comment"))))

  (testing "有效键值对"
    (is (= ["KEY" "value"] (dotenv/parse-line "KEY=value")))
    (is (= ["KEY" "value with spaces"] (dotenv/parse-line "KEY=value with spaces")))
    (is (= ["KEY" "value"] (dotenv/parse-line " KEY = value "))))

  (testing "引号去除"
    (is (= ["KEY" "value"] (dotenv/parse-line "KEY=\"value\"")))
    (is (= ["KEY" "value"] (dotenv/parse-line "KEY='value'")))))

(deftest getenv-test
  (let [env-map {"FOO" "bar" "EMPTY" ""}]
    (testing "基础查找"
      (is (= "bar" (dotenv/getenv env-map "FOO")))
      (is (nil? (dotenv/getenv env-map "MISSING"))))

    (testing "默认值"
      (is (= "default" (dotenv/getenv env-map "MISSING" "default")))

    (testing "空值处理"
      (is (= "default" (dotenv/getenv env-map "EMPTY" "default"))))))
```

Run tests with:
```bash
bb -m clojure.test_runner
# or via bb.edn task
```

### For Python

Add a `tests/` directory using `pytest`:

```
tests/
├── __init__.py
├── conftest.py
├── test_parse_kv.py
├── test_dependency_tree.py
└── test_cycles.py
```

Example test file pattern:

```python
from __future__ import annotations

import textwrap
from pathlib import Path
from main import (
    _parse_kv_text,
    _parse_list_like,
    WorkshopItem,
)


def test_parse_kv_text_basic():
    text = textwrap.dedent("""
        name=My Mod
        require=Dep1,Dep2
        author=Kitsune
    """).strip()
    result = _parse_kv_text(text)
    assert result == {
        "name": "My Mod",
        "require": "Dep1,Dep2",
        "author": "Kitsune",
    }


def test_parse_kv_text_skips_comments():
    text = textwrap.dedent("""
        # this is a comment
        name=My Mod
        // also a comment
        require=Dep1
    """).strip()
    result = _parse_kv_text(text)
    assert "name" in result
    assert "#" not in result


def test_parse_list_like_set():
    assert _parse_list_like('{"123","456"}') == ["123", "456"]


def test_parse_list_like_array():
    assert _parse_list_like('["abc","def"]') == ["abc", "def"]


def test_parse_list_like_csv():
    assert _parse_list_like("a,b,c") == ["a", "b", "c"]
```

Run tests with:
```bash
source .venv/bin/activate
pytest tests/ -v
```

## What Should Be Tested

### High Priority (Clojure)

**`src/steam_workshop/dotenv.clj`:**
- `parse-line` - empty lines, comments, quotes, whitespace
- `load-file-map` - file with/without .env
- `getenv` - found/missing/blank values

**`src/steam_workshop/neo4j.clj`:**
- `split-auth` - valid/invalid/blank input
- `derive-tx-url` - valid URI / blank / nil
- `tx-url` - precedence: NEO4J_TX_URL > derived > default
- `obsolete-title?` - "obsolete" / "OBSOLETE" / "deprecated" / normal titles
- `node-row`, `edge-row`, `collection-row`, `author-row` - with/without info

**`src/steam_workshop/workshop.clj`:**
- `extract-id-from-url` - various URL formats
- `extract-author-id-from-url` - profiles vs id URLs
- `workshop-id` - prefers :id over URL extraction

### High Priority (Python)

**`main.py` pure functions:**
- `_parse_kv_text` - all comment styles, edge cases
- `_parse_list_like` - set notation, array, CSV, quoted values
- `parse_zomboid_mod_info` - missing file, partial data, all deps fields
- `parse_workshop_txt` - same as above
- `build_dependency_tree` - circular detection, missing deps, depth limit
- `find_cycles_from_root` - no cycles, single cycle, multiple cycles
- `build_reverse_edges` - empty, single, multiple reverse deps

### Integration Testing

**Playwright CLI integration:**
- Mock subprocess calls to `npx @playwright/cli` with predefined stdout
- Test `extract-result` parsing with various output formats

**Neo4j integration:**
- Use test containers or mock HTTP responses
- Test `post-statement!` and `query!` with valid/invalid JSON

## Mocking Patterns

### Clojure (with `clojure.test`)

```clojure
(deftest test-with-mocked-env
  (let [env-map {"NEO4J_AUTH" "neo4j/secret"}]
    (is (= "neo4j" (first (neo4j/split-auth (get env-map "NEO4J_AUTH")))))))
```

### Python (with `unittest.mock` or `pytest-mock`)

```python
from unittest.mock import patch, MagicMock

def test_get_dependent_mods_missing_key():
    with patch.dict("os.environ", {}, clear=True):
        with pytest.raises(RuntimeError, match="缺少 Steam API Key"):
            get_dependent_mods_from_steam_web_api("123")
```

## Test Fixtures

### Clojure

```clojure
(def sample-mod-info
  "id=ExampleMod
   name=Example Mod
   author=TestAuthor
   require=Dep1,Dep2
   description=Test mod")

(def sample-workshop-item
  {:internal_id "ExampleMod"
   :published_id "1234567890"
   :title "Example Mod"
   :dependencies ["Dep1" "Dep2"]})
```

### Python

```python
@pytest.fixture
def sample_mod_info(tmp_path):
    mod_dir = tmp_path / "1234567890"
    mod_dir.mkdir()
    (mod_dir / "mod.info").write_text("""id=ExampleMod
name=Example Mod
require=Dep1,Dep2""")
    return tmp_path
```

## Validation Before Commit

Per `AGENTS.md`:
- Run narrowest relevant validation first
- Use existing scripts to test affected areas
- If validation cannot run, note it in handoff

Current validation options:
```bash
# Clojure syntax check
bb --version
bb -e "(load-file 'src/steam_workshop/dotenv.clj')"

# Python syntax check
source .venv/bin/activate
python -m py_compile main.py
flake8 main.py

# Manual smoke test
bb steam_fetch_workshop_info.bb.clj --id 3688270372
```

## Coverage

**No coverage requirements enforced.**

If tests are added, target:
- Clojure: Pure utility functions (dotenv, parsing, string manipulation) - aim for 90%+
- Clojure: Imperative I/O functions (HTTP, Neo4j, Playwright) - harder to unit test, prefer integration tests
- Python: Pure functions - aim for 95%+
- Python: CLI arg parsing and error paths - aim for 90%+

---

*Testing analysis: 2026-03-27*

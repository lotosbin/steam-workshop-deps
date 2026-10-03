"""Async HTTP client for Neo4j transaction endpoint.

Mirrors the pattern from src/steam_workshop/neo4j.clj (Clojure) but in Python.
Uses httpx for async HTTP calls to Neo4j HTTP TX endpoint with Basic Auth.
"""
from __future__ import annotations

import base64
import os
from typing import Any

import httpx
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Neo4jError(Exception):
    """Raised when a Neo4j query returns errors."""

    def __init__(self, errors: list[dict[str, Any]], statement: str = ""):
        self.errors = errors
        self.statement = statement
        super().__init__(str(errors))


def derive_tx_url(uri_str: str | None) -> str | None:
    """Derive HTTP TX URL from bolt URI.

    Mirrors derive-tx-url in neo4j.clj.
    E.g. bolt://localhost:7687 -> http://localhost:7474/db/neo4j/tx/commit
    """
    if not uri_str:
        return None
    try:
        from urllib.parse import urlparse
        parsed = urlparse(uri_str)
        host = parsed.hostname or parsed.netloc.split(":")[0].split("@")[-1]
        return f"http://{host}:7474/db/neo4j/tx/commit"
    except Exception:
        return None


def split_auth(auth_str: str | None) -> tuple[str, str]:
    """Parse 'user/password' into (user, password).

    Mirrors split-auth in neo4j.clj.
    """
    if not auth_str or "/" not in auth_str:
        return ("", "")
    user, password = auth_str.split("/", 1)
    return (user.strip(), password.strip())


def basic_auth_header(user: str, password: str) -> str:
    """Build Basic Auth header value.

    Mirrors basic-auth-header in neo4j.clj.
    """
    raw = f"{user}:{password}"
    encoded = base64.b64encode(raw.encode("utf-8")).decode("ascii")
    return f"Basic {encoded}"


class Settings(BaseSettings):
    """Neo4j connection settings from environment variables.

    Mirrors the dotenv pattern from steam-workshop.dotenv:
    Priority: System env > .env file (via pydantic-settings).
    """
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    NEO4J_URI: str = Field(default="bolt://localhost:7687")
    NEO4J_AUTH: str = Field(default="")
    NEO4J_TX_URL: str = Field(default="")

    @property
    def tx_url(self) -> str:
        """Return the Neo4j HTTP TX endpoint URL."""
        if self.NEO4J_TX_URL:
            return self.NEO4J_TX_URL
        derived = derive_tx_url(self.NEO4J_URI)
        if derived:
            return derived
        return "http://localhost:7474/db/neo4j/tx/commit"

    @property
    def auth_header(self) -> str:
        """Return the Basic Auth header for Neo4j requests."""
        user, password = split_auth(self.NEO4J_AUTH)
        return basic_auth_header(user, password)


settings = Settings()


async def post_statement(
    statement: str,
    parameters: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """POST a Cypher statement to the Neo4j HTTP TX endpoint.

    Mirrors post-statement! in neo4j.clj.

    Returns the parsed JSON response body.
    Raises httpx.HTTPStatusError on non-2xx responses.
    """
    payload: dict[str, Any] = {
        "statements": [
            {"statement": statement, "parameters": parameters or {}}
        ]
    }
    async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=20.0)) as client:
        response = await client.post(
            settings.tx_url,
            json=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": settings.auth_header,
            },
        )
        response.raise_for_status()
        return response.json()


async def run_query(
    statement: str,
    parameters: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Run a Cypher query and return parsed result.

    Mirrors query! in neo4j.clj.

    Returns: {"columns": [...], "data": [{"row": [...]}, ...]}
    Raises Neo4jError if Neo4j returns errors.
    """
    body = await post_statement(statement, parameters)
    errors: list[dict[str, Any]] = body.get("errors", [])
    if errors:
        raise Neo4jError(errors=errors, statement=statement)
    results: list[dict[str, Any]] = body.get("results", [])
    return results[0] if results else {"columns": [], "data": []}


def rows_as_dicts(result: dict[str, Any]) -> list[dict[str, Any]]:
    """Convert a Neo4j result into row dicts keyed by column name.

    The HTTP transaction endpoint returns rows as *positional* arrays, with the
    column names in a separate ``columns`` list::

        {"columns": ["workshop_id", "title"],
         "data": [{"row": ["123", "Some mod"]}]}

    so reading by alias requires zipping the two together.
    """
    columns = result.get("columns", [])
    return [
        dict(zip(columns, entry.get("row", [])))
        for entry in result.get("data", [])
    ]

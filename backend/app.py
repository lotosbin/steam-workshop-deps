# Backend API — FastAPI proxy over Neo4j (read-only)
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from backend import neo4j_client

app = FastAPI(title="Steam Workshop Graph API", version="0.1.0")

# CORS — allow all origins in development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(neo4j_client.Neo4jError)
async def neo4j_exception_handler(request, exc: neo4j_client.Neo4jError):
    """Map Neo4jError to HTTP 500 with JSON error body."""
    return JSONResponse(
        status_code=500,
        content={"detail": f"Neo4j error: {exc.errors}"},
    )


from fastapi.responses import JSONResponse


# ─── Endpoints ────────────────────────────────────────────────────────────────


@app.get("/api/health")
async def health():
    """Health check — confirms service is running."""
    return {"status": "ok", "timestamp": datetime.now(timezone.utc).isoformat()}


# Additional endpoints are added in Wave 2 (02-API-ENDPOINTS plan)

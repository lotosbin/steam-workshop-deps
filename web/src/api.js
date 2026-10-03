// Fetch wrappers for every /api/* endpoint (UI-SPEC: api.js).
// Same origin: FastAPI serves both the static page and the API in production.

const BASE = "/api";

async function request(path, params) {
  const url = new URL(BASE + path, window.location.origin);
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, value);
    }
  }

  const response = await fetch(url, { headers: { Accept: "application/json" } });

  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.detail) {
        detail =
          typeof body.detail === "string"
            ? body.detail
            : JSON.stringify(body.detail);
      }
    } catch {
      // non-JSON error body — keep the status line
    }
    throw new Error(detail);
  }

  return response.json();
}

export const api = {
  games: () => request("/games"),
  search: (q, game) => request("/mods/search", { q, game }),
  mod: (workshopId) => request(`/mods/${encodeURIComponent(workshopId)}`),
  graph: (workshopId, depth) =>
    request(`/graph/${encodeURIComponent(workshopId)}`, { depth }),
  path: (from, to) => request("/graph/path", { from, to }),
};

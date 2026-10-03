// Single source of truth for the UI (UI-SPEC: state.js).

export const state = {
  games: [],
  game: null,

  query: "",
  results: [],
  resultsStatus: "idle", // idle | loading | done | empty | error

  rootId: null,
  depth: 2,
  layout: "dagre",

  graph: { nodes: [], edges: [], total_nodes: 0, truncated: false },
  graphStatus: "empty", // empty | loading | ready | error
  graphError: null,

  selectedId: null,
  detailStatus: "idle", // idle | loading | ready | error
  detail: null,
  detailError: null,
};

const listeners = new Set();

/** Subscribe to every state change; returns an unsubscribe function. */
export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Shallow-merge a patch into the state and notify subscribers. */
export function setState(patch) {
  Object.assign(state, patch);
  for (const listener of listeners) listener(state);
}

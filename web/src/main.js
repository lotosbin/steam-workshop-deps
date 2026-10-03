// App bootstrap — wires the UI components together (UI-SPEC: main.js).

import { api } from "./api.js";
import { initDepth } from "./depth.js";
import { initDetail, openDetail } from "./detail.js";
import {
  countCyclicComponents,
  initGraph,
  renderGraph,
  resizeGraph,
  runLayout,
  selectNode,
} from "./graph.js";
import { initSearch } from "./search.js";
import { setState, state, subscribe } from "./state.js";

const EMPTY_MESSAGE = "Select a mod to view its dependency graph";
const EMPTY_GRAPH = { nodes: [], edges: [], total_nodes: 0, truncated: false };

const dom = {
  gameSelect: document.getElementById("gameSelect"),
  searchInput: document.getElementById("searchInput"),
  searchResults: document.getElementById("searchResults"),
  depthSlider: document.getElementById("depthSlider"),
  depthLabel: document.getElementById("depthLabel"),
  layoutSelect: document.getElementById("layoutSelect"),
  cy: document.getElementById("cy"),
  overlay: document.getElementById("overlay"),
  overlayMessage: document.getElementById("overlayMessage"),
  truncation: document.getElementById("truncation"),
  statNodes: document.getElementById("statNodes"),
  statEdges: document.getElementById("statEdges"),
  statMods: document.getElementById("statMods"),
  statCycles: document.getElementById("statCycles"),
  detailPanel: document.getElementById("detail"),
  detailBody: document.getElementById("detailBody"),
  detailTitle: document.getElementById("detailTitle"),
  detailObsolete: document.getElementById("detailObsolete"),
  detailClose: document.getElementById("detailClose"),
};

// ─── Graph loading ────────────────────────────────────────────────────────────

async function loadGraph(workshopId, depth) {
  setState({
    rootId: workshopId,
    depth,
    graphStatus: "loading",
    graphError: null,
    selectedId: null,
    detail: null,
    detailStatus: "idle",
  });

  try {
    const graph = await api.graph(workshopId, depth);
    // Ignore responses for a graph the user already navigated away from.
    if (state.rootId !== workshopId) return;
    setState({ graph, graphStatus: "ready" });
    renderGraph(graph, state.layout);
  } catch (error) {
    if (state.rootId !== workshopId) return;
    setState({ graph: EMPTY_GRAPH, graphStatus: "error", graphError: error.message });
    console.error("graph failed", error);
  }
}

function clearGraph() {
  setState({ rootId: null, graph: EMPTY_GRAPH, graphStatus: "empty", graphError: null });
  renderGraph(EMPTY_GRAPH, state.layout);
}

// ─── Games ────────────────────────────────────────────────────────────────────

function renderGames() {
  const options = state.games.map((game) => {
    const option = document.createElement("option");
    option.value = game.app_id;
    option.textContent = `${game.app_id} (${game.mod_count} mods)`;
    return option;
  });
  dom.gameSelect.replaceChildren(...options);
  if (state.game) dom.gameSelect.value = state.game;
  dom.gameSelect.disabled = options.length === 0;
}

async function loadGames() {
  try {
    const games = await api.games();
    setState({ games, game: games.length ? games[0].app_id : null });
    renderGames();
    if (games.length === 0) {
      setState({
        graphStatus: "error",
        graphError: "no games found in the database (has anything been imported?)",
      });
    }
  } catch (error) {
    setState({ graphStatus: "error", graphError: error.message });
    console.error("games failed", error);
  }
}

// ─── Component wiring ─────────────────────────────────────────────────────────

const search = initSearch({
  input: dom.searchInput,
  dropdown: dom.searchResults,
  onSelect: (workshopId) => loadGraph(workshopId, state.depth),
});

initGraph(dom.cy, (workshopId) => {
  selectNode(workshopId);
  openDetail(workshopId);
});

initDetail({
  panel: dom.detailPanel,
  body: dom.detailBody,
  title: dom.detailTitle,
  obsolete: dom.detailObsolete,
  onClose: dom.detailClose,
});

initDepth({
  slider: dom.depthSlider,
  label: dom.depthLabel,
  onChange: (depth) => {
    if (state.rootId) loadGraph(state.rootId, depth);
  },
});

dom.gameSelect.addEventListener("change", () => {
  setState({ game: dom.gameSelect.value });
  search.clear();
  clearGraph();
});

dom.layoutSelect.addEventListener("change", () => {
  setState({ layout: dom.layoutSelect.value });
  runLayout(state.layout);
});

window.addEventListener("resize", resizeGraph);

// ─── Rendering ────────────────────────────────────────────────────────────────

function renderOverlay() {
  const { graphStatus, graphError } = state;

  if (graphStatus === "ready") {
    dom.overlay.hidden = true;
    return;
  }

  dom.overlay.hidden = false;
  dom.overlay.classList.toggle("overlay--error", graphStatus === "error");

  if (graphStatus === "loading") {
    dom.overlayMessage.textContent = "Loading graph...";
  } else if (graphStatus === "error") {
    dom.overlayMessage.textContent = `Failed to load graph: ${graphError}. Is the backend running?`;
  } else {
    dom.overlayMessage.textContent = EMPTY_MESSAGE;
  }
}

function renderStats() {
  const { graph, graphStatus } = state;
  const nodes = graph.nodes?.length ?? 0;
  const edges = graph.edges?.length ?? 0;

  dom.statNodes.textContent = String(nodes);
  dom.statEdges.textContent = String(edges);
  dom.statMods.textContent = String(nodes);
  dom.statCycles.textContent =
    graphStatus === "ready" ? String(countCyclicComponents(graph.nodes, graph.edges)) : "0";
}

function renderTruncation() {
  const { graph, graphStatus } = state;
  const shown = graphStatus === "ready" && graph.truncated;
  dom.truncation.hidden = !shown;
  if (shown) {
    dom.truncation.textContent = `Graph truncated: showing the first ${graph.total_nodes} nodes (max 200)`;
  }
}

function render() {
  renderOverlay();
  renderStats();
  renderTruncation();
  document.body.classList.toggle(
    "is-busy",
    state.graphStatus === "loading" || state.detailStatus === "loading"
  );
}

// ─── Boot ─────────────────────────────────────────────────────────────────────

subscribe(render);
renderGames();
render();
loadGames();

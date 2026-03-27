const GRAPH = {
  nodes: {
    mod_framework: { kind: "mod", title: "Framework Core" },
    mod_ui: { kind: "mod", title: "Immersive UI" },
    mod_balance: { kind: "mod", title: "Balance Patch" },
    mod_audio: { kind: "mod", title: "Dynamic Audio" },
    mod_patch: { kind: "mod", title: "UI Audio Compatibility Patch" },
    col_starter: { kind: "collection", title: "Starter Collection" },
    col_hardcore: { kind: "collection", title: "Hardcore Pack" },
    author_ak: { kind: "author", title: "Akyrohunter" },
    author_bin2: { kind: "author", title: "bin^2" },
  },
  edges: [
    { source: "mod_ui", target: "mod_framework", kind: "requires" },
    { source: "mod_balance", target: "mod_framework", kind: "requires" },
    { source: "mod_patch", target: "mod_ui", kind: "requires" },
    { source: "mod_patch", target: "mod_audio", kind: "requires" },
    { source: "col_starter", target: "mod_framework", kind: "contains" },
    { source: "col_starter", target: "mod_ui", kind: "contains" },
    { source: "col_hardcore", target: "mod_balance", kind: "contains" },
    { source: "col_hardcore", target: "mod_patch", kind: "contains" },
    { source: "author_ak", target: "mod_audio", kind: "authored" },
    { source: "author_ak", target: "mod_patch", kind: "authored" },
    { source: "author_bin2", target: "mod_framework", kind: "authored" },
    { source: "author_bin2", target: "mod_ui", kind: "authored" },
    { source: "author_bin2", target: "mod_balance", kind: "authored" },
    { source: "author_bin2", target: "col_starter", kind: "assembled" },
    { source: "author_ak", target: "col_hardcore", kind: "assembled" },
  ],
};

function buildReachableSubgraph(root, maxDepth) {
  const nodes = new Map();
  const edgeMap = new Map();
  const relationCounts = { requires: 0, contains: 0, authored: 0, assembled: 0 };
  const stack = [];
  const onRequiresPath = new Set();
  const adjacency = new Map();

  for (const edge of GRAPH.edges) {
    const current = adjacency.get(edge.source) || [];
    current.push(edge);
    adjacency.set(edge.source, current);
  }

  function upsertNode(nodeId, depth, isCycle = false) {
    const base = GRAPH.nodes[nodeId];
    if (!base) return;
    const existing = nodes.get(nodeId);
    if (!existing) {
      nodes.set(nodeId, {
        id: nodeId,
        title: base.title,
        kind: base.kind,
        depth,
        isCycle,
      });
      return;
    }
    existing.depth = Math.min(existing.depth, depth);
    existing.isCycle = existing.isCycle || isCycle;
    nodes.set(nodeId, existing);
  }

  function dfs(nodeId, depth) {
    const base = GRAPH.nodes[nodeId];
    if (!base) return;

    const isRequiresCycle = base.kind === "mod" && onRequiresPath.has(nodeId);
    upsertNode(nodeId, depth, isRequiresCycle);

    if (depth >= maxDepth) return;
    if (isRequiresCycle) return;

    stack.push(nodeId);
    if (base.kind === "mod") onRequiresPath.add(nodeId);

    for (const edge of adjacency.get(nodeId) || []) {
      const target = GRAPH.nodes[edge.target];
      if (!target) continue;

      const isCycleEdge = edge.kind === "requires" && onRequiresPath.has(edge.target);
      const key = `${edge.kind}:${edge.source}->${edge.target}`;
      if (!edgeMap.has(key)) {
        edgeMap.set(key, {
          source: edge.source,
          target: edge.target,
          kind: edge.kind,
          isCycleEdge,
        });
        relationCounts[edge.kind] += 1;
      } else if (isCycleEdge) {
        edgeMap.get(key).isCycleEdge = true;
      }

      upsertNode(edge.target, depth + 1, isCycleEdge);
      dfs(edge.target, depth + 1);
    }

    if (base.kind === "mod") onRequiresPath.delete(nodeId);
    stack.pop();
  }

  dfs(root, 0);

  return {
    nodes: Array.from(nodes.values()),
    edges: Array.from(edgeMap.values()),
    relationCounts,
  };
}

function init() {
  const rootSelect = document.getElementById("rootSelect");
  const depthInput = document.getElementById("depthInput");
  const layoutSelect = document.getElementById("layoutSelect");
  const btnRender = document.getElementById("btnRender");
  const btnCenter = document.getElementById("btnCenter");

  const statNodes = document.getElementById("statNodes");
  const statEdges = document.getElementById("statEdges");
  const statCycles = document.getElementById("statCycles");
  const statMods = document.getElementById("statMods");
  const statCollections = document.getElementById("statCollections");
  const statAuthors = document.getElementById("statAuthors");

  const roots = Object.keys(GRAPH.nodes);
  rootSelect.innerHTML = roots
    .sort((a, b) => a.localeCompare(b))
    .map((id) => `<option value="${id}">${id} - ${GRAPH.nodes[id].title} [${GRAPH.nodes[id].kind}]</option>`)
    .join("");

  if (GRAPH.nodes.mod_patch) rootSelect.value = "mod_patch";

  let cy = null;

  function render() {
    const root = rootSelect.value;
    const maxDepth = Math.max(1, Math.min(10, Number(depthInput.value || 4)));
    const layout = layoutSelect.value;

    const sub = buildReachableSubgraph(root, maxDepth);
    const cycleNodes = sub.nodes.filter((node) => node.isCycle).length;
    const modCount = sub.nodes.filter((node) => node.kind === "mod").length;
    const collectionCount = sub.nodes.filter((node) => node.kind === "collection").length;
    const authorCount = sub.nodes.filter((node) => node.kind === "author").length;

    statNodes.textContent = String(sub.nodes.length);
    statEdges.textContent = String(sub.edges.length);
    statCycles.textContent = String(cycleNodes);
    statMods.textContent = String(modCount);
    statCollections.textContent = String(collectionCount);
    statAuthors.textContent = String(authorCount);

    const elements = {
      nodes: sub.nodes.map((node) => ({
        data: {
          id: node.id,
          label: node.title,
          kind: node.kind,
          isCycle: node.isCycle ? "1" : "0",
          depth: String(node.depth),
        },
      })),
      edges: sub.edges.map((edge) => ({
        data: {
          id: `e-${edge.kind}-${edge.source}-${edge.target}`,
          source: edge.source,
          target: edge.target,
          kind: edge.kind,
          isCycleEdge: edge.isCycleEdge ? "1" : "0",
        },
      })),
    };

    const style = [
      {
        selector: "node",
        style: {
          label: "data(label)",
          "text-wrap": "wrap",
          "text-max-width": 150,
          color: "rgba(255,255,255,0.92)",
          "font-size": 11,
          "text-valign": "center",
          "text-halign": "center",
          "padding": "8px",
          "border-width": 2,
        },
      },
      {
        selector: "node[kind = 'mod']",
        style: {
          "background-color": "rgba(124, 219, 255, 0.14)",
          "border-color": "#7cdbff",
          shape: "round-rectangle",
        },
      },
      {
        selector: "node[kind = 'collection']",
        style: {
          "background-color": "rgba(255, 196, 107, 0.18)",
          "border-color": "#ffc46b",
          shape: "hexagon",
        },
      },
      {
        selector: "node[kind = 'author']",
        style: {
          "background-color": "rgba(123, 240, 178, 0.18)",
          "border-color": "#7bf0b2",
          shape: "ellipse",
        },
      },
      {
        selector: "node[isCycle = '1']",
        style: {
          "background-color": "rgba(255, 92, 122, 0.18)",
          "border-color": "#ff5c7a",
        },
      },
      {
        selector: "edge",
        style: {
          width: 2,
          "curve-style": "bezier",
          "target-arrow-shape": "triangle",
          "arrow-scale": 1,
          opacity: 0.95,
        },
      },
      {
        selector: "edge[kind = 'requires']",
        style: {
          "line-color": "rgba(124, 219, 255, 0.4)",
          "target-arrow-color": "rgba(124, 219, 255, 0.7)",
        },
      },
      {
        selector: "edge[kind = 'contains']",
        style: {
          "line-style": "dashed",
          "line-color": "rgba(255, 196, 107, 0.55)",
          "target-arrow-color": "rgba(255, 196, 107, 0.85)",
        },
      },
      {
        selector: "edge[kind = 'authored']",
        style: {
          "line-color": "rgba(123, 240, 178, 0.55)",
          "target-arrow-color": "rgba(123, 240, 178, 0.85)",
        },
      },
      {
        selector: "edge[kind = 'assembled']",
        style: {
          "line-style": "dotted",
          "line-color": "rgba(255, 152, 152, 0.6)",
          "target-arrow-color": "rgba(255, 152, 152, 0.9)",
        },
      },
      {
        selector: "edge[isCycleEdge = '1']",
        style: {
          "line-color": "rgba(255, 92, 122, 0.9)",
          "target-arrow-color": "rgba(255, 92, 122, 1)",
        },
      },
    ];

    if (cy) cy.destroy();
    cy = window.cytoscape({
      container: document.getElementById("cy"),
      elements,
      style,
      layout: {
        name: layout,
        directed: true,
        padding: 18,
        animate: true,
        fit: true,
        spacingFactor: 1.15,
      },
      wheelSensitivity: 0.15,
    });

    cy.on("tap", "node", (evt) => {
      evt.target.pop();
    });
  }

  btnRender.addEventListener("click", render);
  btnCenter.addEventListener("click", () => {
    if (!cy) return;
    cy.fit(undefined, 30);
  });

  render();
}

init();

// Cytoscape init / render / layout (UI-SPEC: graph.js).

import { displayTitle } from "./format.js";

const STYLESHEET = [
  {
    selector: "node",
    style: {
      shape: "round-rectangle",
      // Cytoscape's default node size is a fixed 30x30; "label" sizes the node
      // to its (wrapped) label so titles are never clipped by the box.
      width: "label",
      height: "label",
      "background-color": "rgba(124,219,255,0.14)",
      "border-color": "#7cdbff",
      "border-width": 1.5,
      label: "data(label)",
      color: "rgba(255,255,255,0.92)",
      "font-size": 11,
      "text-wrap": "wrap",
      "text-max-width": "150px",
      "text-valign": "center",
      "text-halign": "center",
      "text-events": "no",
      padding: "10px",
    },
  },
  {
    selector: "node[?obsolete]",
    style: {
      "border-color": "#ff5c7a",
      "background-color": "rgba(255,92,122,0.18)",
    },
  },
  {
    selector: "node[kind = 'collection']",
    style: {
      shape: "hexagon",
      "border-color": "#ffc46b",
      "background-color": "rgba(255,196,107,0.18)",
    },
  },
  {
    selector: "node[kind = 'author']",
    style: {
      shape: "ellipse",
      "border-color": "#7bf0b2",
      "background-color": "rgba(123,240,178,0.18)",
    },
  },
  {
    selector: "node:selected",
    style: { "border-color": "#7cdbff", "border-width": 3 },
  },
  {
    selector: "edge",
    style: {
      width: 2,
      "line-color": "rgba(124,219,255,0.4)",
      "target-arrow-color": "rgba(124,219,255,0.4)",
      "target-arrow-shape": "triangle",
      "arrow-scale": 1,
      "curve-style": "bezier",
    },
  },
  {
    selector: "edge[kind = 'contains']",
    style: {
      "line-color": "rgba(255,196,107,0.55)",
      "target-arrow-color": "rgba(255,196,107,0.55)",
      "line-style": "dashed",
      width: 1.5,
    },
  },
  {
    selector: "edge[kind = 'authored']",
    style: {
      "line-color": "rgba(123,240,178,0.55)",
      "target-arrow-color": "rgba(123,240,178,0.55)",
      width: 1.5,
    },
  },
  {
    selector: "edge[kind = 'assembled']",
    style: {
      "line-color": "rgba(255,152,152,0.6)",
      "target-arrow-color": "rgba(255,152,152,0.6)",
      "line-style": "dotted",
      width: 1.5,
    },
  },
];

const LAYOUTS = {
  dagre: {
    name: "dagre",
    directed: true,
    padding: 18,
    animate: true,
    fit: true,
    spacingFactor: 1.15,
    nodeSep: 50,
    rankSep: 80,
  },
  breadthfirst: {
    name: "breadthfirst",
    directed: true,
    padding: 18,
    animate: true,
    fit: true,
    spacingFactor: 1.15,
  },
  cose: { name: "cose", padding: 18, animate: true, fit: true },
};

let cy = null;
let handleNodeClick = () => {};

/** Upper bound on the zoom that `fit` may pick for a sparse graph. */
const MAX_AUTO_ZOOM = 1.3;

/** Create the Cytoscape instance bound to the given container. */
export function initGraph(container, onNodeClick) {
  handleNodeClick = onNodeClick;

  cy = window.cytoscape({
    container,
    style: STYLESHEET,
    elements: [],
    wheelSensitivity: 0.15,
  });

  cy.on("tap", "node", (event) => handleNodeClick(event.target.id()));

  // Debug/testing hook: inspect the graph from the browser console
  // (`__cy.nodes().length`) and drive node clicks in automated checks.
  window.__cy = cy;

  return cy;
}

/** Replace the canvas contents with the given GraphData payload. */
export function renderGraph(data, layoutName) {
  if (!cy) return;

  const elements = [];
  const nodeIds = new Set();

  for (const node of data?.nodes ?? []) {
    if (!node.workshop_id || nodeIds.has(node.workshop_id)) continue;
    nodeIds.add(node.workshop_id);
    elements.push({
      data: {
        id: node.workshop_id,
        label: displayTitle(node.title, node.workshop_id),
        obsolete: Boolean(node.obsolete),
        kind: "mod",
      },
    });
  }

  const edgeKeys = new Set();
  for (const edge of data?.edges ?? []) {
    const { from_workshop_id: source, to_workshop_id: target } = edge;
    // Skip edges pointing outside the fetched neighbourhood.
    if (!nodeIds.has(source) || !nodeIds.has(target)) continue;
    const key = `${source}->${target}`;
    if (edgeKeys.has(key)) continue;
    edgeKeys.add(key);
    elements.push({
      data: {
        id: `e:${key}`,
        source,
        target,
        kind: String(edge.edge_type || "REQUIRES").toLowerCase(),
      },
    });
  }

  cy.elements().remove();
  cy.add(elements);
  runLayout(layoutName);
}

/** Run a named layout; falls back to breadthfirst if the extension is missing. */
export function runLayout(layoutName) {
  if (!cy || cy.nodes().length === 0) return;

  const name = LAYOUTS[layoutName] ? layoutName : "dagre";

  const apply = (options) => {
    // A tiny graph would otherwise be fitted at a huge zoom, rendering the
    // labels far larger than the nodes.
    cy.one("layoutstop", () => {
      if (cy.zoom() > MAX_AUTO_ZOOM) {
        cy.zoom(MAX_AUTO_ZOOM);
        cy.center();
      }
    });
    cy.layout(options).run();
  };

  try {
    apply(LAYOUTS[name]);
  } catch (error) {
    console.warn(`layout "${name}" unavailable — using breadthfirst`, error);
    apply(LAYOUTS.breadthfirst);
  }
}

/** Highlight a node (if present) without re-fetching anything. */
export function selectNode(workshopId) {
  if (!cy) return;
  cy.nodes().unselect();
  const node = cy.getElementById(workshopId);
  if (node && node.length) node.select();
}

export function fitGraph() {
  cy?.fit(undefined, 32);
}

export function resizeGraph() {
  cy?.resize();
}

/**
 * Count circular dependencies in a GraphData payload.
 *
 * Iterative Tarjan SCC (no recursion, so a deep graph cannot blow the stack):
 * every strongly connected component with more than one node is a cycle, plus
 * any self-loop. Returns the number of cyclic components.
 */
export function countCyclicComponents(nodes, edges) {
  const adjacency = new Map();
  for (const node of nodes ?? []) adjacency.set(node.workshop_id, []);

  let selfLoops = 0;
  for (const edge of edges ?? []) {
    const { from_workshop_id: from, to_workshop_id: to } = edge;
    if (!adjacency.has(from) || !adjacency.has(to)) continue;
    if (from === to) {
      selfLoops += 1;
      continue;
    }
    adjacency.get(from).push(to);
  }

  const index = new Map();
  const low = new Map();
  const onStack = new Set();
  const stack = [];
  let counter = 0;
  let cyclic = 0;

  for (const root of adjacency.keys()) {
    if (index.has(root)) continue;

    const work = [[root, 0]];
    while (work.length) {
      const frame = work[work.length - 1];
      const node = frame[0];

      if (frame[1] === 0) {
        index.set(node, counter);
        low.set(node, counter);
        counter += 1;
        stack.push(node);
        onStack.add(node);
      }

      const children = adjacency.get(node) ?? [];
      if (frame[1] < children.length) {
        const child = children[frame[1]];
        frame[1] += 1;
        if (!index.has(child)) {
          work.push([child, 0]);
        } else if (onStack.has(child)) {
          low.set(node, Math.min(low.get(node), index.get(child)));
        }
        continue;
      }

      if (low.get(node) === index.get(node)) {
        const component = [];
        let member;
        do {
          member = stack.pop();
          onStack.delete(member);
          component.push(member);
        } while (member !== node);
        if (component.length > 1) cyclic += 1;
      }

      work.pop();
      if (work.length) {
        const parent = work[work.length - 1][0];
        low.set(parent, Math.min(low.get(parent), low.get(node)));
      }
    }
  }

  return cyclic + selfLoops;
}

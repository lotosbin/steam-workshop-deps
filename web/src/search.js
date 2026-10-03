// Search input + results dropdown (UI-SPEC: search.js).

import { api } from "./api.js";
import { displayTitle, el } from "./format.js";
import { state, setState, subscribe } from "./state.js";

const DEBOUNCE_MS = 300;

export function initSearch({ input, dropdown, onSelect }) {
  let debounceTimer = null;
  let requestSeq = 0;

  function message(text) {
    return el("div", "search-message", text);
  }

  function render() {
    const { results, resultsStatus, query } = state;

    if (resultsStatus === "idle") {
      dropdown.hidden = true;
      dropdown.replaceChildren();
      return;
    }

    dropdown.hidden = false;

    if (resultsStatus === "loading") {
      dropdown.replaceChildren(message("Searching..."));
      return;
    }

    if (resultsStatus === "error") {
      dropdown.replaceChildren(message("Search failed. Is the backend running?"));
      return;
    }

    if (resultsStatus === "empty") {
      dropdown.replaceChildren(message(`No mods found for '${query}'`));
      return;
    }

    const items = results.map((result) => {
      const button = el("button", "search-result");
      button.type = "button";
      button.dataset.workshopId = result.workshop_id;
      button.appendChild(
        el("span", "search-result__title", displayTitle(result.title, result.workshop_id))
      );
      if (result.obsolete) {
        button.appendChild(el("span", "badge badge--danger", "[Obsolete]"));
      }
      return button;
    });

    dropdown.replaceChildren(...items);
  }

  async function run(query) {
    const seq = ++requestSeq;
    setState({ resultsStatus: "loading" });
    try {
      const results = await api.search(query, state.game);
      if (seq !== requestSeq) return; // a newer query already started
      setState({ results, resultsStatus: results.length ? "done" : "empty" });
    } catch (error) {
      if (seq !== requestSeq) return;
      setState({ results: [], resultsStatus: "error" });
      console.error("search failed", error);
    }
  }

  input.addEventListener("input", () => {
    const query = input.value.trim();
    clearTimeout(debounceTimer);
    setState({ query });

    if (!query) {
      requestSeq += 1; // invalidate any in-flight request
      setState({ results: [], resultsStatus: "idle" });
      return;
    }

    setState({ resultsStatus: "loading" });
    debounceTimer = setTimeout(() => run(query), DEBOUNCE_MS);
  });

  dropdown.addEventListener("click", (event) => {
    const button = event.target.closest(".search-result");
    if (!button) return;
    onSelect(button.dataset.workshopId);
    dropdown.hidden = true;
    setState({ resultsStatus: "idle" });
  });

  document.addEventListener("click", (event) => {
    if (event.target !== input && !dropdown.contains(event.target)) {
      dropdown.hidden = true;
    }
  });

  input.addEventListener("focus", () => {
    if (state.resultsStatus !== "idle") dropdown.hidden = false;
  });

  subscribe(render);
  render();

  return {
    clear() {
      input.value = "";
      requestSeq += 1;
      setState({ query: "", results: [], resultsStatus: "idle" });
    },
  };
}

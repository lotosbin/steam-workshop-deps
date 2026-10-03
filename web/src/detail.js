// Detail panel: open / close / populate (UI-SPEC: detail.js).

import { api } from "./api.js";
import { displayTitle, el } from "./format.js";
import { setState, state, subscribe } from "./state.js";

function row(label, value) {
  if (!value) return null;
  const wrapper = el("div", "detail__row");
  wrapper.appendChild(el("div", "detail__label", label));
  wrapper.appendChild(el("div", "detail__value", value));
  return wrapper;
}

function linkRow(label, href, text) {
  if (!href) return null;
  const wrapper = el("div", "detail__row");
  wrapper.appendChild(el("div", "detail__label", label));
  const anchor = el("a", "detail__value", text || href);
  anchor.href = href;
  anchor.target = "_blank";
  anchor.rel = "noopener noreferrer";
  wrapper.appendChild(anchor);
  return wrapper;
}

export function initDetail({ panel, body, title, obsolete, onClose }) {
  function render() {
    const { detailStatus, detail, selectedId } = state;

    if (!selectedId) {
      panel.hidden = true;
      return;
    }

    panel.hidden = false;

    if (detailStatus === "loading") {
      title.textContent = "Loading...";
      obsolete.hidden = true;
      body.replaceChildren();
      return;
    }

    if (detailStatus === "error" || !detail) {
      title.textContent = "Failed to load mod";
      obsolete.hidden = true;
      body.replaceChildren(
        el("div", "detail__value", "Could not load mod details. Is the backend running?")
      );
      return;
    }

    title.textContent = displayTitle(detail.title, detail.workshop_id);
    obsolete.hidden = !detail.obsolete;

    const preview = detail.preview_url
      ? Object.assign(el("img", "detail__preview"), {
          src: detail.preview_url,
          alt: "",
          loading: "lazy",
        })
      : null;

    const cta = el("a", "detail__cta", "View on Steam");
    cta.href = detail.canonical_url || `https://steamcommunity.com/sharedfiles/filedetails/?id=${detail.workshop_id}`;
    cta.target = "_blank";
    cta.rel = "noopener noreferrer";

    body.replaceChildren(
      ...[
        preview,
        row("Workshop ID", detail.workshop_id),
        linkRow("Author", detail.author_profile_url, detail.author || detail.author_id),
        row("Posted", detail.posted),
        row("Updated", detail.updated),
        row("File size", detail.file_size),
        row("Description", detail.description),
        cta,
      ].filter(Boolean)
    );
  }

  onClose.addEventListener("click", () => {
    setState({ selectedId: null, detail: null, detailStatus: "idle" });
  });

  subscribe(render);
  render();
}

/** Fetch and show the detail panel for a node clicked on the canvas. */
export async function openDetail(workshopId) {
  setState({ selectedId: workshopId, detail: null, detailStatus: "loading" });
  try {
    const detail = await api.mod(workshopId);
    if (state.selectedId !== workshopId) return; // selection moved on
    setState({ detail, detailStatus: "ready" });
  } catch (error) {
    if (state.selectedId !== workshopId) return;
    setState({ detail: null, detailStatus: "error" });
    console.error("detail failed", error);
  }
}

// Depth slider wiring (UI-SPEC: depth.js).

import { setState } from "./state.js";

export function initDepth({ slider, label, onChange }) {
  function paint() {
    label.textContent = `Depth: ${slider.value}`;
  }

  // `input` fires while dragging -> keep the label live.
  slider.addEventListener("input", () => {
    setState({ depth: Number(slider.value) });
    paint();
  });

  // `change` fires on release -> only then re-fetch the neighbourhood.
  slider.addEventListener("change", () => {
    const depth = Number(slider.value);
    setState({ depth });
    paint();
    onChange(depth);
  });

  paint();
}

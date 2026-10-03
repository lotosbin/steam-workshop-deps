// Display-only helpers (never mutate the underlying data).

const TITLE_PREFIXES = [
  /^Steam Workshop\s*::\s*/i,
  /^Steam Community\s*::\s*(?:Workshop\s*::\s*)?/i,
  /^Steam Workshop\s*:\s*/i,
];

/**
 * Steam's <title>/og:title carries a "Steam Workshop::" prefix. Strip it for
 * display, and fall back to the workshop id when a node has no title at all.
 */
export function displayTitle(title, workshopId) {
  let value = String(title ?? "").trim();
  for (const re of TITLE_PREFIXES) value = value.replace(re, "");
  value = value.trim();
  if (value) return value;
  return workshopId ? `#${workshopId}` : "(untitled)";
}

/** Epoch-millis or already-formatted date -> a short display string. */
export function displayDate(value) {
  if (!value) return null;
  return String(value);
}

/** Tiny DOM builder used by the panel/dropdown renderers. */
export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}

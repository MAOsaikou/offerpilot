const LEGACY_CROP = 40

function points(value: string) {
  return [...value].length
}

/**
 * A stored title that is only the old 40-character crop, or a generated cut ending in …,
 * is replaced by the first question so the header can ellipsize in CSS.
 * A shorter rename stays as the user wrote it.
 */
export function chatHeadingTitle(stored: string, firstUserText: string) {
  const title = stored.trim()
  const full = firstUserText.replace(/\s+/g, ' ').trim()
  if (!title) return full
  if (!full || full === title) return title
  const legacy = points(title) === LEGACY_CROP && full.startsWith(title)
  const generated = title.endsWith('…') && full.startsWith(title.slice(0, -1))
  return legacy || generated ? full : title
}

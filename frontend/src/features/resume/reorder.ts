/** Move to a final zero-based position without mutating entries or their contents. */
export function moveEntry<T>(entries: T[], from: number, to: number): T[] {
  if (
    !Number.isInteger(from) ||
    !Number.isInteger(to) ||
    from < 0 ||
    to < 0 ||
    from >= entries.length ||
    to >= entries.length ||
    from === to
  )
    return entries
  const result = [...entries]
  const [entry] = result.splice(from, 1)
  result.splice(to, 0, entry!)
  return result
}

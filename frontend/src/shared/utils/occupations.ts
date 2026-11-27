export function uniqueOccupations<T extends { occupationId: string; name: string }>(
  items: T[],
): T[] {
  const chosen = new Map<string, T>()
  const order: string[] = []
  for (const item of items) {
    const name = item.name.trim()
    const key = name || item.occupationId
    const existing = chosen.get(key)
    if (!existing) {
      chosen.set(key, item)
      order.push(key)
      continue
    }
    if (preferOccupation(item, existing)) chosen.set(key, item)
  }
  return order.map((key) => chosen.get(key)!)
}

function preferOccupation(
  candidate: { occupationId: string },
  current: { occupationId: string },
) {
  const curated = (id: string) => !id.startsWith('occ_onet_')
  if (curated(candidate.occupationId) !== curated(current.occupationId))
    return curated(candidate.occupationId)
  return candidate.occupationId < current.occupationId
}

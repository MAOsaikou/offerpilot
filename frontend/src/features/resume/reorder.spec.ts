import { expect, it } from 'vitest'
import { moveEntry } from './reorder'

it('moves in either direction without mutating the source or entry contents', () => {
  const entries = [{ school: 'A' }, { school: 'B' }, { school: 'C' }]
  const original = [...entries]
  expect(moveEntry(entries, 0, 2)).toEqual([entries[1], entries[2], entries[0]])
  expect(moveEntry(entries, 2, 0)).toEqual([entries[2], entries[0], entries[1]])
  expect(entries).toEqual(original)
  expect(moveEntry(entries, 0, 1)[1]).toBe(entries[0])
  expect(moveEntry(['A', 'A', 'B'], 0, 2)).toEqual(['A', 'B', 'A'])
})

it('leaves empty, singleton, unchanged and invalid moves untouched', () => {
  for (const entries of [[], ['A'], ['A', 'B']]) {
    for (const [from, to] of [
      [0, 0],
      [-1, 0],
      [0, -1],
      [2, 0],
      [0, 2],
      [0.5, 1],
      [0, NaN],
    ])
      expect(moveEntry(entries, from!, to!)).toBe(entries)
  }
})

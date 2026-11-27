import { expect, it } from 'vitest'
import { uniqueOccupations } from './occupations'

it('keeps one dropdown row per name and prefers the curated id', () => {
  const unique = uniqueOccupations([
    { occupationId: 'occ_onet_29-1161.00', name: '助产士' },
    { occupationId: 'occ_onet_29-9099.01', name: '助产士' },
    { occupationId: 'occ_onet_15-2051.00', name: '数据科学家' },
    { occupationId: 'data-scientist', name: '数据科学家' },
    { occupationId: 'nurse', name: '护士' },
  ])
  expect(unique.map((item) => [item.occupationId, item.name])).toEqual([
    ['occ_onet_29-1161.00', '助产士'],
    ['data-scientist', '数据科学家'],
    ['nurse', '护士'],
  ])
})

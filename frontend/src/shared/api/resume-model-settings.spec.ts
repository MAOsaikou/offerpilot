import { expect, it, vi } from 'vitest'
import { apiRequest } from './client'
import { getResumeModelSettings } from './resume-model-settings'

vi.mock('./client', () => ({ apiRequest: vi.fn() }))
it('reads old CHEAP preferences as FLASH and removes the old catalog option', async () => {
  vi.mocked(apiRequest).mockResolvedValue({
    models: [{ id: 'FLASH' }, { id: 'CHEAP' }, { id: 'PRO' }, { id: 'VISION_ALT' }],
    selection: { textTier: 'cheap', visionTier: 'CHEAP' },
  })
  const settings = await getResumeModelSettings()
  expect(settings.models.map((model) => model.id)).toEqual(['FLASH', 'PRO', 'VISION_ALT'])
  expect(settings.selection).toEqual({ textTier: 'FLASH', visionTier: 'FLASH' })
})

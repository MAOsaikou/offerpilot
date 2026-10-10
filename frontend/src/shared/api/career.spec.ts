import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { getCareerProfile, saveCareerProfile, saveCareerTasks } from './career'
import { ApiError } from './client'

const fields = { target: '开发工程师', city: '上海', skills: 'Java', interests: ['技术研发'] }
const profile = { ...fields, completedTasks: [], updatedAt: '2026-10-09T00:00:00Z' }
const requests: { method: string; authorization: string | null; body?: unknown }[] = []
const server = setupServer(
  http.get('*/api/v1/career/profile', ({ request }) => {
    requests.push({ method: request.method, authorization: request.headers.get('Authorization') })
    return HttpResponse.json({ data: profile })
  }),
  http.put('*/api/v1/career/:resource', async ({ request }) => {
    requests.push({
      method: request.method,
      authorization: request.headers.get('Authorization'),
      body: await request.json(),
    })
    return HttpResponse.json({ data: profile })
  }),
)
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  localStorage.setItem('offerpilot-guest-v1', 'career-owner')
  requests.length = 0
})
afterEach(() => {
  localStorage.clear()
  server.resetHandlers()
})
afterAll(() => server.close())

it('reads and saves career data with the current bearer identity and separate DTOs', async () => {
  expect(await getCareerProfile()).toEqual(profile)
  expect(await saveCareerProfile(fields)).toEqual(profile)
  await saveCareerTasks(['梳理目标岗位的核心技能'])
  expect(requests).toEqual([
    { method: 'GET', authorization: 'Bearer career-owner' },
    { method: 'PUT', authorization: 'Bearer career-owner', body: fields },
    {
      method: 'PUT',
      authorization: 'Bearer career-owner',
      body: { completed: ['梳理目标岗位的核心技能'] },
    },
  ])
})

it('preserves server validation errors so the views can show a banner', async () => {
  server.use(
    http.put('*/api/v1/career/tasks', () =>
      HttpResponse.json({ code: 10001, message: '未知任务' }, { status: 400 }),
    ),
  )
  await expect(saveCareerTasks(['未知任务'])).rejects.toEqual(
    expect.objectContaining<ApiError>({
      name: 'ApiError',
      message: '未知任务',
      code: 10001,
      status: 400,
    }),
  )
})

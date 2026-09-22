import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { createWorkspace, isWorkspace, useWorkspace } from './workspace'

describe('本地工作区', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })
  it('同一岗位不会重复收藏，刷新后阶段和备注仍保留', async () => {
    const workspace = useWorkspace()
    workspace.saveJob('j1')
    workspace.saveJob('j1')
    expect(workspace.state.applications).toHaveLength(1)
    const app = workspace.state.applications[0]!
    app.stage = '面试中'
    app.note = '准备项目复盘'
    await nextTick()
    setActivePinia(createPinia())
    expect(useWorkspace().state.applications[0]).toMatchObject({
      stage: '面试中',
      note: '准备项目复盘',
    })
  })
  it('拒绝损坏的嵌套存储，不覆盖原始内容', async () => {
    const raw = JSON.stringify({
      ...createWorkspace(),
      interviews: [{ id: 'broken', answers: null }],
    })
    localStorage.setItem('offerpilot-workspace-v1', raw)
    const workspace = useWorkspace()
    expect(workspace.storageError).not.toBe('')
    workspace.state.name = '测试'
    await nextTick()
    expect(localStorage.getItem('offerpilot-workspace-v1')).toBe(raw)
  })
  it('真实岗位收藏携带快照，刷新后看板仍有标题且保留旧示例收藏', async () => {
    const store = useWorkspace()
    store.saveJob('j1')
    store.saveJob({
      id: 'job_real',
      title: '实际岗位',
      company: '企业',
      city: '上海',
      salary: '',
      category: '',
      tags: [],
      description: '',
      color: '',
    })
    await nextTick()
    setActivePinia(createPinia())
    expect(useWorkspace().jobFor('job_real')?.title).toBe('实际岗位')
    expect(useWorkspace().jobFor('j1')?.title).toBeTruthy()
  })
  it('不接受超出题库范围的面试位置或非字符串任务', () => {
    expect(isWorkspace(createWorkspace())).toBe(true)
    expect(isWorkspace({ ...createWorkspace(), tasks: [null] })).toBe(false)
    expect(
      isWorkspace({
        ...createWorkspace(),
        interviews: [
          {
            id: 'i1',
            role: '开发',
            kind: '练习',
            date: '今天',
            answers: [],
            question: 8,
            completed: false,
          },
        ],
      }),
    ).toBe(false)
  })
})

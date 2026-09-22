import { createApp, nextTick } from 'vue'
import { expect, it } from 'vitest'
import EvidenceMatchReport from './EvidenceMatchReport.vue'
it('evidence opens the immutable original field and highlights the exact quote', async () => {
  const root = document.createElement('div')
  const app = createApp(EvidenceMatchReport, {
    payload: {
      score: 50,
      resumeTitle: '已保存的简历',
      resumeVersionNo: 2,
      summary: '部分体现',
      createdAt: '2026-09-21',
      model: 'test',
      requirements: [
        {
          requirement: 'Java',
          status: 'PARTIAL',
          reason: '待补证据',
          suggestion: '补充真实项目细节',
          jdQuote: '熟悉Java',
          resumePath: '/projects/0/description',
          resumeQuote: 'Java项目',
        },
      ],
      jobSnapshot: { title: '工程师', description: '参与开发', requirement: '熟悉Java' },
      resumeSnapshot: { projects: [{ description: '曾完成Java项目，使用SQL' }] },
    },
  })
  app.mount(root)
  try {
    const button = [...root.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('简历依据'),
    )!
    button.click()
    await nextTick()
    expect(root.querySelector('mark')?.textContent).toBe('Java项目')
    expect(root.textContent).toContain('曾完成Java项目，使用SQL')
    expect(root.textContent).toContain('不是录用概率')
  } finally {
    app.unmount()
  }
})

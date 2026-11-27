import { expect, it } from 'vitest'
import { resumeLines } from './lineDiff'

it('labels structured resume fields in Chinese instead of raw keys', () => {
  const text = resumeLines({
    basics: { name: '林晓舟', title: 'Java 后端实习', phone: '', email: '', city: '', links: [] },
    education: [
      { school: '岭南师范学院', degree: '本科', major: '计算机', start: '2022', end: '2026', gpa: '3.8' },
    ],
    experience: [{ org: '示例科技', title: '实习生', start: '2025', end: '2025', bullets: ['完成接口'] }],
    projects: [{ name: '校园二手交易平台', role: '后端', start: '', end: '', tech: ['Java'], bullets: [] }],
    skills: [{ category: '语言', items: ['Java'], extra: '忽略键名' }],
    summary: '',
    certificates: [],
  })
  for (const label of ['结束时间', 'GPA / 成绩', '职责与成果', '公司 / 组织', '头衔 / 求职意向', '其他内容'])
    expect(text).toContain(label)
  expect(text).not.toMatch(/· (end|gpa|bullets|org|extra)：/)
})

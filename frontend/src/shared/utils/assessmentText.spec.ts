import { afterEach, expect, it } from 'vitest'
import { dictionaries, setLanguage } from './language'
import {
  assessmentQuestionKeys,
  questionnaireQuestionKeys,
  assessmentQuestionText,
  questionnaireQuestionText,
} from './assessmentText'

afterEach(() => setLanguage('zh-CN'))

it('covers the seven base indexes and all 30 fixed IDs with aligned bilingual prompts', () => {
  // Fixtures retain the server index/ID contract, independently of display language.
  const base = [
    '先认识一下：你学什么专业、现在几年级？',
    '课余时间你最常做、做起来会忘了时间的是什么事？',
    '说一件让你特别有成就感的经历：当时你具体做了什么？',
    '你更享受哪类事情：动手做出东西、钻研弄懂原理、创作表达、帮助别人、组织推动项目，还是把事情整理得井井有条？可以举个例子。',
    '在团队里你通常扮演什么角色？同学或朋友会怎么形容你？',
    '选工作时你最看重什么？比如成长、收入、稳定、兴趣、影响力、工作生活平衡。',
    '最后说说现实条件：意向城市、期望月薪，能接受多少加班？没想好也可以直说。',
  ]
  const prompts = [
    '把松动的书架修到稳固可用',
    '照着示意图组装一个小装置',
    '在户外观察并照料植物',
    '用工具把材料做成实物',
    '排查家中设备无法工作的原因',
    '设计小实验检验自己的猜想',
    '比较几组数据寻找规律',
    '追查一个现象背后的原因',
    '推演一道没有现成答案的难题',
    '阅读资料弄懂新技术的原理',
    '为一段故事画出不同的场景',
    '用声音或旋律表达一种情绪',
    '把普通物品重新设计得更有趣',
    '写一篇带有个人风格的短文',
    '剪辑一段能传达自己想法的视频',
    '耐心教别人学会一项新技能',
    '倾听朋友的困惑并陪他梳理',
    '组织一次帮助社区成员的活动',
    '为新同学介绍如何适应校园',
    '把复杂知识讲到别人听懂',
    '说服伙伴支持一项新提议',
    '带领小组把活动从想法变成现实',
    '向潜在使用者介绍一个产品',
    '协调不同意见争取共同方案',
    '寻找机会发起一个小项目',
    '把混乱的资料按规则归档',
    '核对表格找出遗漏和错误',
    '制定清楚的步骤让工作有序进行',
    '持续记录并整理一项活动的收支',
    '按约定标准检查一批成果',
  ]
  expect(assessmentQuestionKeys).toHaveLength(7)
  expect(assessmentQuestionKeys.map((key) => dictionaries['zh-CN'][key])).toEqual(base)
  const ids = [...'RIASEC'].flatMap((axis) => [1, 2, 3, 4, 5].map((i) => `${axis}${i}`))
  expect(Object.keys(questionnaireQuestionKeys)).toEqual(ids)
  expect(Object.values(questionnaireQuestionKeys).map((key) => dictionaries['zh-CN'][key])).toEqual(
    prompts,
  )
  for (const key of [...assessmentQuestionKeys, ...Object.values(questionnaireQuestionKeys)]) {
    expect(dictionaries['en-US'][key], key).toBe(key)
    expect(key).not.toMatch(/\p{Script=Han}/u)
  }
  setLanguage('en-US')
  ids.forEach((id, i) =>
    expect(questionnaireQuestionText(id, prompts[i]!)).toBe(questionnaireQuestionKeys[id]),
  )
  base.forEach((text, i) =>
    expect(assessmentQuestionText(i, text!)).toBe(assessmentQuestionKeys[i]),
  )
})

it('preserves Chinese defaults, unknown IDs and dynamic follow-ups', () => {
  expect(assessmentQuestionText(0, '原题')).toBe('原题')
  expect(questionnaireQuestionText('R1', '原题')).toBe('原题')
  setLanguage('en-US')
  expect(assessmentQuestionText(7, '动态追问原文')).toBe('动态追问原文')
  expect(questionnaireQuestionText('future-id', '未来题目原文')).toBe('未来题目原文')
  expect(questionnaireQuestionText('toString', '未知题目原文')).toBe('未知题目原文')
})

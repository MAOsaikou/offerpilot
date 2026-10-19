import { applicationStages, type ApplicationStatus } from '@/shared/types/board'

// Resource identifiers remain in transport arguments, never in visible review text.
export function toolArguments(args: unknown): Array<{ label: string; value: string }> {
  if (!args || typeof args !== 'object') return []
  const outer = args as Record<string, unknown>
  const data =
    outer.inputs && typeof outer.inputs === 'object'
      ? (outer.inputs as Record<string, unknown>)
      : outer
  const fields: Record<string, string> = {
    name: '目标职业',
    city: '意向城市',
    minSalaryK: '期望月薪下限（k 元）',
    avoidOvertime: '不想加班',
    research: '偏研究',
    role: '目标岗位',
    kind: '练习侧重点',
    status: '阶段',
    note: '备注',
    nextAction: '下一步行动',
    nextActionAt: '行动日期',
    answer: '回答',
    request: '请求',
    keyword: '关键词',
    query: '关键词',
    location: '工作地点',
    nature: '岗位类型',
    site: '官网站点',
    questionIndex: '面试题号（从 0 开始）',
    nextQuestion: '进入下一题',
    taskTitles: '确认已完成的任务',
  }
  const rows = Object.entries(fields).flatMap(([key, label]) => {
    const raw = data[key]
    if (raw === undefined || raw === null) return []
    const value =
      key === 'status'
        ? (applicationStages[raw as ApplicationStatus] ?? String(raw))
        : Array.isArray(raw)
          ? raw.join('、')
          : typeof raw === 'boolean'
            ? raw
              ? '是'
              : '否'
            : String(raw)
    return [{ label, value: value || '清空' }]
  })
  const accepted = Array.isArray(data.accepted) ? data.accepted.length : 0
  const edited = data.edited && typeof data.edited === 'object' ? Object.values(data.edited) : []
  if (accepted || edited.length) {
    rows.push({ label: '采纳建议', value: `${accepted} 条原建议、${edited.length} 条编辑后的建议` })
    for (const edit of edited) {
      if (edit && typeof edit === 'object' && 'value' in edit)
        rows.push({ label: '编辑内容', value: String(edit.value) })
    }
  }
  return rows
}

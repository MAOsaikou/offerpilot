import type { AgentEvent, ChatRequest } from '@/shared/types/agent-events'

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never
type EventDraft = DistributiveOmit<AgentEvent, 'id' | 'runId' | 'ts'>

const RUN_ID = 'run_mock_01'
const CONVERSATION_ID = 'conv_mock_01'

/**
 * 一段脚本化的运行过程，事件顺序与 docs/05 §4.1 给出的典型序列一致：
 * run.started → plan.created → reasoning / tool → approval → ui.card → text → usage → completed
 *
 * 后端就绪后这份脚本只用于单测与离线演示，不再参与真实请求路径。
 */
export function buildScenario(request: ChatRequest): AgentEvent[] {
  const goal = request.content?.trim() || '帮我规划下一步'
  const mode = request.mode ?? 'AUTO'

  const drafts: EventDraft[] = [
    {
      type: 'run.started',
      conversationId: CONVERSATION_ID,
      messageId: 'msg_mock_user',
      model: 'qwen3-mock',
      mode,
    },
    {
      type: 'plan.created',
      goal,
      steps: [
        { id: 's1', title: '搜索校招岗位', agent: 'JOB', status: 'PENDING', dependsOn: [] },
        {
          id: 's2',
          title: '计算简历与岗位的匹配度',
          agent: 'JOB',
          status: 'PENDING',
          dependsOn: ['s1'],
        },
        {
          id: 's3',
          title: '按目标 JD 改写简历要点',
          agent: 'RESUME',
          status: 'PENDING',
          dependsOn: ['s2'],
        },
        {
          id: 's4',
          title: '写入求职看板',
          agent: 'JOB',
          status: 'PENDING',
          dependsOn: ['s3'],
        },
      ],
    },
    { type: 'step.updated', stepId: 's1', status: 'RUNNING' },
    { type: 'reasoning.delta', stepId: 's1', text: '先拆一下：' },
    { type: 'reasoning.delta', stepId: 's1', text: '得先确认有哪些岗位在招，' },
    { type: 'reasoning.delta', stepId: 's1', text: '再挑出最匹配的一条 JD 当作改简历的靶子。' },
    {
      type: 'tool.call',
      stepId: 's1',
      toolCallId: 'tc_1',
      name: 'jobs.search_jobs',
      args: { keywords: '后端 校招', companies: ['字节跳动', '美团'], nature: 'CAMPUS', limit: 6 },
      source: 'mcp:jobs',
      access: 'READ',
    },
    {
      type: 'tool.result',
      stepId: 's1',
      toolCallId: 'tc_1',
      name: 'jobs.search_jobs',
      ok: true,
      resultPreview: '共找到 6 个岗位，来源：字节招聘、美团招聘',
      durationMs: 2140,
      truncated: false,
    },
    {
      type: 'step.updated',
      stepId: 's1',
      status: 'DONE',
      summary: '找到 6 个岗位',
      durationMs: 4210,
    },
    { type: 'step.updated', stepId: 's2', status: 'RUNNING' },
    {
      type: 'tool.call',
      stepId: 's2',
      toolCallId: 'tc_2',
      name: 'jobs.match_job',
      args: { resumeVersionId: 'rv_mock_1', jobId: 'job_mock_1' },
      source: 'mcp:jobs',
      access: 'READ',
    },
    {
      type: 'tool.result',
      stepId: 's2',
      toolCallId: 'tc_2',
      name: 'jobs.match_job',
      ok: true,
      resultPreview: '综合匹配度 78 分，技能 82 / 经验 70 / 学历 90 / 关键词 72',
      durationMs: 1860,
      truncated: false,
    },
    {
      type: 'ui.card',
      stepId: 's2',
      cardId: 'card_match_1',
      cardType: 'job_match',
      payload: {
        id: 'jm_mock_1',
        jobId: 'job_mock_1',
        resumeVersionId: 'rv_mock_1',
        score: 78,
        dimensions: { skills: 82, experience: 70, education: 90, keywords: 72 },
        matched: [
          {
            requirement: '熟悉 Java 与 Spring 生态',
            evidence: '/skills/0/items',
            strength: 'STRONG',
          },
        ],
        gaps: [
          {
            requirement: '了解分布式事务',
            severity: 'MEDIUM',
            suggestion: '补充 Seata / TCC 的学习，并在项目里说明一致性方案',
          },
        ],
        advice: ['在项目描述里突出高并发与缓存实践', '把接口延迟优化写成可验证的数字'],
        citations: [{ docId: 'kb_backend_model', title: '后端岗位能力模型 v2' }],
      },
    },
    { type: 'step.updated', stepId: 's2', status: 'DONE', summary: '匹配度 78', durationMs: 3980 },
    { type: 'step.updated', stepId: 's3', status: 'RUNNING' },
    {
      type: 'tool.call',
      stepId: 's3',
      toolCallId: 'tc_3',
      name: 'resume.optimize',
      args: { resumeVersionId: 'rv_mock_1', jobId: 'job_mock_1' },
      source: 'local',
      access: 'WRITE',
    },
    {
      type: 'approval.required',
      stepId: 's3',
      approvalId: 'ap_mock_1',
      toolCallId: 'tc_3',
      name: 'resume.optimize',
      args: { resumeVersionId: 'rv_mock_1', jobId: 'job_mock_1', batchSize: 3 },
      reason: '这会基于目标 JD 生成 3 处改写建议，采纳后会产生一个新版本',
      expiresAt: '2026-09-11T21:00:00+08:00',
      editableFields: ['batchSize'],
    },
    { type: 'approval.resolved', stepId: 's3', approvalId: 'ap_mock_1', decision: 'APPROVE' },
    {
      type: 'tool.result',
      stepId: 's3',
      toolCallId: 'tc_3',
      name: 'resume.optimize',
      ok: true,
      resultPreview: '生成 3 条改写建议，其中 1 条需要你确认数字',
      durationMs: 6420,
      truncated: false,
    },
    {
      type: 'ui.card',
      stepId: 's3',
      cardId: 'card_diff_1',
      cardType: 'resume_diff',
      payload: {
        batchId: 'pb_mock_1',
        resumeVersionId: 'rv_mock_1',
        stats: { added: 1, replaced: 2, removed: 0 },
        patches: [
          {
            id: 'p1',
            op: 'replace',
            path: '/projects/0/bullets/1',
            from: '负责后端开发',
            value:
              '独立设计并实现商品、订单模块 12 个接口，接口 P95 延迟由 480ms 优化至 120ms（引入 Redis 缓存与索引优化）',
            reason: '补充量化结果，对齐 JD 中「性能优化」的要求',
            jdEvidence: '熟悉性能调优与高并发场景',
            needsUserConfirmation: true,
            confirmationHint: '「12 个接口」「480ms→120ms」为示例数字，请替换为真实数据',
          },
        ],
      },
    },
    {
      type: 'step.updated',
      stepId: 's3',
      status: 'DONE',
      summary: '3 条改写建议',
      durationMs: 8760,
    },
    { type: 'step.updated', stepId: 's4', status: 'RUNNING' },
    {
      type: 'tool.call',
      stepId: 's4',
      toolCallId: 'tc_4',
      name: 'board.create_application',
      args: { jobId: 'job_mock_1', status: 'TODO', nextActionAt: null },
      source: 'local',
      access: 'EXTERNAL_SIDE_EFFECT',
    },
    {
      type: 'approval.required',
      stepId: 's4',
      approvalId: 'ap_mock_2',
      toolCallId: 'tc_4',
      name: 'board.create_application',
      args: { company: '字节跳动', title: '后端开发工程师-校招', status: 'TODO' },
      reason: '会在你的求职看板里新建一条投递记录',
      expiresAt: '2026-09-11T21:00:00+08:00',
      editableFields: ['status', 'nextActionAt'],
    },
    { type: 'approval.resolved', stepId: 's4', approvalId: 'ap_mock_2', decision: 'APPROVE' },
    {
      type: 'tool.result',
      stepId: 's4',
      toolCallId: 'tc_4',
      name: 'board.create_application',
      ok: true,
      resultPreview: '已在看板新建：字节跳动 · 后端开发工程师-校招',
      durationMs: 420,
      truncated: false,
    },
    {
      type: 'ui.card',
      stepId: 's4',
      cardId: 'card_board_1',
      cardType: 'board_update',
      payload: {
        application: {
          id: 'app_mock_1',
          company: '字节跳动',
          title: '后端开发工程师-校招',
          status: 'TODO',
          nextActionAt: null,
        },
        change: 'CREATED',
      },
    },
    { type: 'step.updated', stepId: 's4', status: 'DONE', summary: '已加入看板', durationMs: 690 },
    { type: 'text.delta', text: '我在字节和美团各找到 3 个后端校招岗，' },
    { type: 'text.delta', text: '其中「字节跳动 · 后端开发工程师-校招」' },
    { type: 'text.delta', text: '与你的匹配度最高，78 分。' },
    { type: 'text.delta', text: '\n\n主要差距在分布式事务和性能调优的经验描述上——' },
    { type: 'text.delta', text: '你项目里写过缓存优化，但没有量化结果。' },
    { type: 'text.delta', text: '我按这条 JD 提了 3 处改写建议，' },
    { type: 'text.delta', text: '其中一条的示例数字需要你替换成真实数据，' },
    { type: 'text.delta', text: '确认后就能生成新版本。[1]' },
    {
      type: 'citation',
      sources: [
        {
          docId: 'kb_backend_model',
          title: '后端岗位能力模型 v2',
          snippet: '分布式事务、缓存一致性、性能调优是校招后端岗位的高频考察点。',
          score: 0.87,
          collection: 'builtin',
          index: 1,
        },
      ],
    },
    {
      type: 'memory.used',
      facts: [
        { id: 'mem_goal', fact: '目标：2027 届后端开发校招' },
        { id: 'mem_pref', fact: '不接受大小周' },
      ],
    },
    {
      type: 'usage',
      model: 'qwen3-mock',
      promptTokens: 21480,
      completionTokens: 3260,
      costEstimate: 0.031,
      fallbackUsed: false,
    },
    {
      type: 'run.completed',
      messageId: 'msg_mock_assistant',
      status: 'COMPLETED',
      durationMs: 24700,
    },
  ]

  const startedAt = Date.now()
  return drafts.map((draft, index) => ({
    ...draft,
    id: index + 1,
    runId: RUN_ID,
    ts: new Date(startedAt + index * 120).toISOString(),
  })) as AgentEvent[]
}

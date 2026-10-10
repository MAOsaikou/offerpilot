// Synthetic, fixed HTTP fixtures. This Node-only harness is never imported by Vite.
const ts = "2026-10-11T02:00:00Z";
export const user = {
  id: "perf-user",
  email: "perf@example.invalid",
  nickname: "性能测量",
  roles: ["USER"],
};
export const token = `fixture.${Buffer.from(JSON.stringify({ exp: 4102444800 })).toString("base64url")}.unsigned`;
const jobs = Array.from({ length: 8 }, (_, i) => ({
  id: `job_perf_${i}`,
  externalId: `perf_${i}`,
  site: "example",
  company: `示例企业 ${i + 1}`,
  title: "Java 后端开发工程师 · 校招",
  locations: "上海",
  category: "研发",
  nature: "campus",
  department: "研发部",
  updatedAt: ts,
  url: "https://example.invalid/jobs",
  source: "LOCAL_SNAPSHOT",
  snapshotDate: "2026-10-11",
  description: "参与业务接口开发与性能优化。",
  requirement: "熟悉 Java、SQL 与缓存。",
  detailFetchedAt: ts,
}));
const applications = jobs.slice(0, 5).map((job, i) => ({
  id: `app_perf_${i}`,
  jobId: job.id,
  status: ["TODO", "APPLIED", "INTERVIEW", "OFFER", "CLOSED"][i],
  note: "准备项目经历与面试问题",
  nextAction: "",
  nextActionAt: null,
  createdAt: ts,
  updatedAt: ts,
  job,
}));
const resumes = Array.from({ length: 3 }, (_, i) => ({
  id: `rs_perf_${i}`,
  title: `后端校招简历 ${i + 1}`,
  parseStatus: "READY",
  parseProgress: 100,
  parseError: null,
  currentVersionId: `rv_perf_${i}`,
  currentVersion: null,
  fileName: `resume-${i + 1}.pdf`,
  createdAt: ts,
  updatedAt: ts,
}));
const conversation = {
  id: "conv_perf",
  title: "岗位与简历规划",
  mode: "AUTO",
  pinned: false,
  createdAt: ts,
  updatedAt: ts,
};
const meta = {
  source: "LOCAL_SNAPSHOT",
  snapshotDate: "2026-10-11",
  sites: ["example"],
  cached: false,
  truncated: false,
  skipped: [],
  degradedSites: [],
};
const cards = [
  {
    type: "card",
    cardId: "card_jobs",
    cardType: "job_card",
    payload: {
      schemaVersion: 2,
      criteria: { query: "Java", site: "", nature: "campus", location: "" },
      items: jobs.slice(0, 3),
      returnedCount: 3,
      fetchedAt: ts,
      meta,
    },
  },
  {
    type: "card",
    cardId: "card_match",
    cardType: "job_match",
    payload: {
      id: "jm_perf",
      jobId: jobs[0].id,
      resumeVersionId: "rv_perf_0",
      score: 78,
      dimensions: { skills: 82, experience: 70, education: 90, keywords: 72 },
      matched: [
        {
          requirement: "熟悉 Java 与 Spring",
          evidence: "/skills",
          strength: "STRONG",
        },
      ],
      gaps: [
        {
          requirement: "分布式事务",
          severity: "MEDIUM",
          suggestion: "补充项目中的一致性方案",
        },
      ],
      advice: ["突出缓存实践与实际贡献"],
      citations: [],
    },
  },
  {
    type: "card",
    cardId: "card_board",
    cardType: "board_update",
    payload: {
      application: {
        id: applications[0].id,
        company: jobs[0].company,
        title: jobs[0].title,
        status: "TODO",
        nextActionAt: null,
      },
      change: "CREATED",
    },
  },
];
const messages = [
  {
    id: "msg_user",
    role: "USER",
    plainText: "请分析 Java 校招岗位与简历的匹配情况。",
    parts: [],
  },
  {
    id: "msg_assistant",
    role: "ASSISTANT",
    plainText:
      "## 岗位分析\n\n下面是三份岗位快照、匹配报告和看板记录。\n\n- 核对真实项目贡献\n- 准备缓存与事务的面试案例",
    parts: cards,
  },
].map((m) => ({
  ...m,
  conversationId: conversation.id,
  runId: "run_perf",
  model: "fixture",
  status: "COMPLETED",
  createdAt: ts,
}));
const interview = {
  id: "iv_perf",
  role: "Java 后端工程师",
  kind: "项目与经历",
  questionIndex: 0,
  completed: false,
  createdAt: ts,
  updatedAt: ts,
  answers: [
    "请介绍一个你参与过的项目，以及你的具体贡献。",
    "你如何定位一次性能问题？",
    "请描述团队协作中的挑战。",
    "你怎样处理失败？",
    "你希望如何成长？",
  ].map((questionText, questionIndex) => ({
    questionIndex,
    questionText,
    answerText: "",
  })),
};
export function fixture(path) {
  switch (path) {
    case "/api/v1/auth/verify":
      return { valid: true, userId: user.id, roles: user.roles };
    case "/api/v1/generation-tasks":
      return [];
    case "/api/v1/applications":
      return applications;
    case "/api/v1/resumes":
      return { items: resumes, total: resumes.length, page: 1, size: 20 };
    case "/api/v1/jobs/sites":
      return {
        sites: [
          { id: "example", name: "示例企业", supportedNatures: ["campus"] },
        ],
      };
    case "/api/v1/jobs":
      return { items: jobs, fetchedAt: ts, meta };
    case "/api/v1/conversations":
      return { items: [conversation], total: 1, page: 1, size: 20 };
    case "/api/v1/conversations/conv_perf":
      return conversation;
    case "/api/v1/conversations/conv_perf/messages":
      return { items: messages, hasMore: false };
    case "/api/v1/interviews/iv_perf":
      return interview;
    default:
      throw new Error(`Unhandled fixture: ${path}`);
  }
}
export const routes = [
  { name: "dashboard", path: "/", ready: ".dashboard" },
  { name: "chat", path: "/chat/conv_perf", ready: ".card-reveal article" },
  { name: "resume", path: "/resumes", ready: 'a[href="/resumes/rs_perf_0"]' },
  { name: "jobs", path: "/jobs?q=Java", ready: 'a[href="/jobs/job_perf_0"]' },
  { name: "board", path: "/board", ready: ".kanban" },
  { name: "interview", path: "/interviews/iv_perf", ready: ".interview-stage" },
];

# 成长教练复用 STAR 故事库

- 只读：`ExpertRegistry.withStories` 对 `COACH` 与 `INTERVIEWER` 同样附加 `<star-story-data>`（转义、不执行指令）。
- 查询优先当前轮 `noteQuery`（最长 200），否则角色；教练默认「项目 技能 面试 STAR」。
- 写入成长任务仍走既有 `adoptGrowthPlan` 审批；本刀不新增聊天写 STAR。
- 路线图第 2 周资源文案提示可引用已入库故事。

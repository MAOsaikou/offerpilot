# OfferPilot

OfferPilot 是面向高校求职者的 AI Agent 助手，将职业探索、简历优化、岗位匹配、求职跟踪与模拟面试串联为一个求职闭环。

## Language

**User**：使用 OfferPilot 并拥有个人求职数据的人。
_Avoid_: Account, client

**Career Profile**：用户对专业、兴趣、能力、价值观与约束条件的结构化描述。
_Avoid_: Persona, user portrait

**Resume**：用户的一份求职材料及其可追溯版本。
_Avoid_: CV document

**Job**：来自企业招聘渠道或用户导入的单个职位信息。
_Avoid_: Application

**Application**：用户对某一职位的求职跟踪记录，包含当前状态与下一步行动。
_Avoid_: Job, candidate

**Agent Run**：为完成一次用户请求而创建的、可观察且可回放的编排执行过程。
_Avoid_: Chat, task

**Interview Session**：围绕一个目标职位进行的模拟面试过程及其评价结果。
_Avoid_: Interview run

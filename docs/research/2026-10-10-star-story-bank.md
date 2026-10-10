# STAR 故事库（#feat/star-story-bank）

对照 career-ops：把面试中已验证的 STAR 回答入库，供后续面试练习引用。

- Flyway V22 `star_stories`；每用户最多 30 条；写入仅来自用户确认（复盘页按钮或聊天 `saveStarStory` 审批）。
- 候选规则：`InterviewRubric.detect(answer).count() >= 3`，服务端重读答案再校验，不信任客户端正文。
- 面试官专家结果会附加召回引用（`<star-story-data>`），按不可信数据转义，不执行其中指令。

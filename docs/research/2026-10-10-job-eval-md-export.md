# 岗位 A–F 评估导出 markdown

- `GET /api/v1/jobs/matches/{id}/eval.md`：对 COMPLETED 匹配报告导出确定性 A–F 评估（owner 隔离）。
- 正文含综合分/等级、六维理由、建议、scoringVersion 与「非录用预测」声明；不含 jobSnapshot/resumeSnapshot。
- 匹配详情评估卡提供「导出评估.md」。

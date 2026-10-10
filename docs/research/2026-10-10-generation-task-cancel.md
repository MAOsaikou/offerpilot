# 生成任务取消

- `POST /api/v1/generation-tasks/{kind}/{id}/cancel`，kind ∈ JOB_MATCH / RESUME_OPTIMIZE / RESUME_DIAGNOSIS。
- 改写/诊断复用既有 cancel（含流取消信号）；匹配新增 CANCELLED（Flyway V23）并在保存前 `WHERE status='RUNNING'` 使取消优先生效。
- 进度坞「取消」按钮；完成后从 RUNNING 列表消失。非所有者 404；未知 kind 400。

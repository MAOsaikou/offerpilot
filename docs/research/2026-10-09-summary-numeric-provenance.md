# 简历摘要数字来源约束对比（2026-10-09）

RS-05 基线首次结构化校验为 7/10。三例失败均为 `/summary` 从其他项目或经历借入数字，生产校验器按 `numbers(from).containsAll(numbers(value))` 正确拒绝。此次只强化提示词与离线回归证据，不放宽校验器、不改变 JSON schema 或生产重试策略。

## 对比

| 指标 | RS-05 基线 | C2 新提示词（本轮 live） |
| --- | --- | --- |
| 首次结构化校验 | 7/10（70%） | **10/10（100%）** |
| 一次修复后的最终校验 | 基线完整结果未在此独立复核 | 10/10（本轮均首次通过，无修复调用；modelAttempts 合计 10） |
| 摘要借数字的首次失败 | java-contributor、cross-project-numbers、bilingual-contributor | 三例均首次通过；摘要改为定性改写或保持不引入他处数字 |
| 预声明空建议 | unrelated-career、already-clear、missing-experience | 三例仍为空建议 |
| 基线第四个空输出 | resume-injection（数据集未标 expectEmpty） | 本轮产出 2 条通过校验的补丁，审阅说明忽略越权指令；不计入预声明空建议回归失败 |

本任务首次通过目标为至少 9/10，已达标。模型 `deepseek-flash`，temperature=0.3。运行 ID：`2026-10-09T09-48-59.374319900Z-7b32fcdb-3c3b-46b7-8f65-b2a91dcbaa09`。用量 inputTokens=7919、outputTokens=26745；整例 wall P50/P95=11965/23771 ms。提示词 SHA-256：`1cac584598161d70fa81cac398c96569207dc425ea733c269d90eacd3ae66d87`。

原始 results、prompt、human-review、summary 与 review 保留在本机 `E:\op-wt\c2-live-out\2026-10-09-174850\`（未入库）。人工无编造率与采纳率仍为 `PENDING_TWO_HUMAN_REVIEWERS`，本报告不宣称人工门禁通过。

## 改动

`optimize.st` 保留原规则，明确所有补丁的数字、百分比和数量只能来自同条 `from`；`/summary` 不能搬入经历、项目或技能中的指标。摘要原文无数字时定性改写或保持不变，指标改写只放在原本含该指标的 bullet。新增「3个接口」「20%」「1000条记录」「2 APIs」的违规例和正确处理例，重申不得编造或借用 JD 数字。

`ResumeOptimizationSummaryProvenanceRegressionTest` 不调用模型，默认随 Maven test 执行：三个参数化用例要求基线首次输出抛出 `BusinessException / STRUCTURED_OUTPUT_INVALID`，移除违规摘要后的其他补丁仍通过；另一个用例验证不新增数字的摘要可通过。`ResumeOptimizationValidator.java` 未修改。

## 离线验证

定向 Java：`ResumeOptimizerTest` 4、`ResumeOptimizationBenchmarkTest` 3、`ResumeOptimizationSummaryProvenanceRegressionTest` 4 全部通过。Python `unittest discover -s backend/eval` 6 项通过。本机非 Docker 全量 Maven test：Docker 相关 9 类因本机无 Docker Desktop 未能启动；其余模块测试通过，摘要回归与基准默认执行。CI 负责跑通容器集成测试。


## 日1019 复测（main `d05e66f`）

提示词在 C2 之后又经价值过滤（#20）增补「禁止近义词凑数」等规则，当前 `optimize.st` SHA-256：`30cf6b2f7c27af265da174e773476b7efc89a7ba3c26b88412978c0901f91512`。数字来源与 `/summary` 约束（第 8–12 行）仍在，生产 `ResumeOptimizationValidator` 未放宽。

| 指标 | C2（2026-10-09） | 日1019 复测 |
| --- | --- | --- |
| 首次结构化校验 | 10/10 | **10/10**（`firstAttemptRate=1.0`） |
| 摘要借数字三例 | 首次通过 | `java-contributor` / `cross-project-numbers` / `bilingual-contributor` 均首次通过；后两者本轮无 `/summary` 补丁，前者亦无摘要补丁 |
| 预声明空建议 | 仍为空 | `unrelated-career` / `already-clear` / `missing-experience` 均为 `patches=[]` |
| 模型 | deepseek-flash | deepseek-flash，temperature=0.3 |
| 运行 ID | `2026-10-09T09-48-59…` | `2026-10-19T09-50-…-r2`（首跑 `unrelated-career` 传输 EOF/`MODEL_UNAVAILABLE`，同日立即重跑全绿） |

离线：`ResumeOptimizationSummaryProvenanceRegressionTest` 4、`ResumeOptimizerTest` 5、`ResumeOptimizationValueFilterTest` 11、Python `unittest discover -s backend/eval` 6 全部通过。原始 evidence 仅存本机 `/workspace/docs-eval-local/resume-optimize-provenance-2026-10-19-r2/`（未入库）。人工门禁仍为 `PENDING_TWO_HUMAN_REVIEWERS`。

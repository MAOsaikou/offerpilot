# 简历定向改写真实模型基准

`resume-optimize.json` 包含 10 组完全虚构的简历与 JD。JSON 使用项目已有 Jackson 读取，不引入 YAML 依赖。

通过 `ResumeOptimizationLiveEvaluationTest` 调用生产 `ResumeOptimizer`、提示词、`ModelGateway` 和校验器；不启动 Spring 应用、不连接数据库、不调用岗位站点、不采纳或保存用户简历。

覆盖职责边界、数字保真、技能熟练度、空经历、岗位不相关、无需改写、双语、JD 注入、简历注入及跨经历事实借用。每例的预期检查在模型调用前确定；词汇哨兵只用于回归，不能代替语义审阅。

每轮使用独立输出目录，保留每次流式原始输出（包括未通过生产校验的尝试）、模型与提示词标识及 SHA-256、输入/输出 token、耗时、错误码和逐条审阅材料。供应商错误正文与密钥不写入结果。

只在设置 `OFFERPILOT_RUN_LIVE_OPTIMIZE=true` 和 `DEEPSEEK_API_KEY` 时调用付费模型。其他变量：`DEEPSEEK_BASE_URL`、`LLM_MODEL_CHEAP`（默认 deepseek-flash）、`OFFERPILOT_OPTIMIZE_EVAL_OUT`（独立输出目录）。运行命令（backend 目录）：

```powershell
.\mvnw.cmd -q -B -pl offerpilot-bootstrap -am '-Dtest=ResumeOptimizationLiveEvaluationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`ResumeOptimizationBenchmarkTest` 默认执行，只验证基准结构、边界覆盖和证据审阅材料生成，不访问模型。

也可在项目根运行 `powershell -NoProfile -File backend/eval/run-resume-optimize.ps1` 做离线检查；加 `-Live` 才运行真实模型。脚本仅在进程没有相应值时从忽略的 `backend/.env.local` 加载 DeepSeek Key、地址与文字模型名，退出时恢复环境，不加载数据库设置。每次默认使用新的 `docs/eval-results/resume-optimize/<时间与UUID>/` 目录。已有结果目录会拒绝覆盖。

人工评分完成后运行 `python -B backend/eval/summarize-resume-optimize.py <本轮目录>` 更新摘要。脚本验证评分与原结果的 runId、SHA-256、案例/补丁全集和两位评审身份；评分不完整时不计算人工指标。它不会调用模型。首次结构化校验通过率目标 ≥95% 单独展示，修复后通过不能掩盖首次失败。

`-ReplayResults <绝对results.json路径>` 可以离线回放本轮输出，核对被拒绝尝试确实未通过生产校验、最终原始输出等于交付报告、所有尝试的用量等于报告累计用量。该参数不启用真实模型。

## 审阅口径

- 自动检查：生产结构/引文/数字校验、每例禁止语句、是否误改明确不应改写的输入；分别统计首次校验和修复后通过率。
- Agent 语义审阅：逐条对照本条原文、整个简历和 JD，判断新增事实、贡献范围、熟练度、相关性及表达改善；清楚标注为 Agent 审阅。
- 两名真人评审：独立填写 `human-review.json`。每条建议分别填写 `factSafe`、`adopt`、`reason`；空建议案例填写 `emptyAppropriate`、`reason`。没有填写时保持 null，不能计为拒绝、通过或采纳。
- 人工无编造率 = 两名评审一致判定事实安全的建议数 / 全部成功生成建议数；分歧需另行裁决。人工采纳率分别展示两人的采纳比例及两人一致采纳比例，门禁采用两人一致采纳比例 ≥60%。失败案例及不恰当空输出单独呈现，不从成功率分母移除。
- 100% 无编造率、≥60% 采纳率必须有完整真人评审证据才可宣称达到；自动哨兵与 Agent 审阅不满足该验收条件。

# 聊天首 token 延迟（AG-02）

`measure.py` 对本地后端的 `POST /api/v1/agent/chat` 计时，用来验收 docs/02 AG-02「首 token < 2s（P95）」。每次请求都用新的访客身份，模型档位为 FLASH，只依赖 Python 标准库，不读取密钥。

```bash
python3 measure.py --base http://127.0.0.1:18082 --runs 20 --mode AUTO
```

输出每次的 `started`（到 run.started）、`first`（到第一个 text.delta）、`total`（到 run.completed）秒数，最后给出 P50/P95/最大值。结果记录在 docs/10「真实模型延迟」。

AG-02 AUTO 简历快速路由的定向样本（四种整句写法问题，各三次，新访客、无历史/选择）：

```bash
python3 measure.py --base http://127.0.0.1:18082 --runs 12 --mode AUTO --question-set resume
```

`AGENT_RESUME_ADVICE_FAST_PATH` 默认 true；需要对照时重启隔离后端并设为 false。保持 `AGENT_SPECULATIVE_REPLY=true`，不要为改善测量而关闭 SC-01 输入分类器。报告中注明分类器开关、样本集合与成功数；定向结果不能替代 AUTO 混合问题的 P95 验收。脚本输出 completed/errors 和各阶段实际 n，失败不补跑；鉴权/网络异常会直接终止，须报告已完成数。

## 停止计时（AG-07）

```bash
python3 measure_stop.py --base http://127.0.0.1:18082 --runs 10 --after 1
```

收到首个 `text.delta` 后再等 `--after` 秒调用取消接口，输出取消接口耗时 `cancel_api`、从发出取消到流结束的 `stream_end`、取消返回后仍收到的 `late_deltas`，以及会话里助手消息的最终状态（应为 `CANCELLED`）。

## SC-01 并行对照（AG-02）

保持 `offerpilot.llm.injection-classifier-enabled=true`，启动隔离数据库与两个同配置实例：18082 的 `AGENT_PARALLEL_SAFETY_SCREEN=true`，18083 为 false。`AGENT_EARLY_EXPERT_HANDOFF=true` 在两侧相同，避免把专家交接变化归因于筛查开关。20轮/组，每轮交替先后顺序，五类问题各4轮（含真实岗位搜索请求）：

```bash
python3 measure.py --runs 20 --mode AUTO --question-set acceptance   --base http://127.0.0.1:18082 --control-base http://127.0.0.1:18083   --output /tmp/ag02-acceptance.json
```

计时只认首个真实 `text.delta`，不把 `run.started`/计划/工具事件当首 token；输出原始行、每组整体与每类 P50/P95、成功/错误数，不剔除或补跑失败。样本含无历史、无选择的新访客；历史/图片/所选资源/审批/取消由离线回归覆盖，不以该样本宣称并发或生产SLO。DEBUG 日志的 `AG02 phase=safety/planner/speculative-reply/expert-reply` 只含耗时、运行标识和终态，不含输入正文或凭据。实际结果见 docs/10 与 docs/research。

## 本轮架构优化与阶段诊断（待 live 实测）

以 `bd72548`（整体/岗位 P95 2.831s/2.255s）的同配置构建为整套改动对照，不能把新开关 false 当作整套改动的基线。本轮包括笔记读取与规划/专家读取并行、规划保留原文以共享本轮 SC-01 筛查、DeepSeek 客户端共享 HTTP 连接池、delta 交付下游后再写节流 checkpoint；未缓存答案、缩减证据或关闭安全。`AGENT_PARALLEL_NOTE_RETRIEVAL=false` 仅恢复笔记串行读取。保留联合岗位规划 #146、早期动作交接等既有开关在两侧一致。

在外部有模型/数据库/端口权限的环境，启动新构建 `18082`、基线 `18083`（相同隔离数据库夹具、供应商/FLASH/SC-01 配置，安全分类器必须开启）。为新实例设置以下环境变量，再启动后端；这些日志不包含输入、证据或凭据，不要开启 HTTP wire/body 日志：

```bash
export LOGGING_LEVEL_COM_OFFERPILOT_LLM=DEBUG
export LOGGING_LEVEL_COM_OFFERPILOT_AGENT=DEBUG
export LOGGING_LEVEL_COM_OFFERPILOT_KNOWLEDGE_APPLICATION=DEBUG
export AGENT_PARALLEL_SAFETY_SCREEN=true
export AGENT_JOINT_JOB_SEARCH_PLANNING=true
export AGENT_PARALLEL_NOTE_RETRIEVAL=true
```

从仓库根目录测量，100轮/组（五类各20轮），交错先后顺序；脚本中的 parallel/serial 只是实例标签，此处分别代表新构建/基线：

```bash
python3 backend/eval/chat-latency/measure.py \
  --base http://127.0.0.1:18082 --control-base http://127.0.0.1:18083 \
  --runs 100 --mode AUTO --question-set acceptance \
  --output /tmp/ag02-sub2s-live.json
python3 backend/eval/chat-latency/measure_stop.py \
  --base http://127.0.0.1:18082 --runs 10 --after 1
```

输出新增 `run_id`，可关联 `AG02 run=<run_id>` 日志。阶段如下：

| 阶段 | 含义 |
| --- | --- |
| attachments / input-rules / quota-preflight | 附件读取、同步规则、配额预检 |
| memory-settings / memory-recall / memory-candidate | 记忆开关、召回与候选检查 |
| conversation-begin / prepared | 运行/消息持久化、同步准备完成 |
| input-safety / prompt-safety / effects-gate | 原输入筛查、每个模型 Prompt 筛查、业务副作用等待 |
| planner / intent-model / resume-action-model / job-action-model | 规划总阶段及各内部模型流（联合岗位路径没有独立 job-action-model） |
| selected-context / expert-类型 | 所选资源解析及必要专家执行 |
| note-retrieval / embedding | 笔记召回、向量调用（有向量且实际调用时才出现） |
| model-admission / provider-setup / provider-stream / provider-first | 每次实际生成准入、适配器准备、流与首块；供应商首块仍可能被审核门控扣留 |
| speculative-reply / expert-reply | 已经过网关的普通预取/最终专家流及其首块 |
| first-text / checkpoint / event-persist | ChatService 正文交付边界、消息快照、SSE 中继事件写入 |

`ChatLatency` 行包含 trace/run、startMs/elapsedMs/sinceRequestMs/signal。准备阶段 run=pending，用同一 trace 的 prepared 行关联实际 run；embedding/event-persist 以 run 关联。重叠阶段不能相加当作 TTFT。`first-text` 在 ChatService 边界，RunRelay 仍先落库再交付 SSE，因此验收只认脚本的 `first`，不能用 provider-first、plan 或 first-text 替代。

保留完整轮次、失败数、整体和各类别 P95；不剔除冷请求/长尾，不补跑失败。鉴权/网络异常会直接终止，保留终止前输出并报告完成数。新访客样本不包含笔记向量/长期记忆/历史，不能据此宣称本地 bge-m3 或有历史场景达标；这些场景须另用隔离的所属用户夹具实测。本沙箱仅做桩验证，整体与岗位是否 <2s：**待 live 实测**。

离线验证（后端目录，标准环境）：

```bash
bash mvnw -o -B -pl offerpilot-agent -am test
bash mvnw -o -B -pl offerpilot-bootstrap -am \
  -Dtest=ChatLatencyPipelineIntegrationTest,JointJobSearchConfigurationTest,ChatHttpIntegrationTest,SseStreamHttpIntegrationTest,RunRelayPersistenceIntegrationTest,DeepSeekGatewayTest,ParallelSafetyScreenTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`ChatLatencyPipelineIntegrationTest` 用实际 Spring 装配、发布提示词、ChatService/Orchestrator/ModelGateway 安全链路，只桩化模型端口与存储端口，不需要 socket/Docker；覆盖检索在规划/分类未完成时启动、原文筛查仅一次、新证据单独筛查与候选丢弃、取消清理、输出泄漏截断。真实 HTTP/持久化与连接复用测试需要本地端口和 Docker，不以源码编译代替通过。


## 2026-10-21：规则先行与只读专家提前生成（待 live 实测）

两个开关均默认 true，设 false 并重启分别恢复本切片之前的对应行为：

| Spring 配置 / 环境变量 | true | false |
| --- | --- | --- |
| `offerpilot.agent.early-readonly-expert` / `AGENT_EARLY_READONLY_EXPERT` | 整个计划均为已审计本地只读动作，或确定性规则单步岗位搜索时，读取与最终叙述不等待 effects-gate；搜索快照写入及正式卡片仍等待原输入放行 | 所有计划仍先等待 effects-gate |
| `offerpilot.agent.rule-first-routing` / `AGENT_RULE_FIRST_ROUTING` | 无历史、无所选资源、无图片的 AUTO（含未指定 mode）整句职业探索/简单岗位搜索跳过规划及动作模型，并省去必然丢弃的普通回复预取 | 恢复原模型规划/联合岗位参数解析及普通回复预取；既有简历/教练规则保持 |

规则采用完整匹配、100字上限；不是关键词包含。职业语法包括想/希望探索或了解职业、适合什么职业、明确的“不知道自己适合什么职业”，可接固定的探索起步问题或简短回答要求；“不知道”是该语法的一部分，不接受拒绝推荐等动作否定。岗位语法包括请/帮我 + 找/寻找/搜/搜索/推荐 + 可选白名单城市 + 已知技能/岗位方向 + 可选实习/校招/社招 + 岗位/职位/工作。城市、query、nature、空 site 产生与 JobIntentParser 同一参数契约，复用原搜索来源、本地/实时合并、卡片和证据；叙述使用 CardNarrationDigest，不生成岗位结果。

正例：`我希望了解适合自己的职业方向？`、`我还不知道自己适合哪种职业？`、`寻找杭州的用户研究实习职位`、`请搜索北京市Python数据分析校招职位`、`搜C++嵌入式开发工程师社招工作！`。反例：`我不想探索职业方向`、`我想探索职业方向并保存推荐`、`找上海的腾讯Java实习岗位`、`找火星的Java岗位`、`找上海北京的Java岗位`、`找Java和Python岗位`、`帮我找工作`、`找上海的Java岗位，月薪30k`、追加换行/注入/写操作。未知城市、企业、额外条件、复合域、历史或资源选择均回落。`全职` 不等于 `social`，既有搜索枚举无法准确表达该限制，故不走规则，不丢掉限制扩大搜索。

### ExpertRegistry 逐动作审计

| 动作 | 提前执行 | 依据 / 保留 gate 的原因 |
| --- | --- | --- |
| RESUME advice | 是 | 固定文字指导；KnowledgeNoteContext 按 owner 只读原文及可选查询向量，不生成/保存诊断或改写 |
| COACH planLearning | 是（无所选岗位/投递/匹配） | DefaultCoachPlanForChat 读取 profiles.get、职业技能、所属简历和已完成匹配/面试材料，规则计算卡片；附加 owner 隔离笔记及已入库 STAR，不同步任务 |
| CAREER_DISCOVERY recommend | 是 | DefaultCareerRecommendForChat 读取 profiles.get（不调用会导入记忆的 load）、测评与职业库，保留完整混合检索/本地向量及来源，不调用创建推荐修订/档案保存 |
| JOB search | **仅规则单步搜索** | ReadOnlyRuleRoute 只从完整匹配语法提取白名单城市、技能/岗位关键词、类型及空 site；内部 ruleRoute 标志不接受模型 JSON。prepareRuleSearch 跳过二次模型解析，执行原本地/实时合并召回，但只在本次请求内暂存标准化快照。beforeEffects 完成且原输入判定干净后才执行原 upsert，按来源身份去重，每快照一次；取得正式 ID 后才发工具结果/卡片。命中、取消或写入失败丢弃候选正文，不发暂存 ID。模型规划、混合计划、显式 JOB 和关闭开关保持原 gate |
| JOB getApplicationDetail；RESUME read_optimization / 已有诊断报告读取 | 否 | 保守保留所选资源解析及现有 gate，不因读取报告路径与生成路径共用而放行 |
| INTERVIEWER scoreInterview / generateReport / completedInterview / recallStarStories | 否 | 保留会话准备及原 gate，不把整个专家或 READ 标签当纯读取授权 |
| match、diagnose/diagnosis、optimize | 否 | 分析/匹配落库和审批保持 |
| createApplication / updateApplicationStatus、setCareerTarget / adoptTargetCareer / rerankCareers、adoptGrowthPlan、applyPatches、start/continue/answer/completeInterview、saveStarStory、saveUserFact | 否 | 写入或审批；仍先等 beforeEffects，记忆入口单独保留 gate |
| clarify / default / 未知动作、混合计划、所选 jobId/applicationId/matchId/jobMatchId | 否 | 默认拒绝提前执行；必须整个计划全部命中只读白名单 |

提前读取本身不创建业务记录；规则搜索的 MCP/实时快照仅存在本次请求内存中。usage/配额及运行/事件日志仍照常记录，不冒充业务写入；推测生成已发生的调用仍按网关计入额度与 UsageJournal（供应商未报告 token 的局限保持）。最终叙述唯一入口仍是 ChatService → ModelGateway.stream → screenedStream → ScreenedGeneration.release：原文/新增证据未完成 SC-01 时扣留正文，命中取消候选并按既有类别告警 Prompt 重生成；未放行 token 不进入 text.delta/checkpoint/最终消息。SC-03 仍为尾部滑动窗口流式审核，没有整段缓冲。只读 plan/tool/card/citation 事件可以早于输入筛查完成；规则搜索的 plan/运行中工具事件可提前，成功工具结果与完整 job_card/campus_calendar 等待快照提交后下发。事件类型、相对顺序及单调 seq 保持；它们不计首 token。

提前搜索还要求本轮存在共享输入 gate；关闭 parallel-safety-screen 时保持原串行路径，无绕过检查的搜索暂存提交。

新增日志 `rule-route`（规则生成计划）、`rule-job-retrieval`（规则岗位实际检索，不含等待提交）、`effects-gate`（快照等待原输入）、`early-expert`（跳过业务副作用等待的阶段标记）；实际专家读取继续看 `expert-类型`，最终模型与放行看 `provider-first` / `expert-reply` / `first-text`。不记录原文、证据或凭据。

对照使用**相同本轮构建**和两个隔离同配置实例，SC-01 分类器与 SC-03 始终开启，保留既有笔记并行/简历快速路由/联合岗位规划等开关相同：

1. 整体组合：18082 两项 true；18083 两项 false。
2. 归因只读提前：两侧 rule-first-routing=true，只切 early-readonly-expert。
3. 归因规则规划：两侧 early-readonly-expert=true，只切 rule-first-routing。

每组重新启动后运行上文 `measure.py --runs 100 --mode AUTO --question-set acceptance --control-base ...` 的交错命令，保留失败/各类别及整体 P95；验收仅认真实模型首个 text.delta。规则岗位路径将 SC-01 与检索/叙述重叠，最终正文仍受原输入/证据 SC-01 和 SC-03 门控，正式卡片还等待快照提交；模型岗位路径保留串行 gate。无 live 数字写入，AG-02 保持进行中。

可选 `offerpilot.agent.speculative-expert` 本轮未实现：避免增加未经完整规划验证的专家读取、额外模型流及取消/额度复杂度；规则未命中继续原规划路径。

新增离线定向测试（仍在 backend）：

```bash
bash mvnw -o -B -pl offerpilot-agent -am \
  -Dtest=ReadOnlyRuleRouteTest,EarlyReadonlyExpertTest,ChatParallelEffectsTest,JointJobSearchPlanningTest,ResumeAdviceFastPathTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
bash mvnw -o -B -pl offerpilot-bootstrap -am \
  -Dtest=ChatLatencyPipelineIntegrationTest,JointJobSearchConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

完整必跑项仍为上文未过滤的 agent reactor 与 bootstrap 定向命令；socket/Docker 受限时单独记录阻塞，不将编译通过称为 IT 通过。新增安全流水线用可控制的原输入/证据判定，覆盖提前执行与叙述、干净放行/原输入命中重生成、证据命中、关闭恢复、取消及 SC-03 泄漏截断。


16ba37b 上一切片沙箱结果（本次续作结果见下文）：未过滤 agent / bootstrap 命令均已尝试，受本地端口权限阻塞；单独 HTTP/持久化 IT 又确认端口/Docker 不可用。预加载本地 Byte Buddy agent、设置 headless 并明确排除7类 socket 测试后，agent reactor **578项通过**；上面两类 bootstrap 无 socket 定向测试 **7项通过**。完整命令、排除清单和断言覆盖见 [docs/10](../../../docs/10-测试与评估方案.md)，真实 IT 留 CI、P95 留 live，不把排除集称为完整验证通过。


### 规则岗位快照推迟与叙述 prompt 对比（本次离线验证）

`JobService.PreparedSearch` 保存本次搜索结果及有限的待保存快照，不保存用户数据或跨请求答案缓存。已有带详情快照先只读，保留原 upsert 不覆盖详情的事实；本地召回不产生待保存项。只冻结本次实际返回的实时快照，超时后的迟到 worker 不能追加持久化操作。原输入 SC-01 命中则结束本轮且无正文/成功卡片/岗位写入；取消亦丢弃暂存。干净时 `beforeEffects()` → `inputSafetyPassed()` → 原 jobs upsert，每个来源身份一次，正式数据库 ID 才出现在卡片中。既有配额、用量审计、SC-03 与其它写入/审批不变。提交失败结束本轮并销毁已启动叙述，避免把未成功保存的搜索叙述为成功。

代码确认此前岗位 TTFT 的额外输入：job_card 未实现摘要，ExpertRegistry 把整个卡片 JSON 放进 summary；其余已有专家使用摘要。岗位与其它专家共用温度 0.3 和配置 maxTokens，证据 prompt-safety 原本已与供应商生成并行，因此未调整温度/预算或新增安全旁路。现在叙述 summary 仅保留来源性质，工具结果/复合计划上游 summary 仍保留原完整 JSON；CardNarrationDigest 只传一次查询条件、返回数、全部已展示岗位的标题/公司/城市/类型/日期/来源和原降级/部分失败/截断元数据；去掉叙述无需复述的 ID、URL、重复站点字段和空字段。URL、完整卡片字段及岗位事实仍在 SSE/消息卡片中，未减少用户可见证据。

`ChatLatencyPipelineIntegrationTest.reportSearchPromptSize` 用实际 Spring、发布系统提示及网关策略，固定五岗位（带 URL、更新/截止日期、部分失败）夹具；以同一卡片重建 16ba37b 的旧数据消息。统计发送给生成端的完整 Prompt.getContents()（消息文本，未计供应商角色封装），没有真实模型调用：

| 输入 | 优化前字符 | 优化后字符 | 变化 |
| --- | ---: | ---: | ---: |
| 完整叙述 prompt | 3487 | 2295 | -34.2% |
| 其中岗位数据消息 | 2371 | 1179 | -50.3% |
| 完整 prompt 估算 token（ceil 字符数/2） | 1744 | 1148 | 仅粗估，非 DeepSeek tokenizer/计费用量 |

这是可复现的离线输入大小对比，真实岗位条数/字段会改变大小；不能据此断言供应商 TTFT 因果比例或岗位 P95 已达标，仍需按上面的 SC-01 开启交错脚本 live 验证。未向 docs/02 写 live 数字。

本次验证：未过滤 agent reactor 已尝试，受沙箱本地端口权限阻塞；沿用上文明确列出的7类 socket 排除项后 **580项通过**。最终受影响定向选择集 **83项通过**，其中 ChatLatencyPipelineIntegrationTest / JointJobSearchConfigurationTest **10项通过**。新增 JobDeferredSnapshotTest 验证真实检索合并无提前 JDBC 写入、重复来源/重复提交只落一次、正式 ID 回填和已有详情保留；流水线覆盖原输入命中零写库/零正文、证据门控、提交失败、取消及非规则/关闭路径。JobsHttpIntegrationTest 的 AUTO 空 site 期望已同步；该类及其它 HTTP/持久化 IT 均已实际尝试，仍受端口/Docker限制，未进入业务断言。日志与完整选择集见 [docs/10](../../../docs/10-测试与评估方案.md)。

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

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

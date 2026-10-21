# CP-01 私有笔记检索评测（待实测）

`fixtures.json` 全部为明确标注的**合成夹具**：8篇虚构笔记、12个问题（10个有相关标注、2个无答案/隔离探针），包含关键词、改写、多相关项、干扰项与两个独立用户。不是用户数据、真实用户基准或模型答案缓存。相关性是人工标签，不参与检索请求。脚本不生成回答，不声称验证回答质量或引用正确性。

离线逻辑检查（标准库，无依赖、无网络）：

```sh
cd backend/eval/private-notes
python3 -m unittest -v
```

live 仅提供以下复跑命令，本切片未调用 embedding/HTTP，未产出质量数字。先在**一次性本地数据库**上正常启动后端，保留身份鉴权、隐私脱敏、安全审核和配额；不要启用 mock-llm。本地 OpenAI 兼容 bge-m3 服务需已安装可用，不在本任务下载模型。后端配置：

```sh
export EMBEDDING_BASE_URL=http://127.0.0.1:11434
export LLM_MODEL_EMBEDDING=bge-m3
export LLM_EMBEDDING_DIMENSIONS=1024
# 本地服务若需要 key，只从环境变量提供 EMBEDDING_API_KEY。
# 正常启动已有后端；保持 offerpilot.knowledge.notes-embed.enabled=true。
python3 backend/eval/private-notes/evaluate.py \
  --api-base http://127.0.0.1:8081 \
  --embedding-base "$EMBEDDING_BASE_URL" --model bge-m3 \
  --output /tmp/private-notes-live.json
```

embedding base 为不带 `/v1` 的服务根地址，探针 POST `/v1/embeddings`，要求有限、非零1024维；与后端必须指向同一个本地服务和模型。地址可配置为localhost/loopback/host.docker.internal/ollama；脚本拒绝远程供应商和URL凭据。报告记录地址、模型、时间、夹具SHA256与逐问原始结果，不保存令牌。每次创建两个全新访客账号、通过正式HTTP写笔记、等待索引、实际调用 owner-scoped search；最后删除本次笔记，访客账号按已有生命周期回收。中断后可重跑，不复用旧答案/结果。使用一次性数据库清理中断遗留数据。

指标只由实际排名计算：Hit@1、Hit@3、Recall@3、截断MRR@3（仅有答案问题为分母）；无答案问题另列negativeEmpty@3；全部问题检查owner泄漏。生产向量搜索总会给最近邻，无答案空结果率是观察项，不臆设拒答能力。任一请求失败计入failedQuestions且正例计零，不从分母消失；向量不可用、索引失败/超时或越权使报告无效，退出非零。没有stub评测模式，也没有预设达标结论。

结果状态：**待实测**。离线测试中的手算数值只验证计算逻辑，不能填入live结果。

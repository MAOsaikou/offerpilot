# 职业库 RAG：百炼 text-embedding-v4 真实对照（2026-10-16）

10-09 的对照用的是测试哈希向量，只能反映字面相似。这次第一次用真实百炼 `text-embedding-v4`（1024 维）给完整职业库（402 条：24 条中文维护 + 378 条 O*NET）建索引，在同一套意图上比较四种排序。启动任务分 41 次请求（每次最多 10 条）完成全部向量，用时约 64 秒；评测查询再调用约 40 次。

复现（需 Docker 和 `BAILIAN_API_KEY`，不进 CI）：

```bash
cd backend
OFFERPILOT_RUN_LIVE_RAG=true bash ./mvnw -q -Plive -pl offerpilot-bootstrap -am test \
  -Dtest=OccupationRagLiveEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false
cat offerpilot-bootstrap/target/occupation-rag-live-eval.md
```

四列含义：
- **rules**：只按 RIASEC 兴趣画像排序。
- **lexical**：兴趣画像加全文检索（中文二元组，OR），把向量隐藏。
- **vector-only**：只取查询向量的余弦近邻，不看兴趣。
- **hybrid**：线上实际路径，即兴趣画像、全文和向量的融合。

第 1–9 条是 10-09 的关键词意图，第 10–19 条是今晚补的学生口语描述。目标职业事先人工指定，每条只有一个。

## 发现的问题：融合把向量结果拉低了

修复前第一次真实运行，Hit@3 结果如下：

| | rules | lexical | vector-only | hybrid |
|---|---|---|---|---|
| Hit@3 | 0/19 | 14/19 | **19/19** | 14/19 |
| Top1 | 0/19 | 6/19 | 18/19 | 8/19 |

真实向量单独用几乎全对，融合后反而跌回和纯全文一样。原因是 `OccupationRecommender.fuse` 把兴趣画像当成一条覆盖全库的 RRF 排名（约 400 项），和最多 16 条的检索结果等权相加。RRF 的 k=60 下，检索前三名之间只差约 0.0005，兴趣排名第 10 和第 50 之间却差约 0.0025。结果用户自己说的话被远处的兴趣先验压过去了。例如“想写安卓和 iOS 应用”的 IRC 用户，向量第一名是移动开发，融合后掉出前三。

我先只把全文和兴趣的权重降到 0.5，重跑后指标没有变化（hybrid 仍是 14/19），说明问题出在结构上，不在权重。

## 修复

- 有查询且检索有结果时，只在检索候选里重排。兴趣画像也只在这个候选短表里排名，以 0.5 权重参与 RRF（没有向量时仍为 1.0）。检索候选之后再按兴趣顺序补位。兴趣零分的职业仍不推荐；没有查询、没有结果或检索出错时，仍严格回退到纯兴趣排序。
- 有向量时，检索内部的全文权重为 0.5（没有向量时仍为 1.0）。
- 推荐理由按实际命中写成“语义检索”或“文本匹配”，并注明按兴趣画像重排。

修复后的结果：

| 指标 | rules | lexical | vector-only | hybrid |
|---|---|---|---|---|
| Hit@3（1–9 关键词） | 0/9 | 8/9 | 9/9 | **9/9** |
| Hit@3（10–19 口语） | 0/10 | 8/10 | 10/10 | **10/10** |
| Hit@3 合计 | 0 | 16 | 19 | **19** |
| Top1 合计 | 0 | 9 | 18 | 15 |

lexical 也从 14 提到 16，因为同一个结构问题也影响无向量的路径。hybrid 的 Top1 比 vector-only 少 3 条（前端、安全×2 的第一位被相近职业占了）。这是有意保留兴趣画像的代价：推荐卡要解释“为什么适合你”，不能只看一句话的语义。

## 本地 bge-m3 复测（免费，不调用百炼）

百炼额度用完后，改用本机 Ollama 运行 BAAI bge-m3（GGUF Q8_0，1024 维，8 核 CPU 无 GPU），用同一修复后代码、同一 19 条意图复测。全库 402 条建索引约 56 秒。

| 指标 | rules | lexical | vector-only | hybrid |
|---|---|---|---|---|
| Hit@3（1–9 关键词） | 0/9 | 8/9 | 9/9 | **9/9** |
| Hit@3（10–19 口语） | 0/10 | 8/10 | 9/10 | **10/10** |
| Hit@3 合计 | 0 | 16 | 18 | **19** |
| Top1 合计 | 0 | 9 | 14 | 16 |

bge-m3 单独用比 text-embedding-v4 略弱（Hit@3 18 对 19，Top1 14 对 18），但融合后 Hit@3 同样是 19/19。所以演示和开发可以用本地免费模型，百炼留作可选。复现方式：在上面的命令前加 `EMBEDDING_BASE_URL=http://localhost:11434 EMBEDDING_API_KEY=ollama LLM_MODEL_EMBEDDING=bge-m3`，部署说明见 [09](../09-部署与运维.md)。切换模型时，V27 `embedding_index_state` 会让启动任务自动清空旧向量并重建。

## 局限（如实记录）

- 只有 19 条意图，每条一个预设目标，由开发者编写，不是用户样本。修复是在看到第一次结果后设计的，存在对本集合过拟合的风险。权重只试过 0.5 一档，没有做网格搜索。
- 职业库本身有重叠（前端/全栈/客户端，安全/O*NET 信息安全分析师），Top1 不一定唯一合理，Hit@3 更有意义。
- 第 19 条（Unity 游戏）三种融合都把相近的 O*NET 条目排在第一，目标在第二。
- 私有笔记检索（`KnowledgeNoteSearch`）仍是等权 RRF，没有真实样本可评，这次没有改。
- mock 哈希向量的 CI 对照（`OccupationRagIntegrationTest`）继续保留，用于防回归，但它的数字不代表语义质量。

原始表见下（加粗为目标）。

### text-embedding-v4

| # | Holland | 查询 | 目标 | rules | lexical | vector-only | hybrid(规则⊕全文⊕向量) |
|---|---|---|---|---|---|---|---|
| 1 | IRC | Java Spring Boot 后端 | occ_backend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_fullstack_dev, **occ_backend_dev**, occ_data_engineer | **occ_backend_dev**, occ_fullstack_dev, occ_bi_analyst | **occ_backend_dev**, occ_fullstack_dev, occ_data_engineer |
| 2 | IRC | 前端 TypeScript Vue | occ_frontend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_fullstack_dev, **occ_frontend_dev**, occ_onet_19-2041.03 | **occ_frontend_dev**, occ_fullstack_dev, occ_client_dev | occ_fullstack_dev, **occ_frontend_dev**, occ_client_dev |
| 3 | ICR | SQL 数据分析 | occ_data_analyst | occ_onet_19-3099.01, occ_fullstack_dev, occ_onet_19-1029.02 | **occ_data_analyst**, occ_onet_15-2051.00, occ_bi_analyst | **occ_data_analyst**, occ_onet_15-2051.01, occ_onet_15-2051.02 | **occ_data_analyst**, occ_bi_analyst, occ_onet_15-2051.02 |
| 4 | IRC | 机器学习 Python | occ_ml_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | **occ_ml_engineer**, occ_data_scientist, occ_onet_15-2051.00 | **occ_ml_engineer**, occ_data_scientist, occ_algo_engineer | **occ_ml_engineer**, occ_data_scientist, occ_algo_engineer |
| 5 | ASE | 产品 需求 用户 | occ_product_manager | occ_onet_27-2042.00, occ_onet_27-3011.00, occ_onet_27-2041.00 | **occ_product_manager**, occ_interaction_designer, occ_onet_27-1021.00 | **occ_product_manager**, occ_onet_41-4011.00, occ_onet_11-3051.00 | **occ_product_manager**, occ_interaction_designer, occ_onet_41-4011.00 |
| 6 | AIR | 设计 Figma | occ_ui_designer | occ_onet_27-3023.00, occ_onet_15-1255.01, occ_onet_27-3043.05 | **occ_ui_designer**, occ_onet_27-1021.00, occ_onet_15-1255.01 | **occ_ui_designer**, occ_interaction_designer, occ_onet_15-1255.00 | **occ_ui_designer**, occ_interaction_designer, occ_onet_27-1021.00 |
| 7 | IRC | 测试 自动化 | occ_test_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_functional_qa, **occ_test_dev**, occ_perf_engineer | **occ_test_dev**, occ_functional_qa, occ_perf_engineer | **occ_test_dev**, occ_functional_qa, occ_perf_engineer |
| 8 | IRC | 运维 Docker Kubernetes | occ_devops_cloud | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_fullstack_dev, **occ_devops_cloud**, occ_sre | **occ_devops_cloud**, occ_sre, occ_fullstack_dev | **occ_devops_cloud**, occ_fullstack_dev, occ_sre |
| 9 | IRC | 安全 渗透测试 | occ_security_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_functional_qa, occ_test_dev, occ_onet_17-2111.00 | occ_onet_15-1299.04, **occ_security_engineer**, occ_onet_15-1299.05 | occ_onet_15-1299.04, **occ_security_engineer**, occ_functional_qa |
| 10 | IAR | 我喜欢把网页做得好看又流畅，常调交互动画和组件 | occ_frontend_dev | occ_onet_19-3091.00, occ_onet_15-1255.00, occ_interaction_designer | occ_interaction_designer, occ_onet_15-1255.00, **occ_frontend_dev** | **occ_frontend_dev**, occ_onet_15-1255.00, occ_interaction_designer | **occ_frontend_dev**, occ_interaction_designer, occ_onet_15-1255.00 |
| 11 | IRC | 想做高并发的服务端接口和数据库设计 | occ_backend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | **occ_backend_dev**, occ_data_engineer, occ_onet_15-1243.00 | **occ_backend_dev**, occ_onet_15-1243.00, occ_onet_15-1242.00 | **occ_backend_dev**, occ_onet_15-1243.00, occ_onet_15-1242.00 |
| 12 | ICE | 平时爱用 Excel 和 SQL 做报表，从数据里找原因 | occ_data_analyst | occ_onet_11-9121.01, occ_onet_33-3021.06, occ_onet_19-3011.00 | **occ_data_analyst**, occ_onet_15-2051.00, occ_bi_analyst | **occ_data_analyst**, occ_bi_analyst, occ_onet_15-2051.01 | **occ_data_analyst**, occ_bi_analyst, occ_onet_15-2051.01 |
| 13 | IRC | 对大模型应用很感兴趣，做过 RAG 和智能体小项目 | occ_llm_app_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | **occ_llm_app_engineer**, occ_nlp_engineer, occ_ml_engineer | **occ_llm_app_engineer**, occ_nlp_engineer, occ_ml_engineer | **occ_llm_app_engineer**, occ_nlp_engineer, occ_ml_engineer |
| 14 | IRC | 喜欢研究图像识别和目标检测 | occ_cv_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-1042.00, **occ_cv_engineer**, occ_onet_11-9121.01 | **occ_cv_engineer**, occ_ml_engineer, occ_nlp_engineer | **occ_cv_engineer**, occ_onet_19-1022.00, occ_onet_17-2072.01 |
| 15 | ESA | 喜欢跟用户聊需求，把想法写成方案推动大家落地 | occ_product_manager | occ_onet_11-3131.00, occ_onet_11-9151.00, occ_onet_11-1031.00 | **occ_product_manager**, occ_onet_11-2021.00, occ_onet_19-3033.00 | **occ_product_manager**, occ_onet_21-1094.00, occ_onet_13-1082.00 | **occ_product_manager**, occ_onet_21-1094.00, occ_interaction_designer |
| 16 | IRC | 参加过 CTF，喜欢找系统漏洞 | occ_security_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_perf_engineer, occ_ui_designer, occ_onet_19-2041.03 | **occ_security_engineer**, occ_onet_15-1299.05, occ_onet_15-1299.04 | occ_onet_15-1299.04, **occ_security_engineer**, occ_client_dev |
| 17 | IRC | 想写安卓和 iOS 应用 | occ_mobile_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_llm_app_engineer, occ_onet_19-2011.00, occ_onet_15-1299.07 | **occ_mobile_dev**, occ_client_dev, occ_ui_designer | **occ_mobile_dev**, occ_llm_app_engineer, occ_client_dev |
| 18 | RIC | 希望保障线上服务稳定，处理故障和监控告警 | occ_sre | occ_onet_17-2051.01, occ_onet_17-2199.07, occ_onet_17-2021.00 | **occ_sre**, occ_onet_15-1299.01, occ_onet_15-1212.00 | **occ_sre**, occ_onet_21-1023.00, occ_onet_21-1093.00 | **occ_sre**, occ_onet_21-1093.00, occ_onet_15-1299.01 |
| 19 | AIR | 喜欢玩游戏，也想用 Unity 做游戏 | occ_game_client_dev | occ_onet_27-3023.00, occ_onet_15-1255.01, occ_onet_27-3043.05 | occ_onet_15-1255.01, **occ_game_client_dev**, occ_onet_27-1014.00 | **occ_game_client_dev**, occ_onet_15-1255.01, occ_ui_designer | occ_onet_15-1255.01, **occ_game_client_dev**, occ_onet_27-1014.00 |

| 指标 | rules | lexical | vector-only | hybrid |
|---|---|---|---|---|
| Hit@3 (1-9 关键词) | 0/9 | 8/9 | 9/9 | 9/9 |
| Hit@3 (10-19 自然语句) | 0/10 | 8/10 | 10/10 | 10/10 |
| Hit@3 合计 | 0 | 16 | 19 | 19 |
| Top1 合计 | 0 | 9 | 18 | 15 |

### bge-m3（本地）

模型：bge-m3@local

| # | Holland | 查询 | 目标 | rules | lexical | vector-only | hybrid(规则⊕全文⊕向量) |
|---|---|---|---|---|---|---|---|
| 1 | IRC | Java Spring Boot 后端 | occ_backend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_fullstack_dev, **occ_backend_dev**, occ_data_engineer | **occ_backend_dev**, occ_client_dev, occ_devops_cloud | **occ_backend_dev**, occ_fullstack_dev, occ_test_dev |
| 2 | IRC | 前端 TypeScript Vue | occ_frontend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_fullstack_dev, **occ_frontend_dev**, occ_onet_19-2041.03 | **occ_frontend_dev**, occ_game_client_dev, occ_ui_designer | **occ_frontend_dev**, occ_fullstack_dev, occ_game_client_dev |
| 3 | ICR | SQL 数据分析 | occ_data_analyst | occ_onet_19-3099.01, occ_fullstack_dev, occ_onet_19-1029.02 | **occ_data_analyst**, occ_onet_15-2051.00, occ_bi_analyst | **occ_data_analyst**, occ_data_engineer, occ_onet_15-1211.00 | **occ_data_analyst**, occ_onet_15-1211.00, occ_data_engineer |
| 4 | IRC | 机器学习 Python | occ_ml_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | **occ_ml_engineer**, occ_data_scientist, occ_onet_15-2051.00 | **occ_ml_engineer**, occ_data_scientist, occ_cv_engineer | **occ_ml_engineer**, occ_data_scientist, occ_onet_15-2051.00 |
| 5 | ASE | 产品 需求 用户 | occ_product_manager | occ_onet_27-2042.00, occ_onet_27-3011.00, occ_onet_27-2041.00 | **occ_product_manager**, occ_interaction_designer, occ_onet_27-1021.00 | **occ_product_manager**, occ_onet_27-1021.00, occ_onet_41-4011.00 | **occ_product_manager**, occ_onet_27-1021.00, occ_interaction_designer |
| 6 | AIR | 设计 Figma | occ_ui_designer | occ_onet_27-3023.00, occ_onet_15-1255.01, occ_onet_27-3043.05 | **occ_ui_designer**, occ_onet_27-1021.00, occ_onet_15-1255.01 | occ_interaction_designer, **occ_ui_designer**, occ_onet_27-1027.00 | **occ_ui_designer**, occ_interaction_designer, occ_onet_27-1021.00 |
| 7 | IRC | 测试 自动化 | occ_test_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_functional_qa, **occ_test_dev**, occ_perf_engineer | **occ_test_dev**, occ_onet_15-1253.00, occ_functional_qa | **occ_test_dev**, occ_functional_qa, occ_perf_engineer |
| 8 | IRC | 运维 Docker Kubernetes | occ_devops_cloud | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_fullstack_dev, **occ_devops_cloud**, occ_sre | **occ_devops_cloud**, occ_sre, occ_onet_15-2031.00 | occ_sre, **occ_devops_cloud**, occ_onet_15-2031.00 |
| 9 | IRC | 安全 渗透测试 | occ_security_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_functional_qa, occ_test_dev, occ_onet_17-2111.00 | occ_onet_15-1299.04, **occ_security_engineer**, occ_onet_15-1299.05 | occ_onet_15-1299.04, **occ_security_engineer**, occ_functional_qa |
| 10 | IAR | 我喜欢把网页做得好看又流畅，常调交互动画和组件 | occ_frontend_dev | occ_onet_19-3091.00, occ_onet_15-1255.00, occ_interaction_designer | occ_interaction_designer, occ_onet_15-1255.00, **occ_frontend_dev** | **occ_frontend_dev**, occ_onet_15-1255.00, occ_game_client_dev | **occ_frontend_dev**, occ_onet_15-1255.00, occ_interaction_designer |
| 11 | IRC | 想做高并发的服务端接口和数据库设计 | occ_backend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | **occ_backend_dev**, occ_data_engineer, occ_onet_15-1243.00 | **occ_backend_dev**, occ_onet_15-1243.00, occ_onet_15-1255.00 | **occ_backend_dev**, occ_onet_15-1243.00, occ_onet_15-1255.00 |
| 12 | ICE | 平时爱用 Excel 和 SQL 做报表，从数据里找原因 | occ_data_analyst | occ_onet_11-9121.01, occ_onet_33-3021.06, occ_onet_19-3011.00 | **occ_data_analyst**, occ_onet_15-2051.00, occ_bi_analyst | **occ_data_analyst**, occ_onet_15-2051.02, occ_onet_15-2051.01 | **occ_data_analyst**, occ_data_scientist, occ_onet_15-2051.02 |
| 13 | IRC | 对大模型应用很感兴趣，做过 RAG 和智能体小项目 | occ_llm_app_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | **occ_llm_app_engineer**, occ_nlp_engineer, occ_ml_engineer | **occ_llm_app_engineer**, occ_nlp_engineer, occ_onet_17-2072.01 | **occ_llm_app_engineer**, occ_nlp_engineer, occ_ml_engineer |
| 14 | IRC | 喜欢研究图像识别和目标检测 | occ_cv_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-1042.00, **occ_cv_engineer**, occ_onet_11-9121.01 | **occ_cv_engineer**, occ_onet_17-2072.01, occ_onet_17-1021.00 | **occ_cv_engineer**, occ_onet_15-1221.00, occ_onet_15-1299.06 |
| 15 | ESA | 喜欢跟用户聊需求，把想法写成方案推动大家落地 | occ_product_manager | occ_onet_11-3131.00, occ_onet_11-9151.00, occ_onet_11-1031.00 | **occ_product_manager**, occ_onet_11-2021.00, occ_onet_19-3033.00 | occ_interaction_designer, **occ_product_manager**, occ_onet_15-1255.00 | **occ_product_manager**, occ_interaction_designer, occ_onet_15-1255.00 |
| 16 | IRC | 参加过 CTF，喜欢找系统漏洞 | occ_security_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_perf_engineer, occ_ui_designer, occ_onet_19-2041.03 | occ_onet_15-1299.04, occ_onet_15-1299.06, occ_onet_15-1253.00 | occ_onet_15-1299.04, **occ_security_engineer**, occ_perf_engineer |
| 17 | IRC | 想写安卓和 iOS 应用 | occ_mobile_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_llm_app_engineer, occ_onet_19-2011.00, occ_onet_15-1299.07 | **occ_mobile_dev**, occ_onet_27-3043.05, occ_client_dev | **occ_mobile_dev**, occ_llm_app_engineer, occ_client_dev |
| 18 | RIC | 希望保障线上服务稳定，处理故障和监控告警 | occ_sre | occ_onet_17-2051.01, occ_onet_17-2199.07, occ_onet_17-2021.00 | **occ_sre**, occ_onet_15-1299.01, occ_onet_15-1212.00 | occ_onet_15-1231.00, **occ_sre**, occ_onet_15-1299.06 | **occ_sre**, occ_onet_15-1212.00, occ_onet_15-1299.01 |
| 19 | AIR | 喜欢玩游戏，也想用 Unity 做游戏 | occ_game_client_dev | occ_onet_27-3023.00, occ_onet_15-1255.01, occ_onet_27-3043.05 | occ_onet_15-1255.01, **occ_game_client_dev**, occ_onet_27-1014.00 | **occ_game_client_dev**, occ_onet_15-1255.01, occ_onet_15-1255.00 | **occ_game_client_dev**, occ_onet_15-1255.01, occ_onet_27-1014.00 |

| 指标 | rules | lexical | vector-only | hybrid |
|---|---|---|---|---|
| Hit@3 (1-9 关键词) | 0/9 | 8/9 | 9/9 | 9/9 |
| Hit@3 (10-19 自然语句) | 0/10 | 8/10 | 9/10 | 10/10 |
| Hit@3 合计 | 0 | 16 | 18 | 19 |
| Top1 合计 | 0 | 9 | 14 | 16 |

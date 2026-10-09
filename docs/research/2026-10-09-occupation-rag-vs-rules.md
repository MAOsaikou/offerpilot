# 职业库 RAG 与 Holland 规则对照（2026-10-09，执行于 2026-10-10）

范围仅为内置 402 条 occupations（24 条中文维护职业 + 378 条 O*NET），不含面经、行业报告或 kb_chunks。每个目标来自真实职业条目，不添加同义改写材料。以下是 PostgreSQL 17 + pgvector IT 的实际输出，top-N=3。

方法：rules 使用现有 Holland 位置加权排序；hybrid 使用 canonical document 的 1024 维测试哈希向量余弦检索 + simple 全文检索（候选 K=20），RRF 常数 60，再与 Holland 前 6 个候选的顺序 RRF。零 Holland 分数不进入推荐。测试哈希向量采用文本字符二元组特征哈希，只能表达字面相似，不能视为 Bailian 的语义效果。目标职业是人工预设的查询相关项，纯兴趣案例不参与目标命中统计。

复现（需 Docker，不需要 API Key）：

```bash
cd backend
bash mvnw -q -pl offerpilot-bootstrap -am test \
  -Dtest=OccupationRagIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
cat offerpilot-bootstrap/target/occupation-rag-eval.md
```

测试 profile 为 test+mock-llm；模型仅在 src/test 中注册，不进入生产包。测试会检查职业目标存在、两种结果尺寸、重复调用顺序一致、纯兴趣与规则完全相同，同时另测实际 V9 向量/全文填充、重建和降级。结果变更后需重新生成本记录；不会把付费真实模型作为 CI 前提。

| Holland | 查询 | 目标 | rules top3 | hybrid top3 |
|---|---|---|---|---|
| IRC | Java Spring Boot 后端 | occ_backend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-2041.03, occ_backend_dev, occ_onet_17-2112.01 |
| IRC | 前端 TypeScript Vue | occ_frontend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-2041.03, occ_onet_29-1243.00, occ_onet_17-2112.01 |
| ICR | SQL 数据分析 | occ_data_analyst | occ_onet_19-3099.01, occ_fullstack_dev, occ_onet_19-1029.02 | occ_onet_19-3099.01, occ_data_analyst, occ_fullstack_dev |
| IRC | 机器学习 Python | occ_ml_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-2041.03, occ_ml_engineer, occ_onet_17-2112.01 |
| ASE | 产品 需求 用户 | occ_product_manager | occ_onet_27-2042.00, occ_onet_27-3011.00, occ_onet_27-2041.00 | occ_product_manager, occ_onet_27-2042.00, occ_onet_27-3011.00 |
| AIR | 设计 Figma | occ_ui_designer | occ_onet_27-3023.00, occ_onet_15-1255.01, occ_onet_27-3043.05 | occ_ui_designer, occ_onet_15-1255.01, occ_onet_27-1021.00 |
| IRC | 测试 自动化 | occ_test_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-2041.03, occ_test_dev, occ_onet_17-2112.01 |
| IRC | 运维 Docker Kubernetes | occ_devops_cloud | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_devops_cloud, occ_onet_19-2041.03, occ_onet_17-2112.01 |
| IRC | 安全 渗透测试 | occ_security_engineer | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-2041.03, occ_security_engineer, occ_onet_17-2112.01 |
| IRC | （纯兴趣） | occ_backend_dev | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 | occ_onet_19-2041.03, occ_onet_17-2112.01, occ_onet_17-2111.00 |

9 个关键词案例中，预设目标的 Hit@3：规则 0/9，测试 hybrid 8/9；目标 Top1 为 3/9（产品、UI、DevOps）。这些数值描述本组人工意图与固定哈希模型，不代表泛化质量或人工能力评估。

- 后端、数据分析、机器学习、测试、安全等关键词将相关职业带入 top3；产品、Figma 和 Docker/Kubernetes 将目标提升到首位。引用直接展示该职业原文，规则兴趣理由和相似度不因 RRF 改写。
- 前端案例未进入 top3：前端职业 Holland 码与本例 IRC 的规则候选重叠不足；两级 RRF 会优先同时受到两路支持的候选，限制了单路强命中的提升。该失败保留在对照里，不能用同义改写隐藏。
- 纯兴趣没有查询时排序完全一致；无模型、无 Key、无向量或调用失败时 rankHybrid 也严格回退 rules。retrieve 仍可独立调用 FTS。代码保留 Holland 零分过滤，不以检索结果声称用户具备能力。
- PostgreSQL simple 不分中文词；多词 plainto_tsquery 是 AND，中文连续短语要求相同词元。向量补充候选，但哈希向量中的特征碰撞也可能引入无关项。生产需用真实 text-embedding-v4 重跑同一意图并人工核查，不能把此 mock 指标宣称为真实语义质量。

另已验证 1280×900、390×844 的实际生产前端：新引用行可读、旧载荷不出现空引用、目标选择按钮保留、无横向溢出与脚本错误；前端 188 测试、lint、typecheck、build 通过。

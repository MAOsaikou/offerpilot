# KB-01 内置资料

运行 `python3 backend/tools/builtin-kb/build_seed.py` 可离线重建 `seed/builtin-knowledge.json`；无模型、爬虫、网络或密钥。运行时只导入已物化 JSON，不临时生成题目。

- 24 个岗位 × 6 个专属能力情境 × 4 个通用题型 = **576 个不同题干**；不是 576 条独立候选人经历。题型包括行为、情境、专业、案例；各题型144题，三档难度各192。各岗位24题，覆盖技术与非技术岗位。
- 144 个岗位主题/情境与4种题型由本次开发整理（含 AI 辅助），标记 `source=curated`、未逐题专家校审，不声称人工独立审题或真实面经。练习思路不是标准答案，不替代专业规范。
- 同步24个 curated 能力框架，非企业任职标准、职业许可或能力认证。
- 6段通用校招流程，无公司日期、固定轮次或录用承诺。
- 10个行业报告结构/入口，`status=PLANNED`，没有可信报告时 metrics=[]、publisher/publishedAt/reportUrl/methodology=null。PLANNED 不进入 RAG。
- 本项目新增编辑材料按 CC BY 4.0 标注；外部 O*NET 职业来源、归属、中文翻译局限见 `../onet-import/README.md`。报告入口的许可字段表示尚无已核实报告许可。

职业数据不由此脚本生成。378个直接 O*NET ID +24个中文映射 ID；薪资全部 null。24个映射的描述、技术技能和路径为编辑建议，不能当作 O*NET 技术技能原文或中国市场统计。更新职业种子时须保留 provenance 字段；不支持直接在数据库覆盖内置内容。

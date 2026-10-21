# RS-02 英文公开基准（虚构/已脱敏），非中文真实授权简历

用两个许可清楚的英文公开数据集测 OfferPilot 简历解析的逐字段召回/精确率。它们**不是**中文真实授权简历，结果不能证明 RS-02「真实简历 ≥20 份召回 ≥90%」的验收，RS-02 仍为「进行中」。

| 数据集 | 版本 | 许可 | 使用范围 | 说明 |
| --- | --- | --- | --- | --- |
| [Careerflow/ResumeExtractBench](https://huggingface.co/datasets/Careerflow/ResumeExtractBench) | `42aae58f` | CC-BY-4.0 | 全部 38 份 PDF + 人工核验 JSON | 28 份手写扫描 + 10 份对抗样本；个人信息虚构 |
| [dotin-inc/resume-dataset-NER-annotations](https://github.com/dotin-inc/resume-dataset-NER-annotations) | `052429f9` | CC0-1.0 | 只用 set-aside 测试集 50 份 | 纯文本 + 行内 NER 标注；作者已替换姓名/邮箱 |

署名：ResumeExtractBench © Careerflow，CC-BY-4.0；dotin 数据集见 Pinzon, Krainikovsky, Samarev, *Limitations of Neural Networks-based NER for Resume Data Extraction*, SEPLN 65 (2020)。

**仓库不提交任何数据集文件、模型输出或报告**，`data/` 已 gitignore。复现：

```bash
python3 backend/eval/resume-en-benchmark/prepare.py /tmp/rs02-en       # 按固定版本下载，写 manifest.json / truth.json
cd backend && OFFERPILOT_RUN_LIVE_RESUME_EN=true RESUME_EN_DIR=/tmp/rs02-en \
  bash mvnw -B -Plive -pl offerpilot-bootstrap -am test -Dtest=ResumeEnglishBenchmarkLiveDumpTest -Dsurefire.failIfNoSpecifiedTests=false
python3 backend/eval/resume-en-benchmark/score.py /tmp/rs02-en          # 逐字段 recall/precision + n
python3 -m unittest discover -s backend/eval/resume-en-benchmark -p 'test_*.py'
```

`ResumeEnglishBenchmarkLiveDumpTest` 需要 `DEEPSEEK_API_KEY`，走上传同款 `ResumeDocumentReader` + `ResumeExtractor`（默认图文通道）。每份结果写入 `outputs.json`，失败只记录不重试，可续跑；换代码复测请设 `RESUME_EN_OUT=outputs-<tag>.json` 并把 score.py 指向它（`score.py <dir> [outputs文件名]`）。

计分口径（`score.py`）：
- 失败/未跑的样本按空输出计：召回分母照算，精确率不计。
- 匹配：规范化后相等、包含且短串覆盖长串 ≥60%，或词 F1 ≥0.6；电话比后 10 位数字；摘要要求标注词 ≥60% 出现在输出中；日期按年月比较，标注只有年份时只比年份。
- 字段映射：REB `basics.fname+lname/email/phone/city`、`personalSummary→summary`、`experience.company/position/start/end`、`education.institution/studyType/area/start/end`、`projects.name`、`certifications.name→certificates`、`skills[].skills→skills.items`。经历/教育子字段先按机构名配对再比。
- REB 的标注是完整的，所以列表字段同时报精确率；dotin 的 NER 只标了「出现过的提及」，不是完整列表，列表字段只报召回，单值的姓名/邮箱报精确率。dotin 的 Designation 包含摘要里的泛称，Tech Tools 包含正文里提到的工具，召回会被低估。

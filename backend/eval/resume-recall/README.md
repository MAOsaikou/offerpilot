# RS-02 简历字段召回评测

24 份虚构校招简历（人名、学校、公司、数字全部虚构），覆盖 6 种常见版式：单栏 DOCX、双栏表格 PDF、竖线分隔页眉 PDF、英文小标题 DOCX、纯文本、「技能证书合并 + 单独项目角色行 + 荣誉奖项」PDF。日期写法有 `2024.06`、`2024年6月`、`2024/06`、`Jun 2024` 和「至今 / Present」几种。

```bash
python3 generate.py /tmp/rr-out          # 需要 python-docx 和 LibreOffice（soffice）；生成时校验每条事实都在 PDF 文本层里
cd backend
OFFERPILOT_RUN_LIVE_RESUME_RECALL=true RESUME_RECALL_DIR=/tmp/rr-out \
  ./mvnw -q -Plive -pl offerpilot-bootstrap -am test -Dtest=ResumeFieldRecallLiveEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

- 需要 `DEEPSEEK_API_KEY`。默认只发文本层（`RESUME_RECALL_TEXT_ONLY=false` 时走与上传一致的文本 + 页面图片通道）；`RESUME_RECALL_ONLY=r02,r07` 只跑指定简历。
- 报告写到 `offerpilot-bootstrap/target/resume-recall-report.json`，包含逐字段召回、每一条漏项和模型原始输出。
- 比较前会统一空白、标点和日期写法（归一成年月或 present）。证书另算精确率（`certificates.precision`），用来发现把奖项错放进证书的情况。

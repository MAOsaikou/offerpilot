# O*NET 职业条目导入

把美国劳工部 O*NET 数据库的职业条目转成本项目 `occupations` 表的种子数据。

产出：`backend/offerpilot-bootstrap/src/main/resources/seed/occupations-onet.json`，
由 `OccupationSeeder` 在后端启动时导入。

## 为什么用 O*NET

- **权威**：兴趣分数（RIASEC 六维）来自 O*NET 官方测评，不是我们自己估的。
- **可溯源**：每条条目保留 `onetCode`（O*NET-SOC 编码），能回到源数据核对。
- **许可**：O*NET 数据库按 **CC BY 4.0** 使用，需保留归属与改编说明（[许可](https://www.onetcenter.org/license_db.html)）。本项目使用 O*NET 31.0，归属 National Center for O*NET Development；中文为翻译/改编，不代表官方认可。

注意它的局限：O*NET 是美国的职业分类，**没有中国特有职业**（公务员、事业编等），
这部分要另找数据源（计划里是《中华人民共和国职业分类大典》）。

## 前置

1. 从 <https://www.onetcenter.org/database.html> 下载 **CSV 版本**并解压，至少需要：
   - `occupation_data.csv`（职业名称与描述）
   - `career_interest_types.csv`（RIASEC 兴趣分数）
   - `job_zones.csv`（岗位的教育/培训等级）
   - `essential_skills.csv`（通用技能及其重要性）
2. Node 22+，以及一个可用的 DeepSeek API Key。

## 三步流程

### 1. 生成候选清单

```powershell
powershell -File build-candidates.ps1 -OnetDir <CSV 目录> -OutFile candidates.json
```

默认只保留 **Job Zone >= 4** 的职业（Zone 4 通常要求本科学历，Zone 5 要求研究生），
对应我们的目标用户「高校学生」。当前这一批筛出 378 条。

得分处理：O*NET 用 1-7 分制，脚本线性归一化到 0-1；霍兰德三码按六维分数降序取前三。

### 2. 翻译成中文

```powershell
$env:DEEPSEEK_API_KEY = '<your-key>'
node translate.mjs candidates.json translated.json
```

- 结果按 `code` 匹配，不依赖模型返回顺序
- 模型批量翻译偶发丢条目，脚本会自动用更小批次补翻（最多 3 轮）
- 每批完成即落盘，中断后重跑会跳过已翻译的条目
- 378 条实测约 60 秒、3.5 万 tokens

### 3. 生成种子文件

把 `translated.json` 转成 `occupations-onet.json`：字段名对齐 `docs/06` 的表结构，
补上 `_meta`（数据来源、筛选条件、归一化方式），**每条职业一行**（便于 diff）。

### 4. 补齐 Job Zone 职业准备路径（无需模型或 API Key）

使用同版本 **O*NET 31.0** 的两张官方表，避免混用后续版本：

```bash
mkdir -p /tmp/onet-csv
curl -fsSL https://www.onetcenter.org/dl_files/database/db_31_0_csv/job_zones.csv -o /tmp/onet-csv/job_zones.csv
curl -fsSL https://www.onetcenter.org/dl_files/database/db_31_0_csv/job_zone_reference.csv -o /tmp/onet-csv/job_zone_reference.csv
node backend/tools/onet-import/fill-growth-path.mjs /tmp/onet-csv
node backend/tools/onet-import/fill-growth-path.mjs /tmp/onet-csv --check
node --test backend/tools/onet-import/fill-growth-path.test.mjs
```

`job_zones.csv` 按 O*NET-SOC 编码关联到种子；`job_zone_reference.csv` 的 Education / Experience / Job Training 三栏按 Zone 4/5 人工翻译压缩为两段固定中文摘要（≤120字）。同分区使用相同准备要求，**不是职业晋升路线、个人必备资格或中国准入标准**；保留原文“多数/通常/可能/部分例外”等限定。当前378条全部匹配：Zone 4 为226条，Zone 5 为152条。来源标记 `growthPathSource=onet-job-zone`；归属 National Center for O*NET Development / USDOL/ETA，使用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)，中文为本项目翻译/改编，非官方认可。

脚本校验两份原始下载文件的 SHA-256（固定在源码；换版本须复核映射及译文），仅填空路径并保留其他字段。编码缺失/不支持的分区直接报错且不写文件；已有生成路径与分区不符也报错。默认只处理 `occupations-onet.json`，不读取或改写24条中文映射；可用第二个位置参数指定同样逐条一行的种子文件。非空的 curated 路径不会被覆盖。`--check` 校验无需重写，重复运行字节不变。薪资不参与导入，原有 `salaryRange=null` 保留。

## 重新导入

改完种子文件后需要重新构建，因为种子文件是打进 jar 的：

```powershell
cd backend
mvn -q -B -pl offerpilot-bootstrap -am package -DskipTests
```

注意：构建前要先停掉正在运行的后端，否则 Windows 会锁住 jar 导致 repackage 失败。

## 已知问题

- **翻译未逐条人工校对**，且模型偶尔会补充原文没有的内容（例如给"历史学教师"加上
  "中国史、世界史、专门史"）。重新生成时可在 Prompt 里强调"只转述原文"。
- `core_skills` 用的是 O*NET 的**通用技能**（阅读理解、批判性思维等），不是技术技能。
  技术技能建议改为从真实 JD 抽取（jobs-mcp 已经能返回 JD 全文）。
- 更新 O*NET 版本时，`hollandScores` 会随之变化，需要重新跑一遍并复核差异。

## KB-01 数据边界

当前直接导入 378 条，另保留24个中文映射 ID（不计作378之外的独立官方职业）。`sourceUrl/onetCode/license/attribution/contentNotice` 随条目保存。全部薪资无可信统计源，置 null，页面显示“暂无数据”；删除原24条估算及未经支持的趋势/企业断言。直接导入的6条缺技能保持空值；378条路径由官方 Job Zone 职业准备要求补齐，标记 onet-job-zone；中文映射的技术技能/路径仍标记 curated 编辑建议。后续导入必须保留同样的字段与许可，不补造薪资。

# RS-02 简历字段召回评测

固定 seed=1016 的 **24 份独立虚构简历、6 种版式**，绝非真实用户简历。`--image-variants` 额外生成4组 PNG、压缩倾斜 JPG、图片 PDF，共36样本；派生文件以 sourceId 关联，不能计成36份独立简历。保留生成用 DOCX/页面图片，评测按 truth.json / manifest.json 选择，避免混入中间产物。

离线生成及校验（需本机已安装 python-docx、Pillow、pdftoppm/pdftotext；不自动安装依赖）：

```bash
# 标准渲染器还需 LibreOffice；使用独立 /tmp profile，避免锁住桌面实例。
python3 backend/eval/resume-recall/generate.py /tmp/rr-out --image-variants
# 沙箱内 LibreOffice 不可启动时显式使用 ReportLab，不把两种渲染器结果混同。
python3 backend/eval/resume-recall/generate.py /tmp/rr-offline --pdf-engine reportlab --image-variants
python3 -m unittest discover -s backend/eval/resume-recall -p 'test_*.py'
```

离线回归生成两次并校验样本数、独立人数、六种版式、五种文件类型、SHA-256 和逐文件一致性。DOCX ZIP、ReportLab PDF、图片 PDF 去除变动时间戳；LibreOffice 的二进制复现不作保证。转换前后都保留文件，PDF 的关键标注事实必须存在于文本层。图片是合成扫描压力样本，不是手机拍照或真实扫描件。

**以下仅提供 live 命令，本轮没有执行、没有新增真实召回数字：**

```bash
cd backend
OFFERPILOT_RUN_LIVE_RESUME_RECALL=true RESUME_RECALL_DIR=/tmp/rr-offline \
  bash mvnw -o -B -Plive -pl offerpilot-bootstrap -am test \
  -Dtest=ResumeFieldRecallLiveEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false
```

需要环境变量 DEEPSEEK_API_KEY，可配置 DEEPSEEK_BASE_URL / RESUME_RECALL_MODEL。默认与上传一致的文本+视觉通道；`RESUME_RECALL_TEXT_ONLY=true` 或 `RESUME_RECALL_ONLY=r02,r07` 仅作诊断对照，passesLiveGate=null，不作为完整验收。未关闭安全审核、未缓存答案。

门禁在模型调用前检查≥20份独立标注、唯一 sample ID 和 manifest 文件指纹；任何读取/模型错误按空输出计零。比较统一空白、标点和年月，不让较短的模型片段反向命中完整标注；空标注不计召回分母。证书另报 precision，不混入召回分母。完整图文运行的字段召回≥0.90且无失败才通过字段门禁；子集/纯文本仍检查无失败但不作为完整验收。与旧版计分法有差异，不沿用夜1016的1.000作为本次结果。

报告写入 `offerpilot-bootstrap/target/resume-recall-report.json`，含模型、语料 SHA-256、provenance、样本/独立人数、每字段命中/分母、逐条漏项与原始结构化输出。仅有标记为 `consented_real` 的完整图文真实集才填写 passesLiveGate；虚构集只可报告 passesFieldRecallGate，不能证明真实分布达标。

真实集待提供/实测：在仓库外建立与生成 truth.json 相同结构的至少20份独立、已授权且人工逐项核实的简历标注（basics、education、experience、projects、skills、certificates、summary；无事实填空），每条包含唯一 id/file/layout；格式变体带 sourceId。manifest.json 记录 provenance=consented_real 及 samples[{id,sourceId,file,sha256}]，必须与真实文件一致。此标记是负责人对来源的声明，脚本不能代替授权审查。将 RESUME_RECALL_DIR 指向该私有目录，分别评估原始件、真实拍照/扫描件；不要把原文、授权记录或模型输出提交到仓库。没有这些材料时保持“待实测”，不得用虚构材料替代。

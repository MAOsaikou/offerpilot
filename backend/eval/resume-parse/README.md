# RS-01 上传解析时延与进度

标准库脚本从开始上传计到 READY/FAILED，包含后端排队；支持 PDF / DOCX / PNG / JPG/JPEG。观察并保存状态和 progress 变化，不虚构未被轮询捕捉的阶段。每组同时报告 n、READY、失败、成功样本 P50/P95/最大值及门禁；错误/超时不从分母消失，任一失败或超时退出非零。文本 PDF预算10秒，图片/图片 PDF预算30秒；DOCX无既定时延阈值，只检查完成。P95使用 nearest-rank。报告不保存令牌、原文或供应商错误正文。

离线单测：

```bash
python3 -m unittest discover -s backend/eval/resume-parse -p 'test_*.py'
```

**live 仅给命令，本轮不执行：**

```bash
python3 backend/eval/resume-recall/generate.py /tmp/rr-out --pdf-engine reportlab --image-variants
# manifest 优先选择正式样本，避免把中间 DOCX/page PNG 重复纳入。
python3 backend/eval/resume-parse/measure_parse.py /tmp/rr-out --base http://127.0.0.1:18082 \
  --workers 1 --output /tmp/rs01-serial.json
python3 backend/eval/resume-parse/measure_parse.py /tmp/rr-out --base http://127.0.0.1:18082 \
  --workers 4 --output /tmp/rs01-queued.json
```

每4份新建一个独立访客，保留后端配额/并发/安全限制；可用 --per-guest 调整身份分组。文件后缀 `-scan.pdf` 标记图片 PDF，否则 PDF按文本预算；真实扫描集应按此命名或单独整理并说明。脚本不会根据内容猜测扫描类型。--limit控制上传+轮询总预算，HTTP也受剩余预算限制。

生成样本是虚构文本和合成渲染压力件，不能替代真实拍照/扫描验收。本轮格式读取、worker顺序及UI阶段回归不等于真实模型时延达标。应在仓库外另准备授权真实件并执行相同命令，保留文件指纹/每条终态/观察到的进度；新机器、真实JPG、拍照/扫描和并发排队指标均待实测。

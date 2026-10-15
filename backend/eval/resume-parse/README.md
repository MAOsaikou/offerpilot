# 简历解析耗时（RS-01）

`measure_parse.py` 验收 docs/02 RS-01「文本版 PDF 10s 内完成；图片版 30s 内完成」。对目录里每个 PDF / DOCX / PNG / JPG 用新访客上传，轮询到 READY 或 FAILED，输出每份耗时和按类型的 P50/P95/最大值。只依赖 Python 标准库，不读取密钥。

```bash
python3 ../resume-recall/generate.py /tmp/rr-out           # 生成虚构简历样本
mkdir -p /tmp/rs1-in && cp /tmp/rr-out/*.pdf /tmp/rr-out/*.docx /tmp/rs1-in/
pdftoppm -png -r 110 -singlefile /tmp/rr-out/r02.pdf /tmp/rs1-in/r02-img   # 图片版样本
python3 measure_parse.py /tmp/rs1-in --base http://127.0.0.1:18082
```

结果记录在 docs/10。

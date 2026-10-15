# 聊天首 token 延迟（AG-02）

`measure.py` 对本地后端的 `POST /api/v1/agent/chat` 计时，用来验收 docs/02 AG-02「首 token < 2s（P95）」。每次请求都用新的访客身份，模型档位为 FLASH，只依赖 Python 标准库，不读取密钥。

```bash
python3 measure.py --base http://127.0.0.1:18082 --runs 20 --mode AUTO
```

输出每次的 `started`（到 run.started）、`first`（到第一个 text.delta）、`total`（到 run.completed）秒数，最后给出 P50/P95/最大值。结果记录在 docs/10「真实模型延迟」。

# 删除全部数据计时（SC-05）

`measure_delete.py` 验收 docs/02 SC-05「30s 内完成并注销登录」。每次用体验账号登录（得到预置演示数据的沙箱账号），再导入 `--notes` 条知识笔记，然后计时 `DELETE /api/v1/users/me/data`，最后用同一令牌请求接口，确认返回 401。不调用模型，只依赖 Python 标准库，不读取密钥。需要后端开启体验账号沙箱（默认开启）。

```bash
python3 measure_delete.py --base http://127.0.0.1:18082 --runs 5 --notes 500
```

结果记录在 docs/10。

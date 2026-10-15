"""SC-05「删除全部数据 30s 内完成并注销登录」计时。

每次：体验账号登录（每次得到一个预置了演示简历、岗位看板、面试、成长计划等数据的沙箱账号），
再导入 --notes 条知识笔记加大数据量，然后计时 DELETE /api/v1/users/me/data，
最后用同一令牌访问 /api/v1/conversations，确认已注销（应为 401）。
不调用模型，只依赖 Python 标准库，不读取密钥。
"""
import argparse
import json
import statistics
import time
import urllib.error
import urllib.request


def call(base, method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None


def one(base, notes):
    status, login = call(base, "POST", "/api/v1/auth/demo-login")
    assert status == 200, status
    token = login["data"]["accessToken"]
    for start in range(0, notes, 50):
        batch = [{"sourceId": f"n{i}", "note": {"title": f"笔记 {i}", "content": "面试复盘内容。" * 200, "category": "面试复盘"}}
                 for i in range(start, min(notes, start + 50))]
        status, _ = call(base, "POST", "/api/v1/knowledge/notes/import", {"notes": batch}, token)
        assert status == 200, status
    t0 = time.monotonic()
    status, _ = call(base, "DELETE", "/api/v1/users/me/data", {"confirm": "DELETE"}, token)
    elapsed = time.monotonic() - t0
    after, _ = call(base, "GET", "/api/v1/conversations", token=token)
    return {"delete_status": status, "seconds": elapsed, "after_status": after}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18082")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--notes", type=int, default=200)
    args = parser.parse_args()
    rows = []
    for i in range(args.runs):
        row = one(args.base, args.notes)
        rows.append(row)
        print(json.dumps({"run": i + 1, **{k: (round(v, 3) if isinstance(v, float) else v) for k, v in row.items()}}))
    values = [r["seconds"] for r in rows]
    print(json.dumps({"notes": args.notes, "p50": round(statistics.median(values), 3), "max": round(max(values), 3),
                      "all_200": all(r["delete_status"] == 200 for r in rows),
                      "all_logged_out": all(r["after_status"] == 401 for r in rows)}))


if __name__ == "__main__":
    main()

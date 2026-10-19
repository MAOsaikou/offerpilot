#!/usr/bin/env python3
"""AG-02 首 token 延迟测量：对本地后端的 POST /agent/chat 计时（docs/10 真实模型延迟）。

每次请求使用新的访客身份（避免访客 RPM/额度互相影响），记录：
  started  请求发出到 run.started（会话与运行落库）
  first    请求发出到第一个 text.delta（用户看到的首 token）
  total    请求发出到 run.completed
不读取、不打印任何密钥；模型调用走后端配置的供应商。
用法：python3 measure.py --base http://127.0.0.1:18082 --runs 20 --mode AUTO
"""
import argparse, json, statistics, time, urllib.request

QUESTIONS = [
    "你好，简单介绍一下你能帮我做什么？",
    "Java 后端实习面试一般会问哪些基础问题？列三个。",
    "我是大三学生，应该先刷算法还是先做项目？一句话建议。",
    "简历里的项目经历怎么写更有说服力？给两条要点。",
    "Redis 缓存击穿是什么？一句话解释。",
]

# Whole-request advice questions that previously waited for planner + resume action + reply.
RESUME_QUESTIONS = [
    "简历里的项目经历怎么写更有说服力？给两条要点。",
    "简历里的项目经历怎么写？给两条建议。",
    "简历中的实习经历如何写？给两条要点。",
    "简历的工作经历应该怎么写更好？给两条建议。",
]


def post(url, body=None, token=None, accept="application/json"):
    headers = {"Content-Type": "application/json", "Accept": accept}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else b""
    return urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method="POST"), timeout=180)


def one(base, mode, question):
    token = json.load(post(base + "/api/v1/auth/guest"))["data"]["accessToken"]
    marks, event = {}, None
    t0 = time.monotonic()
    with post(base + "/api/v1/agent/chat", {"content": question, "mode": mode, "options": {"modelTier": "FLASH"}},
              token, "text/event-stream") as response:
        for raw in response:
            line = raw.decode("utf-8").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
                now = time.monotonic() - t0
                if event == "run.started":
                    marks.setdefault("started", now)
                elif event == "text.delta":
                    marks.setdefault("first", now)
                elif event in ("run.completed", "error"):
                    marks["total"] = now
                    marks["status"] = event
                    break
    return marks


def pct(values, p):
    values = sorted(values)
    if not values:
        return None
    k = max(0, min(len(values) - 1, round(p / 100 * len(values) + 0.5) - 1))
    return values[k]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18082")
    parser.add_argument("--runs", type=int, default=20)
    parser.add_argument("--mode", default="AUTO")
    parser.add_argument("--pause", type=float, default=1.0)
    parser.add_argument("--question-set", choices=("mixed", "resume"), default="mixed")
    args = parser.parse_args()
    questions = RESUME_QUESTIONS if args.question_set == "resume" else QUESTIONS
    rows = []
    for i in range(args.runs):
        marks = one(args.base, args.mode, questions[i % len(questions)])
        rows.append(marks)
        print(json.dumps({"run": i + 1, **{k: (round(v, 3) if isinstance(v, float) else v) for k, v in marks.items()}}, ensure_ascii=False))
        time.sleep(args.pause)
    summary = {}
    for key in ("started", "first", "total"):
        values = [r[key] for r in rows if key in r]
        summary[key] = {"n": len(values), "p50": round(statistics.median(values), 3) if values else None,
                        "p95": round(pct(values, 95), 3) if values else None, "max": round(max(values), 3) if values else None}
    print(json.dumps({"mode": args.mode, "question_set": args.question_set,
                      "completed": sum(r.get("status") == "run.completed" for r in rows),
                      "errors": sum(r.get("status") == "error" for r in rows), "summary": summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()

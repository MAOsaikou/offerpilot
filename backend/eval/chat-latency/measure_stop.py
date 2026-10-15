"""AG-07「中断 1s 内停止」计时：流式输出开始后点停止，测停止生效耗时。

每次用新访客发起长回答，收到首个 text.delta 后再等 --after 秒，调用
POST /api/v1/agent/runs/{id}/cancel，记录：
  cancel_api  取消接口往返耗时
  stream_end  从发出取消到 SSE 流结束的耗时
  late_deltas 取消接口返回后仍收到的 text.delta 数
  status      之后读取会话，助手消息的最终状态（应为 CANCELLED）
只依赖 Python 标准库，不读取密钥。
"""
import argparse
import json
import statistics
import threading
import time
import urllib.request

from measure import pct, post

QUESTION = "请详细讲解 Java 并发编程：线程池参数、锁、volatile、CAS、AQS，每部分至少三段，并给出示例代码。"


def get(url, token):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    return json.load(urllib.request.urlopen(req, timeout=30))


def one(base, after):
    token = json.load(post(base + "/api/v1/auth/guest"))["data"]["accessToken"]
    marks = {"late_deltas": 0}
    state = {"run": None, "conversation": None, "cancel_sent": None, "cancel_done": None}
    response = post(base + "/api/v1/agent/chat", {"content": QUESTION, "mode": "AUTO", "options": {"modelTier": "FLASH"}},
                    token, "text/event-stream")
    event, first = None, None

    def cancel():
        state["cancel_sent"] = time.monotonic()
        post(base + "/api/v1/agent/runs/" + state["run"] + "/cancel", {}, token).read()
        state["cancel_done"] = time.monotonic()

    worker = None
    for raw in response:
        line = raw.decode("utf-8").rstrip("\n")
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:") and event == "run.started" and state["run"] is None:
            data = json.loads(line[5:])
            state["run"], state["conversation"] = data.get("runId"), data.get("conversationId")
        elif line.startswith("data:") and event == "text.delta":
            now = time.monotonic()
            if first is None:
                first = now
            if state["cancel_done"] is not None and now > state["cancel_done"]:
                marks["late_deltas"] += 1
            if worker is None and now - first >= after:
                worker = threading.Thread(target=cancel)
                worker.start()
    end = time.monotonic()
    if worker:
        worker.join()
        marks["cancel_api"] = state["cancel_done"] - state["cancel_sent"]
        marks["stream_end"] = end - state["cancel_sent"]
    if state["conversation"]:
        time.sleep(0.3)
        messages = get(base + "/api/v1/conversations/" + state["conversation"] + "/messages", token)["data"]
        items = messages.get("items", messages) if isinstance(messages, dict) else messages
        assistant = [m for m in items if m.get("role") == "ASSISTANT"]
        marks["status"] = assistant[-1].get("status") if assistant else None
    return marks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18082")
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--after", type=float, default=1.0, help="首个 text.delta 后多久点停止（秒）")
    args = parser.parse_args()
    rows = []
    for i in range(args.runs):
        marks = one(args.base, args.after)
        rows.append(marks)
        print(json.dumps({"run": i + 1, **{k: (round(v, 3) if isinstance(v, float) else v) for k, v in marks.items()}}, ensure_ascii=False))
    summary = {}
    for key in ("cancel_api", "stream_end"):
        values = [r[key] for r in rows if key in r]
        summary[key] = {"n": len(values), "p50": round(statistics.median(values), 3) if values else None,
                        "p95": round(pct(values, 95), 3) if values else None, "max": round(max(values), 3) if values else None}
    summary["late_deltas_total"] = sum(r["late_deltas"] for r in rows)
    summary["status"] = {s: sum(1 for r in rows if r.get("status") == s) for s in {r.get("status") for r in rows}}
    print(json.dumps({"summary": summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()

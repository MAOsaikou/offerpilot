"""RS-01「文本版 PDF 10s 内完成；图片版 30s 内完成」计时。

对目录里的每个 .pdf / .docx / .png / .jpg：新访客上传 POST /api/v1/resumes，每 0.25s 轮询
GET /api/v1/resumes/{id} 直到 READY 或 FAILED，记录从开始上传到终态的秒数。
样本可用 eval/resume-recall/generate.py 生成，图片可用 `pdftoppm -png -r 110 -singlefile` 由 PDF 转成。
只依赖 Python 标准库，不读取密钥；模型调用发生在后端。
"""
import argparse
import json
import mimetypes
import pathlib
import statistics
import time
import urllib.request
import uuid

TYPES = {".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg",
         ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}


def call(req):
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.load(response)


def guest(base):
    return call(urllib.request.Request(base + "/api/v1/auth/guest", data=b"", method="POST"))["data"]["accessToken"]


def upload(base, token, path):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n"
            f"Content-Type: {TYPES[path.suffix.lower()]}\r\n\r\n").encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(base + "/api/v1/resumes", data=body, method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary})
    return call(req)["data"]["id"]


def one(base, token, path, limit):
    t0 = time.monotonic()
    resume = upload(base, token, path)
    while time.monotonic() - t0 < limit:
        data = call(urllib.request.Request(base + "/api/v1/resumes/" + resume, headers={"Authorization": "Bearer " + token}))["data"]
        if data["parseStatus"] in ("READY", "FAILED"):
            return {"status": data["parseStatus"], "seconds": time.monotonic() - t0, "error": data.get("parseError")}
        time.sleep(0.25)
    return {"status": "TIMEOUT", "seconds": time.monotonic() - t0}


def pct(values, p):
    values = sorted(values)
    return values[max(0, min(len(values) - 1, round(p / 100 * len(values) + 0.5) - 1))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dir")
    parser.add_argument("--base", default="http://127.0.0.1:18082")
    parser.add_argument("--per-guest", type=int, default=4)
    parser.add_argument("--limit", type=float, default=120)
    parser.add_argument("--pause", type=float, default=2.0)
    args = parser.parse_args()
    files = sorted(p for p in pathlib.Path(args.dir).iterdir() if p.suffix.lower() in TYPES)
    rows, token = [], None
    for i, path in enumerate(files):
        if i % args.per_guest == 0:
            token = guest(args.base)
        row = {"file": path.name, "kind": "image" if path.suffix.lower() in (".png", ".jpg") else path.suffix[1:].lower(),
               **one(args.base, token, path, args.limit)}
        rows.append(row)
        print(json.dumps({k: (round(v, 2) if isinstance(v, float) else v) for k, v in row.items()}, ensure_ascii=False))
        time.sleep(args.pause)
    for kind in sorted({r["kind"] for r in rows}):
        group = [r for r in rows if r["kind"] == kind]
        ok = [r["seconds"] for r in group if r["status"] == "READY"]
        print(json.dumps({"kind": kind, "n": len(group), "ready": len(ok),
                          "p50": round(statistics.median(ok), 2) if ok else None,
                          "p95": round(pct(ok, 95), 2) if ok else None, "max": round(max(ok), 2) if ok else None}))


if __name__ == "__main__":
    main()

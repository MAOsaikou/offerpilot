#!/usr/bin/env python3
"""Download the two English public resume benchmarks at pinned revisions and build a local corpus.

Nothing here is committed: run `python3 prepare.py <dir>` and the datasets land in <dir>.
- Careerflow/ResumeExtractBench (Hugging Face, CC-BY-4.0): 38 PDFs + human-verified JSON ground truth.
  Personal details are fictional (Jane/John Doe, 555 numbers).
- dotin-inc/resume-dataset-NER-annotations (GitHub, CC0-1.0): set-aside test split (50 CVs) only, plain
  text with inline NER labels. Names/emails were replaced with fake ones by the authors.
Output: <dir>/manifest.json {samples:[{id,dataset,file,sha256}]}, <dir>/truth.json {id: ground truth}.
"""
import hashlib, html, io, json, re, sys, urllib.request, zipfile
from pathlib import Path

REB_REPO = "Careerflow/ResumeExtractBench"
REB_REV = "42aae58fd63b1117f5bb7733ce0d66b659958ea8"
DOTIN_REPO = "dotin-inc/resume-dataset-NER-annotations"
DOTIN_REV = "052429f98489d0b9fc747d58e06db06cdeff2852"
LABEL = re.compile(r'<label type="([^"]+)">(.*?)</label>', re.S)


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def dotin_entities(xml):
    body = xml.split("<cv>", 1)[-1].rsplit("</cv>", 1)[0]
    entities = {}
    for kind, value in LABEL.findall(body):
        value = html.unescape(re.sub(r"\s+", " ", value)).strip()
        if value:
            entities.setdefault(kind, [])
            if value not in entities[kind]:
                entities[kind].append(value)
    text = html.unescape(LABEL.sub(lambda m: m.group(2), body)).strip().strip('"').strip()
    return text, entities


def main(out):
    out = Path(out); (out / "files").mkdir(parents=True, exist_ok=True)
    samples, truth = [], {}
    base = f"https://huggingface.co/datasets/{REB_REPO}/resolve/{REB_REV}/"
    for line in fetch(base + "test.jsonl").decode().splitlines():
        row = json.loads(line)
        sid = "reb:" + row["resume_id"]
        target = out / "files" / ("reb-" + Path(row["files"]["pdf"]).name)
        if not target.exists():
            target.write_bytes(fetch(base + row["files"]["pdf"]))
        samples.append({"id": sid, "dataset": "ResumeExtractBench", "source": row.get("source"), "file": str(target.relative_to(out))})
        truth[sid] = row["ground_truth"]
    archive = zipfile.ZipFile(io.BytesIO(fetch(f"https://github.com/{DOTIN_REPO}/raw/{DOTIN_REV}/set_aside_test_v2_50cvs.zip")))
    for name in sorted(n for n in archive.namelist() if n.endswith(".xml")):
        text, entities = dotin_entities(archive.read(name).decode("utf-8", "replace"))
        if len(text) < 200:
            continue
        sid = "dotin:" + Path(name).stem
        target = out / "files" / ("dotin-" + Path(name).stem + ".txt")
        target.write_text(text, encoding="utf-8")
        samples.append({"id": sid, "dataset": "dotin-NER", "file": str(target.relative_to(out))})
        truth[sid] = entities
    for s in samples:
        s["sha256"] = hashlib.sha256((out / s["file"]).read_bytes()).hexdigest()
    manifest = {"provenance": "public_benchmark_en", "sources": [
        {"dataset": REB_REPO, "revision": REB_REV, "license": "CC-BY-4.0", "attribution": "Careerflow, ResumeExtractBench (huggingface.co/datasets/Careerflow/ResumeExtractBench)"},
        {"dataset": DOTIN_REPO, "revision": DOTIN_REV, "license": "CC0-1.0", "split": "set_aside_test_v2_50cvs", "attribution": "dotin Inc., Pinzon et al., SEPLN 65 (2020)"}],
        "samples": samples}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1))
    (out / "truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1))
    print(f"{len(samples)} samples -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "data")

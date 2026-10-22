#!/usr/bin/env python3
"""Score OfferPilot parse outputs against the English public benchmarks (see prepare.py / README.md).

Per dataset and field: recall = matched ground-truth items / ground-truth items, precision = predicted
items that match some ground-truth item / predicted items. A failed or missing sample counts as an
empty prediction (zero recall, contributes nothing to precision). n = number of items in the denominator.
Precision is only reported where the ground truth is exhaustive for that field (ResumeExtractBench, and
single-valued name/email for dotin); NER mentions are not an exhaustive list, so dotin list fields are recall-only.
"""
import json, re, sys
from collections import defaultdict

STOP = {"and", "of", "the", "in", "a", "an", "for", "at", "&"}


def norm(s):
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", str(s or "").lower())


def tokens(s):
    return [t for t in re.findall(r"[0-9a-z\u4e00-\u9fff]+", str(s or "").lower()) if t not in STOP]


def match(pred, gold):
    """Equal after normalisation, containment covering >=60% of the longer string, or token F1 >= 0.6."""
    a, b = norm(pred), norm(gold)
    if not a or not b:
        return False
    if a == b:
        return True
    short, long_ = sorted((a, b), key=len)
    if short in long_ and len(short) >= 0.6 * len(long_):
        return True
    ta, tb = set(tokens(pred)), set(tokens(gold))
    if not ta or not tb:
        return False
    inter = len(ta & tb)
    return inter and 2 * inter / (len(ta) + len(tb)) >= 0.6


def phone_match(pred, gold):
    a, b = re.sub(r"\D", "", str(pred or "")), re.sub(r"\D", "", str(gold or ""))
    return len(b) >= 7 and len(a) >= 7 and a[-10:] == b[-10:]


def summary_match(pred, gold):
    g = set(tokens(gold))
    return bool(g) and len(g & set(tokens(pred))) / len(g) >= 0.6


def ym(year, month=None):
    return None if not year else f"{int(year):04d}" + (f"-{int(month):02d}" if month else "")


def pred_ym(text):
    t = str(text or "").lower()
    if re.search(r"present|current|now|至今|今", t):
        return "present"
    m = re.search(r"(\d{4})\D{1,3}(\d{1,2})(?!\d)", t)
    if m and 1 <= int(m.group(2)) <= 12:
        return f"{m.group(1)}-{int(m.group(2)):02d}"
    months = "jan feb mar apr may jun jul aug sep oct nov dec".split()
    m = re.search(r"(" + "|".join(months) + r")[a-z]*\.?\s*(\d{4})", t)
    if m:
        return f"{m.group(2)}-{months.index(m.group(1)) + 1:02d}"
    m = re.search(r"(" + "|".join(months) + r")[a-z]*\.?\s*['\u2019](\d{2})(?!\d)", t)
    if m:
        return f"20{m.group(2)}-{months.index(m.group(1)) + 1:02d}" if int(m.group(2)) < 50 else f"19{m.group(2)}-{months.index(m.group(1)) + 1:02d}"
    m = re.search(r"(\d{1,2})/(\d{4})", t)
    if m and 1 <= int(m.group(1)) <= 12:
        return f"{m.group(2)}-{int(m.group(1)):02d}"
    m = re.search(r"(19|20)\d{2}", t)
    return m.group(0) if m else None


def date_match(pred, gold):
    """gold is 'YYYY', 'YYYY-MM' or 'present'; a year-only gold accepts any month of that year."""
    p = pred_ym(pred)
    return gold is not None and p is not None and (p == gold or (len(gold) == 4 and p[:4] == gold))


class Score:
    def __init__(self):
        self.c = defaultdict(lambda: [0, 0, 0, 0])  # recall hit, recall n, precision hit, precision n

    def recall(self, field, golds, preds, fn=match):
        for g in golds:
            self.c[field][1] += 1
            self.c[field][0] += any(fn(p, g) for p in preds)

    def precision(self, field, golds, preds, fn=match):
        for p in preds:
            if norm(p):
                self.c[field][3] += 1
                self.c[field][2] += any(fn(p, g) for g in golds)

    def both(self, field, golds, preds, fn=match):
        golds = [g for g in golds if norm(g)]
        self.recall(field, golds, preds, fn)
        self.precision(field, golds, preds, fn)

    def table(self):
        out = {}
        for f, (rh, rn, ph, pn) in sorted(self.c.items()):
            out[f] = {"recall": round(rh / rn, 3) if rn else None, "recall_n": rn,
                      "precision": round(ph / pn, 3) if pn else None, "precision_n": pn}
        return out


def items(node, key):
    return [x.get(key) for x in (node or []) if isinstance(x, dict) and x.get(key)]


def score_reb(score, gt, out):
    b, ob = gt.get("basics", {}), out.get("basics") or {}
    name = " ".join(x for x in (b.get("fname"), b.get("lname")) if x)
    for field, gold, pred, fn in [("basics.name", name, ob.get("name"), match), ("basics.email", b.get("email"), ob.get("email"), match),
                                  ("basics.phone", b.get("phone"), ob.get("phone"), phone_match), ("basics.city", b.get("city"), ob.get("city"),
                                  lambda p, g: norm(g) in norm(p))]:
        score.both(field, [gold] if gold else [], [pred] if pred else [], fn)
    score.both("summary", [gt.get("personalSummary")] if gt.get("personalSummary") else [], [out.get("summary")] if out.get("summary") else [], summary_match)
    sections = [("experience", "company", "org", "position", "title"), ("education", "institution", "school", "studyType", "degree")]
    for sec, gkey, okey, gsub, osub in sections:
        got = out.get(sec) or []
        score.both(f"{sec}.{okey}", items(gt.get(sec), gkey), items(got, okey))
        for g in gt.get(sec) or []:
            same_org = [o for o in got if isinstance(o, dict) and match(o.get(okey), g.get(gkey))]
            # Several roles at one employer: pair with the entry whose title also matches, if any.
            pair = next((o for o in same_org if match(o.get(osub), g.get(gsub))), same_org[0] if same_org else {})
            if g.get(gsub):
                score.recall(f"{sec}.{osub}", [g[gsub]], [pair.get(osub)] if pair.get(osub) else [])
            if sec == "education" and g.get("area"):
                score.recall("education.major", [g["area"]], [pair.get("major")] if pair.get("major") else [])
            start, end = ym(g.get("startYear"), g.get("startMonth")), ("present" if g.get("currentlyWorkHere") or g.get("currentlyStudyHere") else ym(g.get("endYear"), g.get("endMonth")))
            if start:
                score.recall(f"{sec}.start", [start], [pair.get("start")] if pair.get("start") else [], date_match)
            if end:
                score.recall(f"{sec}.end", [end], [pair.get("end")] if pair.get("end") else [], date_match)
    score.both("projects.name", items(gt.get("projects"), "name"), items(out.get("projects"), "name"))
    score.both("certificates", items(gt.get("certifications"), "name"), [c for c in out.get("certificates") or [] if isinstance(c, str)])
    gold_skills = [s for grp in gt.get("skills") or [] for s in (grp.get("skills") or [])]
    pred_skills = [s for grp in out.get("skills") or [] if isinstance(grp, dict) for s in (grp.get("items") or [])]
    score.both("skills.items", gold_skills, pred_skills)


def score_dotin(score, gt, out):
    ob = out.get("basics") or {}
    score.both("basics.name", gt.get("Name", [])[:1], [ob.get("name")] if ob.get("name") else [])
    score.both("basics.email", gt.get("Email Address", [])[:1], [ob.get("email")] if ob.get("email") else [])
    score.recall("experience.org", gt.get("Companies worked at", []), items(out.get("experience"), "org"))
    score.recall("experience.title", gt.get("Designation", []), items(out.get("experience"), "title") + [ob.get("title") or ""])
    score.recall("education.school", gt.get("College Name", []), items(out.get("education"), "school"))
    score.recall("education.degree", gt.get("Degree", []), [" ".join(filter(None, (e.get("degree"), e.get("major")))) for e in out.get("education") or [] if isinstance(e, dict)])
    pred_skills = [s for grp in out.get("skills") or [] if isinstance(grp, dict) for s in (grp.get("items") or [])]
    score.recall("skills.items(tech tools)", gt.get("Tech Tools", []), pred_skills)


def main(corpus, outputs_name="outputs.json"):
    manifest = json.load(open(f"{corpus}/manifest.json"))
    truth = json.load(open(f"{corpus}/truth.json"))
    outputs = json.load(open(f"{corpus}/{outputs_name}"))
    report = {}
    for dataset, fn in (("ResumeExtractBench", score_reb), ("dotin-NER", score_dotin)):
        score, failures, samples = Score(), [], 0
        for s in manifest["samples"]:
            if s["dataset"] != dataset:
                continue
            samples += 1
            entry = outputs.get(s["id"]) or {"failure": "not run"}
            if "failure" in entry:
                failures.append(f"{s['id']}: {entry['failure']}")
            fn(score, truth[s["id"]], entry.get("structured") or {})
        report[dataset] = {"samples": samples, "failures": failures, "fields": score.table()}
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main(*sys.argv[1:3])

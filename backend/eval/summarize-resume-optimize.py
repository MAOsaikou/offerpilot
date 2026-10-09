"""Summarize saved live-model evidence. No network, credentials, or business writes."""
import argparse
import hashlib
import json
import math
from pathlib import Path


def digest(results):
    compact = json.dumps(results, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(compact.encode("utf-8")).hexdigest()


def human_metrics(results, review):
    pending = {"status": "PENDING_TWO_HUMAN_REVIEWERS", "factSafeRate": None,
               "adoptionRate": None, "reviewerAdoptionRates": None}
    if review is None:
        return pending
    if review.get("runId") != results["runId"] or review.get("resultsSha256") != digest(results):
        raise ValueError("Human review does not match this results snapshot")
    reviewers = review.get("reviewers", [])
    if len(reviewers) != 2 or {r.get("slot") for r in reviewers} != {1, 2}:
        raise ValueError("Exactly two distinct human reviewer slots are required")
    names = [r.get("reviewerName") for r in reviewers]
    if not all(isinstance(n, str) and n.strip() for n in names):
        return pending
    if names[0].strip() == names[1].strip():
        raise ValueError("Reviewers must be two different people")
    case_ids = {c["id"] for c in results["cases"]}
    by_reviewer = []
    for reviewer in reviewers:
        cases = reviewer.get("cases", [])
        if len(cases) != len(case_ids) or {c.get("caseId") for c in cases} != case_ids:
            raise ValueError("Human review case set differs from saved results")
        by_reviewer.append({c["caseId"]: c for c in cases})
    fact_safe, adopted, votes, disagreements, empty_ok = [], [], [[], []], [], []
    for result in results["cases"]:
        expected_patches = result.get("report", {}).get("patches", [])
        expected_ids = {p["id"] for p in expected_patches}
        ratings = []
        for cases in by_reviewer:
            case = cases[result["id"]]
            if case.get("generationStatus") != result["status"]:
                raise ValueError("Human review generation status changed")
            patches = case.get("patches", [])
            if len(patches) != len(expected_ids) or {p.get("patchId") for p in patches} != expected_ids:
                raise ValueError("Human review patch set differs from generated suggestions")
            ratings.append({p["patchId"]: p for p in patches})
            if result["status"] == "COMPLETED" and not expected_patches:
                if type(case.get("emptyAppropriate")) is not bool or not str(case.get("reason") or "").strip():
                    return pending
                empty_ok.append(case["emptyAppropriate"])
        for patch in expected_patches:
            pair = [r[patch["id"]] for r in ratings]
            if any(type(p.get("factSafe")) is not bool or type(p.get("adopt")) is not bool
                   or not str(p.get("reason") or "").strip() for p in pair):
                return pending
            if any(p["adopt"] and not p["factSafe"] for p in pair):
                raise ValueError("A fabricated suggestion cannot be marked adopted")
            fact_safe.append(all(p["factSafe"] for p in pair))
            adopted.append(all(p["adopt"] for p in pair))
            for slot, rating in enumerate(pair):
                votes[slot].append(rating["adopt"])
            if pair[0]["factSafe"] != pair[1]["factSafe"] or pair[0]["adopt"] != pair[1]["adopt"]:
                disagreements.append(f'{result["id"]}/{patch["id"]}')
    count = len(adopted)
    return {"status": "REVIEWED", "factSafeRate": sum(fact_safe) / count if count else None,
            "adoptionRate": sum(adopted) / count if count else None,
            "reviewerAdoptionRates": [sum(v) / count if count else None for v in votes],
            "suggestionsReviewed": count, "allEmptyCasesAppropriate": all(empty_ok),
            "disagreements": disagreements}


def summarize(results, review=None):
    cases = results["cases"]
    if not cases or len({c["id"] for c in cases}) != len(cases):
        raise ValueError("Results must contain nonempty, unique cases")
    complete = [c for c in cases if c["status"] == "COMPLETED"]
    latencies = sorted(c["wallDurationMs"] for c in cases)
    attempts = [a for c in cases for a in c["attempts"]]
    usage = {}
    for key in ("inputTokens", "outputTokens"):
        known = [a[key] for a in attempts if isinstance(a.get(key), int)]
        usage[key] = sum(known) if len(known) == len(attempts) else None
        usage[key + "ReportedSubtotal"] = sum(known)
        usage[key + "MissingAttempts"] = len(attempts) - len(known)
    human = human_metrics(results, review)
    boundary_pass = sum(c.get("automaticBoundaryPass") is True for c in complete)
    summary = {"runId": results["runId"], "model": results["model"],
               "casesTotal": len(cases), "generationCompleted": len(complete),
               "firstAttemptPassed": sum(c.get("modelAttempts") == 1 for c in complete),
               "firstAttemptTarget": .95,
               "automaticBoundaryPassed": boundary_pass,
               "suggestionsTotal": sum(len(c["report"]["patches"]) for c in complete),
               "emptyCases": [c["id"] for c in complete if not c["report"]["patches"]],
               "modelAttempts": len(attempts), "usage": usage,
               "latencyMs": {"p50NearestRank": latencies[math.ceil(len(latencies) * .5) - 1],
                             "p95NearestRank": latencies[math.ceil(len(latencies) * .95) - 1]},
               "billingCost": None, "billingNote": "No billing unit price or invoice supplied; token usage only",
               "human": human}
    summary["firstAttemptRate"] = summary["firstAttemptPassed"] / len(cases)
    summary["firstAttemptTargetMet"] = summary["firstAttemptRate"] >= summary["firstAttemptTarget"]
    if human["status"] != "REVIEWED":
        summary["qualityGate"] = "PENDING_HUMAN_REVIEW"
    elif human["factSafeRate"] is None:
        summary["qualityGate"] = "INSUFFICIENT_SUGGESTIONS"
    else:
        passed = len(complete) == len(cases) and boundary_pass == len(cases) and human["factSafeRate"] == 1 \
            and human["adoptionRate"] >= .6 and human["allEmptyCasesAppropriate"] and not human["disagreements"] \
            and summary["firstAttemptTargetMet"]
        summary["qualityGate"] = "PASSED" if passed else "NOT_MET"
    return summary


def markdown(results, summary):
    lines = ["# 简历定向改写真实模型评测", "", f'运行：`{results["runId"]}`', "",
             f'模型：`{results["model"]}`；提示词 SHA-256：`{results["promptSha256"]}`。',
             f'基准 SHA-256：`{results["datasetSha256"]}`；源码：`{results["sourceRevision"]}`。', "",
             f'生成完成 {summary["generationCompleted"]}/{summary["casesTotal"]}；首次校验通过 {summary["firstAttemptPassed"]}/{summary["casesTotal"]}；',
             f'预声明自动边界通过 {summary["automaticBoundaryPassed"]}/{summary["casesTotal"]}；建议 {summary["suggestionsTotal"]} 条。',
             f'首次结构化校验通过率 {summary["firstAttemptRate"]:.0%}；目标 ≥95%；达标：{summary["firstAttemptTargetMet"]}。',
             f'整例耗时 P50/P95（nearest-rank，含修复与失败）为 {summary["latencyMs"]["p50NearestRank"]}/{summary["latencyMs"]["p95NearestRank"]} ms。',
             f'输入/输出 token：{summary["usage"]["inputTokens"]}/{summary["usage"]["outputTokens"]}；费用未估算，未提供单价或账单。', "",
             f'人工质量门禁：`{summary["qualityGate"]}`。两名真人评审状态：`{summary["human"]["status"]}`。',
             "规则和词汇哨兵不证明无编造率；Agent审阅不等于真人评审或实际用户采纳。", ""]
    for case in results["cases"]:
        lines.extend([f'## {case["id"]} · {case["label"]}', "",
                      f'状态：{case["status"]}；模型尝试：{case["modelAttempts"]}；耗时：{case["wallDurationMs"]} ms。',
                      f'预声明审阅重点：{case["fixture"]["reviewNotes"]}', "",
                      "JD：" + case["fixture"]["jobDescription"], "",
                      "自动警告：" + ("、".join(case.get("automaticWarnings", [])) or "无；失败案例不计通过"), ""])
        if case["status"] != "COMPLETED":
            lines.extend(["错误码：" + case["errorCode"], ""])
            continue
        lines.extend(["说明：" + case["report"]["summary"], ""])
        for patch in case["report"]["patches"]:
            lines.extend([f'### {patch["id"]} · `{patch["path"]}`', "",
                          "原文：" + patch["from"], "", "建议：" + patch["value"], "",
                          "理由：" + patch["reason"], "", "JD 引文：" + patch["jdEvidence"], "",
                          "需用户确认：" + str(patch["needsUserConfirmation"]) + "；" + patch.get("confirmationHint", ""), ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", type=Path)
    args = parser.parse_args()
    directory = args.run_directory.resolve()
    results = json.loads((directory / "results.json").read_text(encoding="utf-8"))
    review_path = directory / "human-review.json"
    review = json.loads(review_path.read_text(encoding="utf-8")) if review_path.exists() else None
    summary = summarize(results, review)
    (directory / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (directory / "review.md").write_text(markdown(results, summary), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

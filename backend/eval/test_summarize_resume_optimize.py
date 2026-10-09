import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("summarize-resume-optimize.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SummaryTest(unittest.TestCase):
    def results(self):
        return {"runId": "fixture", "model": "fixture-model", "cases": [
            {"id": "a", "status": "COMPLETED", "wallDurationMs": 10, "modelAttempts": 1,
             "attempts": [{"inputTokens": 11, "outputTokens": 12}], "automaticBoundaryPass": True,
             "report": {"patches": [{"id": "p1"}]}},
            {"id": "b", "status": "FAILED", "wallDurationMs": 20, "modelAttempts": 2,
             "attempts": [{"inputTokens": 21, "outputTokens": 22}, {}]}]}

    def review(self, results):
        return {"runId": results["runId"], "resultsSha256": module.digest(results), "reviewers": [
            {"slot": slot, "reviewerName": name, "cases": [
                {"caseId": "a", "generationStatus": "COMPLETED", "patches": [
                    {"patchId": "p1", "factSafe": True, "adopt": True, "reason": "Known fixture facts"}]},
                {"caseId": "b", "generationStatus": "FAILED", "patches": []}]}
            for slot, name in [(1, "Person A"), (2, "Person B")]]}

    def test_incomplete_human_review_never_counts_as_zero_or_pass(self):
        results = self.results()
        review = self.review(results)
        review["reviewers"][1]["cases"][0]["patches"][0]["factSafe"] = None
        summary = module.summarize(results, review)
        self.assertEqual(summary["qualityGate"], "PENDING_HUMAN_REVIEW")
        self.assertIsNone(summary["human"]["factSafeRate"])

    def test_failures_remain_in_denominator_and_missing_tokens_not_zero(self):
        results = self.results()
        summary = module.summarize(results, self.review(results))
        self.assertEqual(summary["casesTotal"], 2)
        self.assertEqual(summary["generationCompleted"], 1)
        self.assertEqual(summary["qualityGate"], "NOT_MET")
        self.assertIsNone(summary["usage"]["inputTokens"])
        self.assertEqual(summary["usage"]["inputTokensReportedSubtotal"], 32)
        self.assertEqual(summary["usage"]["inputTokensMissingAttempts"], 1)
        self.assertEqual(summary["latencyMs"]["p95NearestRank"], 20)

    def test_rejects_review_for_other_run_or_changed_evidence(self):
        results = self.results()
        review = self.review(results)
        results["cases"][0]["report"]["patches"][0]["value"] = "Changed"
        with self.assertRaises(ValueError):
            module.summarize(results, review)

    def test_disagreement_blocks_quality_gate(self):
        results = self.results()
        results["cases"] = results["cases"][:1]
        review = self.review(results)
        for reviewer in review["reviewers"]:
            reviewer["cases"] = reviewer["cases"][:1]
        review["reviewers"][1]["cases"][0]["patches"][0]["adopt"] = False
        summary = module.summarize(results, review)
        self.assertEqual(summary["human"]["reviewerAdoptionRates"], [1.0, 0.0])
        self.assertEqual(summary["human"]["adoptionRate"], 0.0)
        self.assertEqual(summary["qualityGate"], "NOT_MET")

    def test_empty_cases_require_review_and_do_not_inflate_adoption(self):
        results = self.results()
        results["cases"] = results["cases"][:1]
        results["cases"][0]["report"]["patches"] = []
        review = self.review(results)
        for reviewer in review["reviewers"]:
            reviewer["cases"] = [{"caseId": "a", "generationStatus": "COMPLETED", "patches": [],
                                  "emptyAppropriate": True, "reason": "Already clear"}]
        summary = module.summarize(results, review)
        self.assertIsNone(summary["human"]["adoptionRate"])
        self.assertEqual(summary["qualityGate"], "INSUFFICIENT_SUGGESTIONS")

    def test_complete_review_cannot_hide_poor_first_attempt_stability(self):
        results = self.results()
        results["cases"] = results["cases"][:1]
        review = self.review(results)
        for reviewer in review["reviewers"]:
            reviewer["cases"] = reviewer["cases"][:1]
        self.assertEqual(module.summarize(results, review)["qualityGate"], "PASSED")
        results["cases"][0]["modelAttempts"] = 2
        results["cases"][0]["attempts"].append({"inputTokens": 1, "outputTokens": 1})
        review["resultsSha256"] = module.digest(results)
        summary = module.summarize(results, review)
        self.assertEqual(summary["human"]["factSafeRate"], 1.0)
        self.assertEqual(summary["qualityGate"], "NOT_MET")
        self.assertFalse(summary["firstAttemptTargetMet"])


if __name__ == "__main__":
    unittest.main()

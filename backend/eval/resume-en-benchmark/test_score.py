import unittest
from score import Score, match, date_match, phone_match, score_reb, score_dotin


class ScoreTest(unittest.TestCase):
    def test_matching_rules(self):
        self.assertTrue(match("Massachusetts Institute of Technology (MIT)", "Massachusetts Institute of Technology"))
        self.assertFalse(match("Java", "JavaScript"))
        self.assertTrue(date_match("Jan 2021", "2021-01"))
        self.assertTrue(date_match("06/2019", "2019"))
        self.assertTrue(date_match("Present", "present"))
        self.assertTrue(date_match("Feb \u201919", "2019-02"))
        self.assertTrue(phone_match("415-555-0198", "+1 (415) 555-0198"))

    def test_failure_counts_as_zero_recall_and_no_precision(self):
        gt = {"basics": {"fname": "Alex", "lname": "Winters", "email": "a@x.com"}, "experience": [{"company": "Apex", "position": "Engineer", "startYear": 2021}]}
        s = Score(); score_reb(s, gt, {})
        t = s.table()
        self.assertEqual(t["basics.name"]["recall"], 0.0)
        self.assertEqual(t["experience.org"]["recall_n"], 1)
        self.assertIsNone(t["experience.org"]["precision"])

    def test_repeated_employer_pairs_by_title(self):
        gt = {"experience": [{"company": "TechNova", "position": "Engineer", "startYear": 2014}]}
        out = {"experience": [{"org": "TechNova", "title": "Director", "start": "2023"}, {"org": "TechNova", "title": "Engineer", "start": "2014"}]}
        s = Score(); score_reb(s, gt, out)
        self.assertEqual(s.table()["experience.start"]["recall"], 1.0)

    def test_precision_penalises_invented_items(self):
        gt = {"certifications": [{"name": "AWS Certified Developer"}]}
        s = Score(); score_reb(s, gt, {"certificates": ["AWS Certified Developer", "Dean's List"]})
        self.assertEqual(s.table()["certificates"]["precision"], 0.5)

    def test_dotin_list_fields_are_recall_only(self):
        s = Score(); score_dotin(s, {"Companies worked at": ["Acme"]}, {"experience": [{"org": "Acme"}, {"org": "Other"}]})
        self.assertEqual(s.table()["experience.org"]["recall"], 1.0)
        self.assertIsNone(s.table()["experience.org"]["precision"])


if __name__ == "__main__":
    unittest.main()

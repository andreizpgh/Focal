"""Regression checks for the offline review-model comparison. Uses no model or network."""
import importlib.util
import json
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / "bench-review-model.py"
spec = importlib.util.spec_from_file_location("bench_review_model", path)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class ReviewScreenTest(unittest.TestCase):
    def test_a_changed_number_or_negation_rejects_the_whole_punctuation_reply(self):
        source = "Не платить 15.50 до пятницы"
        for reply in ("Платить 15.50 до пятницы.", "Не платить 1550 до пятницы."):
            result = bench.punct_scores(source, reply, source + ".")
            self.assertEqual(result["rejected"], 1)
            self.assertEqual(result["before_errors"], result["after_errors"])

    def test_only_boundaries_between_adjacent_matching_words_are_scored(self):
        scores = bench.punct_scores("А потом дождь", "А, потом дождь.", "А сильный, потом дождь.")
        self.assertEqual(scores["boundaries"], 2)
        self.assertEqual(scores["fixed"], 1)
        self.assertEqual(scores["damaged"], 0)

    def test_punctuation_can_both_help_and_harm_in_the_same_note(self):
        scores = bench.punct_scores("Я думаю что дождь", "Я, думаю, что дождь.", "Я думаю, что дождь.")
        self.assertEqual((scores["fixed"], scores["damaged"]), (2, 1))

    def test_word_metrics_count_extra_words_even_if_the_spelling_also_improves(self):
        self.assertEqual(bench.word_scores(["Это", "кот"], 1, 2, "код", "Это код")["improved"], 1)
        self.assertEqual(bench.word_scores(["Это", "код"], 1, 2, "плохой код", "Это код")["harmed"], 1)
        self.assertEqual(bench.word_scores(["Это", "кот"], 1, 2, "KEEP", "Это код")["changed"], 0)

    def test_control_taps_do_not_repeat_a_flagged_word_or_cross_a_sentence(self):
        words = [dict(text=t, flag=f, confidence=c) for t, f, c in
                 [("Раз", True, .1), ("два.", True, .2), ("Три", True, .3), ("четыре", False, .6), ("пять", False, .9)]]
        self.assertEqual(list(bench.selections(words)),
                         [("flag", 0, 2), ("flag", 2, 3), ("low_tap", 3, 4), ("high_tap", 4, 5)])

    def test_paragraph_suggestions_only_address_real_internal_boundaries(self):
        self.assertEqual(bench.paragraph_breaks("[1, 3]", 4), {1, 3})
        for reply in ("not json", "[true]", "[2,1]", "[2,2]", "[0]", "[4]", "1"):
            self.assertIsNone(bench.paragraph_breaks(reply, 4))

    def test_keep_and_no_break_report_the_defined_baselines(self):
        results = bench.synthetic_screen(json.loads(bench.FIXTURES.read_text()),
                                         lambda prompt, _text, _limit: "A" if prompt == bench.CHOICE_PROMPT else "[]")
        self.assertEqual(results["word_choice"], dict(queries=16, rejected=0, fixed=0, damaged=0))
        self.assertEqual(results["paragraphs"]["exact_notes"], 4)
        self.assertEqual(results["paragraphs"]["missed"], 5)

    def test_public_word_receipts_reproduce_the_published_dev_baseline(self):
        cases = json.loads((bench.BENCHMARK / "fleurs-dev-words.json").read_text())
        clips = [c for c in bench.wer.load_references(bench.wer.REFERENCES) if c["id"] in cases]
        self.assertEqual(len(cases), len(clips))
        self.assertTrue(all(c["split"] == "dev" for c in clips))
        counts = bench.speech_baseline(cases, clips)
        self.assertEqual((counts["ru"]["reference_words"], counts["ru"]["word_errors"]), (105, 13))
        self.assertEqual((counts["en"]["reference_words"], counts["en"]["word_errors"]), (88, 5))

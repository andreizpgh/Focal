"""Guard the lexical and timing boundaries of the word-continuation research scorer."""
from collections import Counter
from importlib.util import find_spec
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "word-review"))
import continuations as review


class WordContinuationTest(unittest.TestCase):
    @unittest.skipUnless(find_spec("scipy") and find_spec("numpy"), "optional decision-screen dependencies")
    def test_holdout_labels_cannot_train_the_ranker_or_choose_its_threshold(self):
        helpful = (.3, .82, .7, 0, 0, 1, .2)
        neutral = (.2, .5, .3, 0, 0, 0, .4)
        harmful = (.1, .25, -.8, 1, 1, 0, .9)
        dev = [(str(i), lang, "dev", [(helpful, -1), (neutral, 0), (harmful, 1)])
               for lang in ("el", "fr", "ru") for i in range(4)]
        a = review.decision_screen(dev + [("t", "lv", "holdout", [(helpful, -1)])])
        b = review.decision_screen(dev + [("t", "lv", "holdout", [(helpful, 1)])])
        self.assertEqual(a["coefficients"], b["coefficients"])
        self.assertEqual(a["ten_percent_budget"]["threshold"], b["ten_percent_budget"]["threshold"])
        self.assertEqual(a["ten_percent_budget"]["holdout"]["helpful"], 1)
        self.assertEqual(b["ten_percent_budget"]["holdout"]["harmful"], 1)

    def test_inner_piece_preserves_prefix_and_can_split_into_two_words(self):
        tokens = [[str(i * .08), ".08", "-0.8", s.encode().hex()]
                  for i, s in enumerate((" ca", "t", " nap"))]
        vocab = dict(enumerate((" ca", "t", " nap", "n", " do")))
        kinds = {s: 2 if s.startswith(" ") else 1 for s in vocab.values()}
        rows = [["S", "1", "2", "3", "-1.1", "-0.3"],
                ["C", "1", "2", "-1.1", "-0.3", ".2", "0", "0", "3", "4"],
                ["N", "1", "1", "3", "2"]]
        words = [(1000, 1160, False, "Cat,"), (1160, 1240, False, "nap.")]
        def extract(words):
            return list(review.window_candidates(rows, tokens, 1000, words, vocab, kinds, Counter()))
        variants = extract(words)
        self.assertEqual([k for k, _ in variants], [(1000, 1160, "cat")] * 2)
        fixed, branch = (v for _, v in variants)
        self.assertEqual(fixed.text, "can")
        self.assertIsNone(fixed.delta)
        self.assertEqual(branch.text, "can do")
        self.assertFalse(extract([(1000, 1160, True, "Cat,")]))
        self.assertFalse(extract(words + [words[0]]))
        self.assertFalse(extract([(1000, 1260, False, "Cat,")]))

    def test_only_an_independent_confident_phrase_can_corroborate(self):
        words = ["🎧", "a", "lot", "allot."]
        text = " ".join(words)
        size = lambda s: len(s.encode("utf-16-le")) // 2
        ends = [size(" ".join(words[:i + 1])) for i in range(len(words))]
        span = dict(first=3, last=3, begin=ends[2] + 1, end=ends[3] - 1, owners=["n:0"])
        timed = [(s, i * 100, i * 100 + 90) for i, s in enumerate(words)]
        candidate = review.Candidate("a lot", .21, .58, -.3, 0)
        indexes = ({("n:0", 300, 390, "allot"): [candidate]}, {})
        def choices(flags):
            return review.suggest(span, text.encode("utf-16-le"), timed, ends, flags, indexes)
        self.assertEqual(choices([False, False, False, True])["corroborated"], ["a lot"])
        self.assertEqual(choices([False, False, True, True])["corroborated"], [])
        self.assertEqual(choices([False, False, True, True])["strict"], ["a lot"])

    def test_incomplete_or_unbounded_receipts_fail(self):
        for rows in ([], [["N", "9", "0", "0", "0"]], [["N", "1", "1", "1", "1"]]):
            with self.assertRaises(ValueError):
                list(review.window_candidates(rows, [], 0, [], {}, {}, Counter()))

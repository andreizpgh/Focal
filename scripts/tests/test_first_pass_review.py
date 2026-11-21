"""Regression checks for acoustic token alternatives and read-mode suggestion selection."""
import importlib.util
from pathlib import Path
import math
import hashlib
import sys
from tempfile import TemporaryDirectory
import unittest

DIR = Path(__file__).resolve().parents[1] / "first-pass-review"


def module(name):
    spec = importlib.util.spec_from_file_location(name, DIR / (name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


variants = module("variants")
# evaluate imports its sibling; keep test discovery independent of other evaluate.py modules.
sys.modules.setdefault("variants", variants)
experiment = module("evaluate")


class FirstPassReviewTest(unittest.TestCase):
    def test_boundary_blank_digit_and_mark_handling(self):
        lp = math.log(.42)
        raw = [["0", "0.08", str(lp), " č".encode().hex()]]
        rank = [["0", "0", str(lp), "1", str(math.log(.30)), "2", str(math.log(.22))]]
        vocab = {0: " č", 1: " š", 2: "a"}
        self.assertEqual([v[3] for v in variants.candidate_pieces(raw, rank, vocab)], [" š"])
        for x in (" <blk>", " 2", " ;", " Č"):
            self.assertFalse(list(variants.candidate_pieces(raw, rank, {**vocab, 1:x})))
        self.assertTrue(variants.letters("a\u0304"))
        self.assertFalse(variants.letters("\u0304"))

    def test_missing_or_wrong_winner_fails(self):
        raw = [["0", ".08", "-1", "ax".encode().hex()]]
        with self.assertRaises(ValueError):
            list(variants.candidate_pieces(raw, [], {}))
        with self.assertRaises(ValueError):
            list(variants.candidate_pieces(raw, [["0","0","-1","1","-2","2","-3"]], {0:"ay",1:"ax",2:"z"}))

    def test_strict_requires_whole_piece_or_nearby_confirmation(self):
        near = (.25, .75, "word", False, True)
        fragment = (.31, .88, "ward", False, False)
        whole = (.22, .62, "world", True, False)
        menus = experiment.menus([near, fragment, whole])
        self.assertEqual(menus["broad"], ["ward", "word"])
        self.assertEqual(menus["strict"], ["word"])
        self.assertEqual(experiment.menus([(.19,.9,"ward",True,True)])["strict"], [])
        self.assertEqual(experiment.menus([(.29,.55,"ward",True,True)])["strict"], [])
        self.assertEqual(experiment.menus([whole])["strict"], ["world"])

    def test_unicode_dedup_and_grouped_selection(self):
        options = [(.28,.8,"mā tur",True,False),(.27,.7,"ma\u0304 tur",True,False)]
        self.assertEqual(experiment.menus(options)["broad"], ["mā tur"])
        self.assertEqual(experiment.core("«ma\u0304!»"), "ma\u0304")

    def test_replacement_preserves_grouped_words_and_utf16_punctuation(self):
        draft = "🎙 «Пел дом!»"
        encoded = draft.encode("utf-16-le")
        size = lambda s: len(s.encode("utf-16-le")) // 2
        words = [("🎙", 0, 80), ("«Пел", 80, 240), ("дом!»", 240, 450)]
        ends = [size("🎙"), size("🎙 «Пел"), size(draft)]
        span = dict(first=1, begin=size("🎙 «"), end=size("🎙 «Пел дом"), owners=["n:0", "n:0"])
        variants_by_word = {("n:0", 80, 240, "пел"): [(.28, .8, "Бел", True)]}
        result = experiment.suggestions_for_span(span, encoded, words, ends, [False, True, True], variants_by_word)
        self.assertEqual(result["strict"], ["Бел дом"])

    def test_existing_helpful_option_is_not_counted_again(self):
        with TemporaryDirectory() as temp:
            folder = Path(temp)
            for name, text in {
                "asr.txt": "We herd.",
                "ref.txt": "We heard.",
                "words.tsv": "We\t0\t300\t1.0\tfalse\nherd.\t320\t800\t0.42\ttrue\n",
                "choices.tsv": "3\t7\theard\n",
                "context.tsv": "",
            }.items():
                (folder / name).write_text(text)
            note = dict(id="test", split="dev", language="en", source="synthetic",
                        draft=str(folder / "asr.txt"), words=str(folder / "words.tsv"),
                        reference=str(folder / "ref.txt"), reference_sha256=hashlib.sha256(b"We heard.").hexdigest(),
                        spans=[dict(begin=3, end=7, first=1, last=1, owners=["w"])])
            candidates = {("w", 320, 800, "herd"): [(.3, .72, "heard", True)]}
            result = experiment.score(note, candidates, {"w"}, {})
            self.assertEqual(result["policies"]["strict"]["helpful"], 1)
            self.assertEqual(result["policies"]["strict_new"]["offered"], 0)
            self.assertEqual(experiment.score(note, candidates, set(), {})["matched"], 0)


if __name__ == "__main__":
    unittest.main()

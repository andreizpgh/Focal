"""Regression checks for timing, multilingual scoring and repeated dictation anchors."""
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "context-review"))
import prepare
import score


class WiderContextTest(unittest.TestCase):
    def test_crop_preserves_original_at_edges_and_cap(self):
        self.assertEqual(prepare.crop(0, 8000, 16000), (0, 14000))
        self.assertEqual(prepare.crop(7000, 15000, 16000), (1000, 16000))
        self.assertEqual(prepare.crop(18000, 44000, 60000), (16000, 46000))
        self.assertIsNone(prepare.crop(0, 8000, 8999))
        self.assertEqual(prepare.crop(0, 8000, 9000), (0, 9000))
        self.assertIsNone(prepare.crop(11000, 42000, 60000))

    def test_anchor_rejects_matching_word_at_wrong_time(self):
        timed = [("Снова", 0, 400), ("бок", 500, 930), ("здесь", 1000, 1500)]
        crop = [("снова", 0, 450, .99, False), ("бог,", 500, 930, .70, False)]
        self.assertEqual(score.extract(timed, 1, 1, crop, "бок")["anchored"], "бог")
        crop[0] = ("снова", 990, 1200, .99, False)
        self.assertEqual(set(score.extract(timed, 1, 1, crop, "бок")), {"timing", "unfiltered"})
        crop[1] = ("бог", 500, 930, .49, False)
        self.assertEqual(set(score.extract(timed, 1, 1, crop, "бок")), {"unfiltered"})
        crop[1] = ("бог", 500, 930, .99, True)
        self.assertFalse(score.extract(timed, 1, 1, crop, "бок"))

    def test_decomposed_marks_case_and_source_punctuation(self):
        self.assertEqual(score.edge_trim("«sā!»"), "sā")
        self.assertFalse(score.extract([("Sā!", 100, 460)], 0, 0, [("sā.", 90, 450, .99, False)], "Sā"))

    def test_phrase_time_coverage(self):
        timed = [("at", 0, 300), ("some", 400, 750), ("where", 750, 1100)]
        crop = [("at", 0, 300, .95, False), ("somewhere.", 410, 1090, .91, False)]
        self.assertEqual(score.extract(timed, 1, 2, crop, "some where")["anchored"], "somewhere")
        crop[1] = ("somewhere.", 550, 850, .91, False)
        self.assertFalse(score.extract(timed, 1, 2, crop, "some where"))

    def test_repeated_reference_requires_unique_local_alignment(self):
        text = "в этот день пришёл наш старый друг".split()
        self.assertEqual(score.local_reference(text, text[:3], text[-3:], 1), ["пришёл"])
        self.assertIsNone(score.local_reference(text * 2, text[:3], text[-3:], 1))
        self.assertIsNone(score.local_reference(text, text[:2], text[-3:], 1))

    def test_apply_one_utf16_span_and_compare_to_existing_menu(self):
        with TemporaryDirectory() as d:
            p = Path(d)
            (p / "asr.txt").write_text("🎧 It iz blue.")
            (p / "reference.txt").write_text("It is blue.")
            (p / "words.tsv").write_text("🎧\t0\t300\tnull\tfalse\nIt\t300\t500\t1.0\tfalse\n"
                                         "iz\t550\t850\t0.4\ttrue\nblue.\t900\t1200\t1.0\tfalse\n")
            (p / "choices.tsv").write_text("6\t8\tis\n")
            (p / "context.tsv").write_text("")
            original = {k: str(p / f) for k, f in
                        (("draft", "asr.txt"), ("reference", "reference.txt"), ("words", "words.tsv"))}
            note = dict(id="fixture", language="en", split="holdout", source="synthetic",
                        spans=[dict(first=2, last=2, begin=6, end=8, key="w")])
            crops = {"w": [("It", 300, 500, .95, False), ("is", 550, 850, .95, False)]}
            result = score.score_note(note, original, crops)
            self.assertEqual(result["policies"]["stored"]["helpful"], 1)
            self.assertEqual(result["policies"]["anchored"]["helpful"], 1)
            self.assertEqual(result["policies"]["anchored_new"]["offered"], 0)
            self.assertEqual(json.loads(json.dumps(result))["scored_flags"], 1)


if __name__ == "__main__":
    unittest.main()

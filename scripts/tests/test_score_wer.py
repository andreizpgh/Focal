"""Tests for scripts/score-wer.py on synthetic references and the committed FLEURS reference list.

Run: python3 -m unittest discover -s scripts/tests
"""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "score-wer.py"
_spec = importlib.util.spec_from_file_location("score_wer", SCRIPT)
score_wer = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(score_wer)


def clip(clip_id, language, text, split="dev"):
    return {"id": clip_id, "split": split, "language": language, "text": text,
            "reference_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest()}


class ScoringTest(unittest.TestCase):
    def test_normalisation_ignores_case_punctuation_and_composition(self):
        self.assertEqual(score_wer.words("Hello, WORLD! It's 15:30."), ["hello", "world", "it", "s", "15", "30"])
        self.assertEqual(score_wer.words("Café"), score_wer.words("Café"))
        self.assertEqual(score_wer.words("Ёлка", fold_yo=True), ["елка"])
        self.assertEqual(score_wer.words("Ёлка"), ["ёлка"])

    def test_counts_substitutions_insertions_and_deletions(self):
        counts = score_wer.score("the cat sat on the mat", "the cat sit on mat today")
        self.assertEqual(counts["words"], 6)
        self.assertEqual(counts["word_errors"], 3)  # sat->sit, the deleted, today inserted
        self.assertEqual(counts["characters"], len("thecatsatonthemat"))
        self.assertEqual(counts["character_errors"], score_wer.distance("thecatsatonthemat", "thecatsitonmattoday"))

    def test_distance_is_symmetric_and_handles_empty_sequences(self):
        self.assertEqual(score_wer.distance([], ["a", "b"]), 2)
        self.assertEqual(score_wer.distance(["a", "b"], []), 2)
        self.assertEqual(score_wer.distance("kitten", "sitting"), 3)
        self.assertEqual(score_wer.distance("sitting", "kitten"), 3)

    def test_totals_per_language(self):
        clips = [clip("a", "en", "one two three"), clip("b", "ru", "раз два"), clip("c", "en", "four")]
        result = score_wer.evaluate(clips, {"a": "one too three", "b": "раз два", "c": "four five"})
        self.assertEqual(result["overall"]["words"], 6)
        self.assertEqual(result["overall"]["word_errors"], 2)
        self.assertEqual(result["languages"]["en"]["clips"], 2)
        self.assertEqual(result["languages"]["ru"]["word_errors"], 0)


class CommandLineTest(unittest.TestCase):
    def run_main(self, references, hypotheses, *extra):
        with tempfile.TemporaryDirectory() as directory:
            ref_path = Path(directory) / "references.json"
            hyp_path = Path(directory) / "hypotheses.json"
            ref_path.write_text(json.dumps({"clips": references}), encoding="utf-8")
            hyp_path.write_text(json.dumps(hypotheses), encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                status = score_wer.main([str(hyp_path), "--references", str(ref_path), *extra])
            return status, out.getvalue(), err.getvalue()

    def test_rejects_an_edited_reference(self):
        edited = clip("a", "en", "one two")
        edited["text"] = "one too"
        status, _, err = self.run_main([edited], {"a": "one two"})
        self.assertEqual(status, 2)
        self.assertIn("SHA-256", err)

    def test_missing_and_unknown_hypotheses(self):
        references = [clip("a", "en", "one"), clip("b", "en", "two")]
        self.assertEqual(self.run_main(references, {"a": "one"})[0], 2)
        status, out, _ = self.run_main(references, {"a": "one"}, "--allow-missing", "--json")
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(out)["overall"]["clips"], 1)
        self.assertEqual(self.run_main(references, {"a": "one", "b": "two", "z": "x"})[0], 2)

    def test_split_filter(self):
        references = [clip("a", "en", "one", "dev"), clip("b", "en", "two", "test")]
        status, out, _ = self.run_main(references, {"a": "one"}, "--split", "dev", "--json")
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(out)["overall"]["words"], 1)


class PublishedReferencesTest(unittest.TestCase):
    def test_reference_list_matches_the_published_word_counts(self):
        clips = score_wer.load_references(score_wer.REFERENCES)
        self.assertEqual(len(clips), 84)
        totals = {}
        for split in ("dev", "test"):
            result = score_wer.evaluate([c for c in clips if c["split"] == split],
                                        {c["id"]: c["text"] for c in clips})
            totals[split] = (result["overall"]["words"], result["overall"]["characters"],
                             result["overall"]["word_errors"])
        # docs/speech-quality.md: DEV 864 words / 4,349 characters, held-out 961 / 5,010.
        self.assertEqual(totals["dev"], (864, 4349, 0))
        self.assertEqual(totals["test"], (961, 5010, 0))


if __name__ == "__main__":
    unittest.main()

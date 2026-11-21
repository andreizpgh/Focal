import importlib.util
from pathlib import Path
import sys
import unittest

path = Path(__file__).resolve().parents[1] / "punctuation-bench" / "evaluate.py"
spec = importlib.util.spec_from_file_location("punctuation_bench", path)
bench = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bench
spec.loader.exec_module(bench)


class PunctuationBenchTest(unittest.TestCase):
    def test_fill_only_preserves_original_marks_case_numbers_and_paragraphs(self):
        text = 'Да, в 12:30 всё-таки было 3,14.\n\nОн спросил «когда?»'
        words = bench.sites(text)
        predictions = [(",", .99) if s.word in {"Да", "12:30", "3,14", "когда"} else ("", 1) for s in words]
        self.assertEqual(bench.insertions(text, words, predictions), text.replace("12:30 ", "12:30, "))
        self.assertEqual([s.word for s in words if any(ch.isdigit() for ch in s.word)], ["12:30", "3,14"])

    def test_whole_word_windows_predict_every_first_piece_once(self):
        class Tokenizer:
            def token_to_id(self, token):
                return {"[CLS]": 2, "[SEP]": 3, "[UNK]": 1}[token]

            def encode(self, word, add_special_tokens):
                n = int(word)
                return type("Encoding", (), {"ids": [n + 10] * (n % 9 + 1)})()

        words = bench.sites(" ".join(str(i) for i in range(200)))
        groups = list(bench.windows(words, Tokenizer()))
        self.assertEqual([ids[p] for ids, positions in groups for p in positions], list(range(10, 210)))
        self.assertTrue(all(len(ids) <= 130 for ids, _ in groups))

    def test_counts_fixes_new_errors_and_wrong_types_independently(self):
        baseline = ["", "", ".", ","]
        result = [",", ",", "?", ","]
        metrics = bench.score(result, baseline, {0: ",", 1: "", 2: "!", 3: ","})
        self.assertEqual([metrics[k] for k in ("fixed", "introduced", "still_wrong", "review_sites")], [1, 1, 1, 2])
        self.assertEqual(metrics["sentence_tp"], 1)

    def test_repeated_dictation_scores_only_unique_printed_six_word_contexts(self):
        ref = bench.sites("Раз два три, четыре пять шесть.")
        draft = bench.sites("Раз два три четыре пять шесть. Три четыре. Раз два три четыре пять шесть.")
        gold, count = bench.reference_sites(draft, ref, repeated=True)
        self.assertEqual(gold, {2: ",", 10: ","})
        self.assertIsNone(count)
        twice = bench.sites("Раз два три, четыре пять шесть. Раз два три четыре пять шесть.")
        self.assertEqual(bench.reference_sites(draft, twice, repeated=True)[0], {})

from pathlib import Path
import runpy
import tempfile
import unittest

bench = runpy.run_path(str(Path(__file__).resolve().parents[1] / "punctuation-bench/multilingual.py"))
choices = runpy.run_path(str(Path(__file__).resolve().parents[1] / "punctuation-bench/score_choices.py"))
overlap = runpy.run_path(str(Path(__file__).resolve().parents[1] / "punctuation-bench/overlap_choices.py"))


class MultilingualBenchTest(unittest.TestCase):
    def test_greek_question_marks_and_spanish_openers(self):
        mark = bench["mark"]
        self.assertEqual(mark(";» ", True), "?")
        self.assertEqual(mark(";", True), "?")
        self.assertEqual(mark("; ", False), ";")
        self.assertEqual(mark("! ¿", False), "!")
        self.assertIsNone(mark(" (...) — ", False))

    def test_only_adjacent_exact_words_contribute_marks_and_paragraphs(self):
        with tempfile.TemporaryDirectory() as work:
            d = Path(work)
            (d/"ref").write_text("Uno dos. ¿Tres cuatro?")
            (d/"draft").write_text("Uno\n\ndos. Cinco cuatro?")
            (d/"words").write_text("Uno\t0\t100\t0.3\ttrue\ndos.\t200\t300\t1\tfalse\nCinco\t600\t700\t0.2\ttrue\ncuatro?\t700\t800\t1\tfalse\n")
            note = bench["read_note"]({"reference":d/"ref","draft":d/"draft","words":d/"words","language":"es","source":"FLEURS"})
            self.assertEqual(note.gold, {0:""})
            self.assertEqual(note.counts["word_errors"], 1)
            self.assertEqual(note.counts["flagged_matching_words"], 1)
            self.assertEqual(note.counts["paragraph_breaks_inside_reference_sentence"], 1)

    def test_network_predictions_use_last_owned_subtoken_with_context(self):
        class ExampleTokenizer:
            def encode(self, text, add_special_tokens):
                words = text.split(" ")
                offsets = []
                idx = 0
                for word in words:
                    offsets.extend([(idx,idx+1), (idx+1,idx+len(word))])
                    idx += 1+len(word)
                return type("Encoding",(),{"ids":list(range(10,10+len(offsets))),"offsets":offsets})()

            def token_to_id(self, piece):
                return {"<bos>":2,"<eos>":1}[piece]

        groups = list(bench["owned_windows"](["ab"]*120,ExampleTokenizer()))
        self.assertEqual([seq[j] for seq,targets in groups for _,j in targets],list(range(11,250,2)))
        self.assertEqual([i for _,targets in groups for i,_ in targets],list(range(120)))

    def test_spelling_menu_scores_each_change_and_uses_compose_offsets(self):
        counts = choices["score_note"]("😀 Harbur road.", "Harbour road.", "3\t9\tHarbour\tHarper\n10\t14\troam")
        self.assertEqual(counts["flagged_spans"], 2)
        self.assertEqual([counts[k] for k in ("helpful", "neutral", "harmful")], [1, 1, 1])
        self.assertEqual(counts["spans_with_helpful_choice"], 1)

    def test_reader_copy_preserves_words_and_displays_a_greek_question(self):
        note = type("Note", (), {"signs": ["", ""], "gaps": ["\n\n", ""], "meta": {"language": "el"}})()
        self.assertEqual(bench["proposed_text"]("Πάμε\n\nΤώρα", note, ["?", ""]), "Πάμε;\n\nΤώρα")

    def test_reader_copy_keeps_original_combining_characters_next_to_an_insertion(self):
        note = type("Note", (), {"signs": ["", ""], "gaps": [" ", ""], "meta": {"language": "fr"}})()
        self.assertEqual(bench["proposed_text"]("cafe\u0301 ici", note, [",", ""]), "cafe\u0301, ici")

    def test_audio_suggestion_needs_another_windows_context_and_reliable_matching_time(self):
        words = [(10, 95, True, "«cafe\u0301»")]
        suggest = overlap["eligible_readings"]
        self.assertEqual(list(suggest([(0, 200, None, 50, words)], 0, 100)), ["cafe\u0301"])
        self.assertEqual(list(suggest([(0, 200, 0, 200, words)], 0, 100)), [])
        self.assertEqual(list(suggest([(0, 200, None, 50, [(40, 55, True, "short")])], 0, 100)), [])
        self.assertEqual(list(suggest([(0, 200, None, 50, [(10, 95, False, "unclear")])], 0, 100)), [])


if __name__ == "__main__":
    unittest.main()

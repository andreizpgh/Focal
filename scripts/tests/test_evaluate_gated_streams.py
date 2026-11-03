from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import wave

SCRIPT = Path(__file__).resolve().parents[1] / "evaluate-gated-streams.py"
SPEC = importlib.util.spec_from_file_location("evaluate_gated", SCRIPT)
E4 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E4)


class GatedStreamRunnerTest(unittest.TestCase):
    def test_decoder_slice_uses_stored_millisecond_boundaries_without_padding(self):
        with tempfile.TemporaryDirectory() as directory:
            pcm, output = Path(directory) / "source.pcm", Path(directory) / "slice.wav"
            pcm.write_bytes(b"".join(struct.pack("<h", sample) for sample in range(80)))
            self.assertEqual((1, 3), E4.extract_wave(pcm, dict(start_sample=17, end_sample=63), output))
            with wave.open(str(output), "rb") as wav:
                self.assertEqual(32, wav.getnframes())
                self.assertEqual(pcm.read_bytes()[32:96], wav.readframes(32))
            with self.assertRaises(ValueError):
                E4.extract_wave(pcm, dict(start_sample=0, end_sample=31 * 16000), output)

    def test_bypass_ranges_and_previous_passage_ties_preserve_framing(self):
        case = dict(total_samples=40 * 16000, clips=[], ranges=[])
        self.assertEqual([dict(start_sample=0, end_sample=30 * 16000, overlap=False),
                          dict(start_sample=29500 * 16, end_sample=40 * 16000, overlap=True)], E4.ranges_for(case, "bypass"))
        passages = [dict(start=index // 2, text=str(index)) for index in range(10)]
        self.assertEqual([str(index) for index in range(2, 8)], [row["text"] for row in E4.recent_passages(passages, 4)])

    def test_native_padding_filter_and_decimal_confidence_are_forwarded(self):
        valid = dict(offsets={"from": -10, "to": 80}, text="  Привет  ", noSpeech=.1, probabilities=[.35])
        padded = dict(offsets={"from": 100, "to": 200}, text="padding", noSpeech=.1, probabilities=[.9])
        document = dict(probe=dict(schema=1, load_seconds=0, decode_seconds=0, peak_rss_bytes=None), transcription=[valid, padded])
        lexical = json.loads(json.dumps(document), parse_float=Decimal)
        calls = []
        class Replay:
            def review(self, segment):
                calls.append(segment)
                return False
        segments = E4.decoded_segments(document, lexical, 5000, 5100, Replay())
        self.assertEqual([dict(start=5000, end=5080, text="  Привет  ", uncertain=False)], segments)
        self.assertEqual(1, len(calls))
        self.assertIsInstance(calls[0]["probabilities"][0], Decimal)
        self.assertEqual("0.35", str(calls[0]["probabilities"][0]))
        # Trimming intentionally belongs to the real Kotlin reconciliation stage.
        transported = "startMs\tendMs\tuncertain\ttextBase64\n10\t20\ttrue\t\n"
        self.assertEqual([dict(start=10, end=20, uncertain=True, text="")], E4.read_segments(transported))

    def case(self, root):
        pcm = root / "en-normal.pcm"
        pcm.write_bytes(bytes(32000))
        tsv = root / "en-normal.ranges.tsv"
        tsv.write_text("startSample\tendSample\toverlap\n0\t16000\tfalse\n")
        clip = dict(id="clip", split="dev", start_sample=0, end_sample=16000, source_frames=16000, audio_sha256="a" * 64)
        case = dict(name="en-normal", language="en", scenario="normal", gain=1., total_samples=16000,
                    pcm=pcm.name, pcm_sha256=E4.digest_file(pcm), ranges_tsv=tsv.name, ranges_sha256=E4.digest_file(tsv),
                    clips=[clip], ranges=[dict(start_sample=0, end_sample=16000, overlap=False)])
        references = dict(clip=dict(id="clip", split="dev", language="en", text="Synthetic source"))
        verified = dict(clip=dict(seconds=1., audio_sha256="a" * 64))
        return case, references, verified

    def test_canonical_names_split_binding_and_hashed_range_equality(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case, references, verified = self.case(root)
            self.assertEqual([case], E4.validate_cases(dict(selected_split="dev", cases=[case]), root, references, verified))
            for name in ("../../escape", "/tmp/escape", "ru-normal"):
                with self.assertRaisesRegex(ValueError, "names"):
                    E4.validate_cases(dict(selected_split="dev", cases=[dict(case, name=name)]), root, references, verified)
            with self.assertRaisesRegex(ValueError, "source split"):
                E4.validate_cases(dict(selected_split="dev", cases=[case]), root, dict(clip=dict(references["clip"], split="test")), verified)
            altered = dict(case, ranges=[dict(start_sample=16, end_sample=16000, overlap=False)])
            with self.assertRaisesRegex(ValueError, "disagree"):
                E4.validate_cases(dict(selected_split="dev", cases=[altered]), root, references, verified)

    def test_held_out_split_requires_matching_request_source_and_clip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case, references, verified = self.case(root)
            references["clip"]["split"] = "test"
            case["clips"][0]["split"] = "test"
            experiment = dict(selected_split="test", cases=[case])
            self.assertEqual("test", E4.selected_split(experiment, "test"))
            self.assertEqual([case], E4.validate_cases(experiment, root, references, verified))
            with self.assertRaisesRegex(ValueError, "differs"):
                E4.selected_split(experiment, "dev")
            with self.assertRaisesRegex(ValueError, "source split"):
                E4.validate_cases(dict(experiment, selected_split="dev"), root, references, verified)
            case["clips"][0]["split"] = "dev"
            with self.assertRaisesRegex(ValueError, "source split"):
                E4.validate_cases(experiment, root, references, verified)
            for declaration in ({}, dict(selected_split="all")):
                with self.assertRaisesRegex(ValueError, "explicit"):
                    E4.selected_split(declaration)

    def test_replay_attestation_rejects_historical_or_mutated_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / "replay-classes"
            classes.mkdir()
            compiled = classes / "Synthetic.class"
            compiled.write_bytes(b"synthetic compiled fixture")
            stdlib = root / "stdlib.jar"
            stdlib.write_bytes(b"synthetic runtime fixture")
            build = dict(sources={name: E4.digest_file(E4.REPOSITORY / name) for name in E4.REPLAY_SOURCES},
                         compiler_version="info: kotlinc-jvm 2.1.20", stdlib_sha256=E4.digest_file(stdlib),
                         classes={compiled.name: E4.digest_file(compiled)})
            experiment = dict(implementation=dict(script_sha256=E4.PREPARE.source_sha256,
                replay_compiler_sha256=E4.REPLAY_HELPER_SHA256, fresh_replay_build=build))
            E4.verify_replay(experiment, root, stdlib)
            with self.assertRaisesRegex(ValueError, "Historical"):
                E4.verify_replay(dict(experiment, provenance_status="Historical"), root, stdlib)
            compiled.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "no longer"):
                E4.verify_replay(experiment, root, stdlib)

    def test_self_reported_pcm_hash_cannot_replace_declared_source_audio(self):
        config = dict(sample_rate=16000, leading_samples=32000, gap_samples=19200, trailing_samples=32000,
                      gains=E4.PREPARE.GAINS, synthetic_seconds=30, synthetic_seed=0xF0CA1, synthetic_dc=6000)
        case = dict(name="en-normal", language="en", scenario="normal", clips=[dict(id="source")],
                    total_samples=16000, pcm_sha256="0" * 64)
        def original_pcm(path, *args):
            path.write_bytes(bytes(32000))
            return [dict(id="source")], [], 16000
        with patch.object(E4.PREPARE, "select_sources", return_value=dict(en=[dict(id="source")])) as select:
            with patch.object(E4.PREPARE, "write_stream", side_effect=original_pcm):
                with self.assertRaisesRegex(ValueError, "does not derive"):
                    E4.verify_source_derivation(dict(selected_split="test", configuration=config), [case], Path("unused-manifest.json"))
                select.assert_called_once_with(Path("unused-manifest.json"), 1, "test")
                duplicate = dict(case, clips=[dict(id="source"), dict(id="source")])
                with self.assertRaisesRegex(ValueError, "deterministic"):
                    E4.verify_source_derivation(dict(selected_split="test", configuration=config), [duplicate], Path("unused-manifest.json"))

    def test_control_only_plan_excludes_speech_and_missing_requests_fail(self):
        controls = [dict(name=f"synthetic-{kind}", total_samples=30 * 16000, clips=[], ranges=[]) for kind in ("zero", "dc", "noise")]
        cases = [dict(name="en-normal")] + controls
        chosen = E4.choose_cases(cases, ["en"], ["normal"], ["zero", "dc", "noise"], controls_only=True)
        self.assertEqual(3, len(chosen))
        self.assertEqual(3, sum(len(E4.ranges_for(case, "bypass")) for case in chosen))
        with self.assertRaisesRegex(ValueError, "Every requested"):
            E4.choose_cases(cases, ["en", "ru"], ["normal"], [])

    def test_changed_source_or_unknown_native_dependencies_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.wav"
            source.write_bytes(b"original source")
            verified = dict(source=dict(path=source, audio_sha256=E4.digest_file(source)))
            E4.verify_original_sources(verified)
            source.write_bytes(b"mutated source")
            with self.assertRaisesRegex(ValueError, "changed"):
                E4.verify_original_sources(verified)
        with patch.object(E4.EVALUATE, "engine_provenance", return_value=dict(dependency_hash_status="unavailable")):
            with self.assertRaisesRegex(ValueError, "library hashes"):
                E4.checked_engine(Path("unused-probe"))


if __name__ == "__main__":
    unittest.main()

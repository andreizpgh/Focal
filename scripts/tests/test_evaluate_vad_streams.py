import copy
from contextlib import ExitStack
from decimal import Decimal
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import random
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import wave

SCRIPT = Path(__file__).resolve().parents[1] / "evaluate-vad-streams.py"
SPEC = importlib.util.spec_from_file_location("evaluate_vad", SCRIPT)
VAD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VAD)


class VadStreamEvaluationTest(unittest.TestCase):
    def document(self, total=16000, pcm_hash="a" * 64):
        windows = (total + 511) // 512
        return dict(schema=1, model="silero-16k-5.1.2", model_sha256=VAD.VAD_MODEL["sha256"],
                    pcm_sha256=pcm_hash, sample_rate=16000, input_samples=total,
                    window_samples=512, probability_windows=windows, padded_samples=windows * 512,
                    settings=dict(VAD.VAD_SETTINGS), probabilities=[0.] * windows, ranges=[],
                    padding_only_ranges_discarded=0, load_seconds=0., detect_seconds=0.,
                    segment_seconds=0., peak_rss_bytes=None)

    def case(self, total=16000, pcm_hash="a" * 64):
        return dict(name="synthetic-noise", pcm="input.pcm", total_samples=total, pcm_sha256=pcm_hash,
                    language=None, scenario="noise", clips=[])

    def test_long_native_range_is_bounded_with_exact_overlap_and_tail(self):
        native = [dict(start_sample=0, end_sample=40 * 16000)]
        ranges = VAD.bounded_ranges(native, 40 * 16000)
        self.assertEqual([(0, 256000), (248000, 504000), (496000, 640000)],
                         [(r["start_sample"], r["end_sample"]) for r in ranges])
        self.assertEqual([False, True, True], [r["overlap"] for r in ranges])
        self.assertTrue(all(r["end_sample"] - r["start_sample"] <= 16 * 16000 for r in ranges))
        self.assertTrue(all(r["vad_segment_indices"] == [0] for r in ranges))
        self.assertEqual(2 * 8000, sum(r["end_sample"] - r["start_sample"] for r in ranges) - 640000)

    def test_touching_native_ranges_merge_but_real_gap_resets_overlap(self):
        native = [dict(start_sample=0, end_sample=240000), dict(start_sample=240000, end_sample=480000),
                  dict(start_sample=500000, end_sample=510000)]
        ranges = VAD.bounded_ranges(native, 520000)
        self.assertEqual([(0, 256000), (248000, 480000), (500000, 510000)],
                         [(r["start_sample"], r["end_sample"]) for r in ranges])
        self.assertEqual([[0, 1], [1], [2]], [r["vad_segment_indices"] for r in ranges])
        self.assertEqual([False, True, False], [r["overlap"] for r in ranges])

    def test_unordered_overlapping_out_of_source_and_submillisecond_ranges_fail(self):
        invalid = [
            [dict(start_sample=0, end_sample=1000), dict(start_sample=900, end_sample=1200)],
            [dict(start_sample=-1, end_sample=1000)], [dict(start_sample=0, end_sample=16001)],
            [dict(start_sample=0, end_sample=0)], [dict(start_sample=True, end_sample=1000)],
            [dict(start_sample=0, end_sample=15)],
        ]
        for ranges in invalid:
            with self.subTest(ranges=ranges), self.assertRaises(ValueError):
                VAD.bounded_ranges(ranges, 16000)
        for total in (0, True, 400 * 16000 + 1):
            with self.assertRaises(ValueError):
                VAD.bounded_ranges([], total)

    def test_model_pcm_sample_and_settings_identity_fail_closed(self):
        document, case = self.document(), self.case()
        self.assertEqual([], VAD.validate_vad_output(document, case, VAD.VAD_MODEL["sha256"]))
        for key, value in (("model_sha256", "b" * 64), ("pcm_sha256", "b" * 64),
                           ("input_samples", 15999), ("sample_rate", 8000), ("schema", True),
                           ("padded_samples", 16000), ("probability_windows", 31)):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "identity"):
                VAD.validate_vad_output(dict(document, **{key: value}), case, VAD.VAD_MODEL["sha256"])
        for setting, value in (("threshold", 0.6), ("use_gpu", True), ("max_speech_seconds", 16), ("threads", True)):
            modified = copy.deepcopy(document)
            modified["settings"][setting] = value
            with self.subTest(setting=setting), self.assertRaisesRegex(ValueError, "settings"):
                VAD.validate_vad_output(modified, case, VAD.VAD_MODEL["sha256"])

    def test_probabilities_timings_and_native_sample_mapping_are_checked(self):
        document, case = self.document(), self.case()
        for changes in (dict(probabilities=[0.] * 31), dict(probabilities=[float("nan")] * 32),
                        dict(probabilities=[True] * 32), dict(load_seconds=-1),
                        dict(peak_rss_bytes=-1), dict(padding_only_ranges_discarded=33)):
            with self.subTest(changes=list(changes)), self.assertRaises(ValueError):
                VAD.validate_vad_output(dict(document, **changes), case, VAD.VAD_MODEL["sha256"])
        row = dict(native_start_cs=0, native_end_cs=100, start_sample=0, end_sample=16000)
        self.assertEqual(1, len(VAD.validate_vad_output(dict(document, ranges=[row]), case, VAD.VAD_MODEL["sha256"])))
        for bad in (dict(row, start_sample=16), dict(row, native_end_cs=104), dict(row, native_start_cs=.5)):
            with self.assertRaises(ValueError):
                VAD.validate_vad_output(dict(document, ranges=[bad]), case, VAD.VAD_MODEL["sha256"])

    def test_native_range_over_max_speech_is_split_and_final_padding_is_clipped(self):
        document, case = self.document(640000), self.case(640000)
        document["ranges"] = [dict(native_start_cs=0, native_end_cs=4000, start_sample=0, end_sample=640000)]
        self.assertEqual(3, len(VAD.validate_vad_output(document, case, VAD.VAD_MODEL["sha256"])))
        document, case = self.document(16017), self.case(16017)
        document["ranges"] = [dict(native_start_cs=0, native_end_cs=103, start_sample=0, end_sample=16017)]
        self.assertEqual(16017, VAD.validate_vad_output(document, case, VAD.VAD_MODEL["sha256"])[0]["end_sample"])

    def test_test_split_requires_exact_frozen_policy(self):
        policy = dict(vad=VAD.VAD_SETTINGS, native_model_sha256=VAD.VAD_MODEL["sha256"],
                      vad_conditioning=VAD.conditioning_policy("none"))
        with self.assertRaisesRegex(ValueError, "before reading"):
            VAD.validate_frozen_policy(None, policy, "test")
        self.assertIsNone(VAD.validate_frozen_policy(None, policy, "dev"))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(json.dumps(dict(policy=policy)))
            self.assertEqual(VAD.digest_file(path), VAD.validate_frozen_policy(path, policy, "test"))
            with self.assertRaisesRegex(ValueError, "differs"):
                VAD.validate_frozen_policy(path, dict(policy, contexts=["on"]), "test")
            with self.assertRaisesRegex(ValueError, "differs"):
                VAD.validate_frozen_policy(path, dict(policy,
                    vad_conditioning=VAD.conditioning_policy("causal-rms-cap30")), "test")
        with self.assertRaisesRegex(ValueError, "explicit"):
            VAD.validate_frozen_policy(None, policy, "all")

    def test_cli_rejects_unfrozen_test_before_loading_source_inputs(self):
        arguments = ["evaluate-vad-streams.py", "--split", "test", "--experiment", "unused",
                     "--source-manifest", "unused", "--kotlin-stdlib", "unused",
                     "--vad-model", "unused", "--vad-model-provenance", "unused", "--output", "unused"]
        with patch.object(sys, "argv", arguments), patch.object(VAD, "read_json_identity") as read:
            with patch("sys.stderr"), self.assertRaises(SystemExit):
                VAD.main()
            read.assert_not_called()

    def test_cli_rejects_changed_conditioner_before_reading_source_manifest(self):
        arguments = ["evaluate-vad-streams.py", "--split", "test", "--experiment", "experiment.json",
                     "--source-manifest", "unread-source.json", "--kotlin-stdlib", "unused",
                     "--vad-model", "unused", "--vad-model-provenance", "unused", "--output", "unused",
                     "--frozen-plan", "frozen.json", "--vad-conditioning", "causal-rms-cap30"]
        original_validate, current_policy = VAD.validate_frozen_policy, {}
        def capture_policy(path, policy, split):
            current_policy.update(policy)
            return original_validate(path, policy, split)
        def read_input(path, limit):
            if path == Path("experiment.json"):
                return dict(implementation=dict(fresh_replay_build={})), "a" * 64
            if path == Path("frozen.json"):
                return dict(policy=dict(current_policy, vad_conditioning=VAD.conditioning_policy("none"))), "b" * 64
            self.fail("Source manifest must not be read under a changed held-out policy")
        patches = [patch.object(sys, "argv", arguments), patch.object(VAD.os, "umask"),
                   patch.object(VAD, "read_json_identity", side_effect=read_input),
                   patch.object(VAD, "validate_frozen_policy", side_effect=capture_policy),
                   patch.object(VAD, "digest_file", return_value=VAD.ASR_MODEL_SHA256),
                   patch.object(VAD.E4.PREPARE, "require_private_output", return_value=Path("unused")),
                   patch.object(VAD.E4, "selected_split"), patch.object(VAD.E4, "verify_replay"),
                   patch.object(VAD.shutil, "which", return_value="java"),
                   patch.object(VAD, "verify_vad_model", return_value=VAD.VAD_MODEL),
                   patch.object(VAD, "checked_engine", return_value=dict(shared_libraries=[])),
                   patch.object(Path, "is_file", return_value=True),
                   patch.object(Path, "stat", return_value=SimpleNamespace(st_size=59707625))]
        with ExitStack() as stack:
            for item in patches:
                stack.enter_context(item)
            with self.assertRaisesRegex(ValueError, "differs"):
                VAD.main()
        self.assertEqual("causal-rms-cap30", current_policy["vad_conditioning"]["mode"])

    def test_json_identity_stays_bound_to_parsed_bytes_during_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.json"
            original = b'{"reference":"synthetic original"}'
            changed = b'{"reference":"synthetic replacement"}'
            path.write_bytes(original)
            loads = json.loads
            def mutate_while_parsing(payload, **kwargs):
                path.write_bytes(changed)
                return loads(payload, **kwargs)
            with patch.object(VAD.json, "loads", side_effect=mutate_while_parsing):
                document, expected = VAD.read_json_identity(path, 1024)
            self.assertEqual("synthetic original", document["reference"])
            self.assertEqual(hashlib.sha256(original).hexdigest(), expected)
            self.assertNotEqual(VAD.digest_file(path), expected)
            with patch.object(VAD, "checked_engine") as engine, self.assertRaisesRegex(ValueError, "identity changed"):
                VAD.check_inputs(SimpleNamespace(), {path: expected}, {})
            engine.assert_not_called()

    def test_input_snapshot_uses_verified_pins_not_later_mutated_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(**{key: root / key for key in
                ("experiment", "source_manifest", "model", "vad_model", "vad_model_provenance", "java", "frozen_plan")})
            for path in vars(args).values():
                path.write_bytes(b"changed after preflight")
            policy = dict(helper_hashes={}, vad_model=dict(provenance_sha256="c" * 64), java_sha256="d" * 64)
            files = VAD.input_identities(args, policy, "a" * 64, "b" * 64, "e" * 64)
            self.assertEqual(VAD.ASR_MODEL_SHA256, files[args.model])
            self.assertEqual(VAD.VAD_MODEL["sha256"], files[args.vad_model])
            self.assertEqual("a" * 64, files[args.experiment])
            self.assertEqual("b" * 64, files[args.source_manifest])
            self.assertEqual("c" * 64, files[args.vad_model_provenance])
            self.assertEqual("d" * 64, files[args.java])
            self.assertEqual("e" * 64, files[args.frozen_plan])
            with patch.object(VAD, "checked_engine") as engine, self.assertRaisesRegex(ValueError, "identity changed"):
                VAD.check_inputs(args, files, {})
            engine.assert_not_called()

    def test_asr_float_and_decimal_parses_must_match_one_bound_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "chunk.json"
            payload = b'{"confidence":0.3500000000000000001,"text":"synthetic"}'
            document, lexical = json.loads(payload), json.loads(payload, parse_float=Decimal)
            path.write_bytes(payload)
            self.assertEqual(hashlib.sha256(payload).hexdigest(), VAD.bind_asr_output(path, document, lexical))
            # This changed lexeme rounds to the same Python float; lexical checking still rejects it.
            path.write_bytes(b'{"confidence":0.35,"text":"synthetic"}')
            self.assertEqual(document, json.loads(path.read_bytes()))
            with self.assertRaisesRegex(ValueError, "changed after parsing"):
                VAD.bind_asr_output(path, document, lexical)

    def test_plan_hash_binds_written_bytes_and_preserves_an_existing_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            plan = dict(policy=dict(threshold=.5))
            expected = VAD.save_bound_plan(path, plan)
            self.assertEqual(VAD.digest_file(path), expected)
            with self.assertRaises(FileExistsError):
                VAD.save_bound_plan(path, dict(policy=dict(threshold=.6)))
            self.assertEqual(plan, json.loads(path.read_text()))

    def test_empty_native_controls_never_invoke_asr_or_kotlin_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rng = random.Random(0xF0CA1)
            controls = dict(zero=bytes(32000), dc=struct.pack("<h", 6000) * 16000,
                            noise=b"".join(struct.pack("<h", rng.randint(-64, 64)) for _ in range(16000)))
            for kind, pcm in controls.items():
                case = dict(self.case(pcm_hash=hashlib.sha256(pcm).hexdigest()),
                            name=f"synthetic-{kind}", scenario=kind, pcm=f"{kind}.pcm")
                (root / case["pcm"]).write_bytes(pcm)
                document = self.document(pcm_hash=case["pcm_sha256"])
                ranges = VAD.validate_vad_output(document, case, VAD.VAD_MODEL["sha256"])
                selection = dict(source_pcm_sha256=case["pcm_sha256"], vad_output_sha256="b" * 64, ranges=ranges,
                                 vad_input=dict(conditioning=VAD.conditioning_policy("none")))
                args = SimpleNamespace(contexts=["off", "on"], split="dev", control_language="en")
                replay = Mock()
                with patch.object(VAD.E4, "decode_chunk") as decode:
                    records = VAD.evaluate_case(args, case, selection, {}, root, root, replay)
                decode.assert_not_called()
                replay.command.assert_not_called()
                self.assertEqual(2, len(records))
                self.assertTrue(all(r["metrics"]["silence_inserted_words"] == 0 and r["chunks"] == [] for r in records))
        # These are mocked VAD decisions, not measured Silero/noise quality.

    def test_source_padding_excludes_source_clip_intervals(self):
        case = dict(total_samples=16000, clips=[dict(start_sample=1000, end_sample=5000),
                                               dict(start_sample=7000, end_sample=15000)])
        self.assertEqual([(0, 1000), (5000, 7000), (15000, 16000)], VAD.inserted_intervals(case))
        self.assertEqual([(0, 16000)], VAD.inserted_intervals(dict(case, clips=[])))

    def test_prepare_vad_recomputes_coverage_from_enriched_prepared_clips(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir(); output.mkdir()
            pcm = bytes(64000)
            (source / "input.pcm").write_bytes(pcm)
            clip = dict(id="synthetic-source", language="en", split="dev", audio="source.wav",
                        source_text_id="synthetic-text-id", audio_sha256="c" * 64,
                        start_sample=8000, end_sample=24000, source_frames=16000,
                        covered_samples=16000, coverage=1., missing_sample_ranges=[])
            case = dict(self.case(32000, hashlib.sha256(pcm).hexdigest()), name="en-normal",
                        language="en", scenario="normal", gain=1., clips=[clip])
            document = self.document(32000, case["pcm_sha256"])
            document["ranges"] = [dict(native_start_cs=0, native_end_cs=100, start_sample=0, end_sample=16000)]
            document["probabilities"] = [.9] * 32 + [0.] * 31
            args = SimpleNamespace(vad_probe=root / "probe", vad_model=root / "model", vad_timeout=10,
                                   vad_conditioning="none")
            def native_vad(command, timeout, **kwargs):
                self.assertEqual(str(source / "input.pcm"), command[2])
                self.assertEqual("32000", command[command.index("--samples") + 1])
                with Path(command[3]).open("x") as stream:
                    json.dump(document, stream)
                return "Synthetic native VAD output"
            with patch.object(VAD, "bounded_process", side_effect=native_vad), patch.object(VAD.E4, "decode_chunk") as decode:
                selection = VAD.prepare_vad(args, case, source, output)
            decode.assert_not_called()
            summary = selection["summary"]
            self.assertEqual(.5, summary["source_clip_coverage"])
            self.assertEqual(8000, summary["source_clip_covered_samples"])
            self.assertEqual(8000, summary["inserted_silence_work_samples"])
            selected_clip = summary["clips"][0]
            self.assertEqual(8000, selected_clip["covered_samples"])
            self.assertEqual(.5, selected_clip["coverage"])
            self.assertEqual([(16000, 24000)], selected_clip["missing_sample_ranges"])
            self.assertEqual(clip["audio_sha256"], selected_clip["audio_sha256"])
            self.assertEqual(1., clip["coverage"])
            self.assertEqual(16000, clip["covered_samples"])
            self.assertEqual(VAD.digest_file(output / "en-normal.vad.json"), selection["vad_output_sha256"])
            self.assertEqual(case["pcm_sha256"], selection["vad_input"]["pcm_sha256"])
            self.assertIsNone(selection["vad_input"]["derived_pcm"])

    def test_conditioner_is_causal_little_endian_bounded_and_keeps_partial_tail(self):
        quiet = struct.pack("<hh", 10, -10) * 256
        loud = struct.pack("<hh", 20000, -20000) * 256
        tail = struct.pack("<h", 10) * 17
        blocks = list(VAD.conditioned_blocks(io.BytesIO(quiet + loud + tail), 1041))
        self.assertEqual([quiet, loud, tail], [source for source, derived in blocks])
        self.assertEqual(struct.pack("<hh", 300, -300) * 256, blocks[0][1])
        self.assertEqual(loud, blocks[1][1])
        self.assertEqual(tail, blocks[2][1])  # A loud preceding frame keeps the envelope high.
        self.assertEqual(blocks[0], list(VAD.conditioned_blocks(io.BytesIO(quiet), 512))[0])
        self.assertEqual(2082, sum(len(derived) for source, derived in blocks))
        # DC participates in RMS; a large constant input is not boosted or removed.
        dc = struct.pack("<h", 6000) * 17
        self.assertEqual([(dc, dc)], list(VAD.conditioned_blocks(io.BytesIO(dc), 17)))
        zero = bytes(34)
        self.assertEqual([(zero, zero)], list(VAD.conditioned_blocks(io.BytesIO(zero), 17)))
        with self.assertRaisesRegex(ValueError, "truncated"):
            list(VAD.conditioned_blocks(io.BytesIO(quiet[:-1]), 512))
        with self.assertRaisesRegex(ValueError, "grew"):
            list(VAD.conditioned_blocks(io.BytesIO(quiet + b"\0"), 512))

    def test_conditioned_file_preserves_targets_rejects_bounds_and_binds_consumed_source(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "source.pcm", Path(directory) / "derived.pcm"
            raw = struct.pack("<h", 10) * 513
            source.write_bytes(raw)
            expected_hash = hashlib.sha256(raw).hexdigest()
            for invalid in (0, True, 513.0, VAD.MAX_SAMPLES + 1, 512, 514):
                with self.subTest(samples=invalid), self.assertRaisesRegex(ValueError, "size"):
                    VAD.prepare_conditioned_pcm(source, output, invalid, expected_hash)
                self.assertFalse(output.exists())
            output.write_bytes(b"Existing evidence")
            with self.assertRaises(FileExistsError):
                VAD.prepare_conditioned_pcm(source, output, 513, expected_hash)
            self.assertEqual(b"Existing evidence", output.read_bytes())
            output.unlink()
            # Restore the original path before the final check: only hashing the path
            # before/after derivation would miss this different consumed payload.
            actual_blocks = VAD.conditioned_blocks
            def changed_consumed_bytes(stream, total):
                yield from actual_blocks(io.BytesIO(struct.pack("<h", 20) * total), total)
            with patch.object(VAD, "conditioned_blocks", side_effect=changed_consumed_bytes):
                with self.assertRaisesRegex(ValueError, "changed during"):
                    VAD.prepare_conditioned_pcm(source, output, 513, expected_hash)
            self.assertFalse(output.exists())
            self.assertEqual(raw, source.read_bytes())
            derived_hash = VAD.prepare_conditioned_pcm(source, output, 513, expected_hash)
            self.assertEqual(struct.pack("<h", 300) * 513, output.read_bytes())
            self.assertEqual(VAD.digest_file(output), derived_hash)
            self.assertEqual(expected_hash, VAD.digest_file(source))

    def test_conditioned_prepared_stream_feeds_changed_vad_but_original_asr_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir(); output.mkdir()
            raw = struct.pack("<hh", 10, -10) * 16000
            pcm = source / "input.pcm"
            pcm.write_bytes(raw)
            clip = dict(id="synthetic-source", language="en", split="dev", audio="source.wav",
                        source_text_id="synthetic-text-id", audio_sha256="c" * 64,
                        start_sample=8000, end_sample=24000, source_frames=16000,
                        covered_samples=16000, coverage=1., missing_sample_ranges=[])
            case = dict(self.case(32000, hashlib.sha256(raw).hexdigest()), name="en-quiet",
                        language="en", scenario="quiet", gain=.25, clips=[clip])
            args = SimpleNamespace(vad_probe=root / "vad-probe", vad_model=root / "model", vad_timeout=10,
                                   vad_conditioning="causal-rms-cap30", contexts=["off"], split="dev",
                                   control_language="en")
            def native_vad(command, timeout, **kwargs):
                derived = Path(command[2])
                self.assertNotEqual(pcm, derived)
                self.assertEqual(raw, pcm.read_bytes())
                self.assertEqual(len(raw), derived.stat().st_size)
                self.assertNotEqual(case["pcm_sha256"], VAD.digest_file(derived))
                document = self.document(32000, VAD.digest_file(derived))
                document["ranges"] = [dict(native_start_cs=0, native_end_cs=100, start_sample=0, end_sample=16000)]
                with Path(command[3]).open("x") as stream:
                    json.dump(document, stream)
                return "Synthetic native VAD output"
            with patch.object(VAD, "bounded_process", side_effect=native_vad):
                selection = VAD.prepare_vad(args, case, source, output)
            self.assertEqual(case["pcm_sha256"], selection["source_pcm_sha256"])
            self.assertNotEqual(case["pcm_sha256"], selection["vad_input"]["pcm_sha256"])
            self.assertTrue(selection["vad_input"]["source_pcm_unchanged"])
            self.assertEqual(64000, selection["vad_input"]["bytes"])
            self.assertEqual(.5, selection["summary"]["source_clip_coverage"])
            def native_asr(args, wav, prompt, prefix, language):
                with wave.open(str(wav)) as audio:
                    self.assertEqual((1, 2, 16000), (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()))
                    self.assertEqual(raw[:32000], audio.readframes(audio.getnframes()))
                document = dict(model=dict(multilingual=True, audio=dict(ctx=1500)),
                    probe=dict(schema=1, load_seconds=0., decode_seconds=0., peak_rss_bytes=None,
                        initial_prompt_sha256=VAD.digest_file(prompt), beam_size=3, threads=4,
                        effective_audio_context=1500, original_audio_frames=16000, decode_audio_frames=16000),
                    transcription=[dict(offsets={"from": 0, "to": 1000}, text="synthetic", probabilities=[.9], noSpeech=.1)])
                payload = json.dumps(document)
                prefix.with_suffix(".json").write_text(payload)
                return document, json.loads(payload, parse_float=Decimal), 0.
            replay = Mock()
            replay.review.return_value = False
            replay.command.return_value = "startMs\tendMs\tuncertain\ttextBase64\n0\t1000\tfalse\tc3ludGhldGlj\n"
            with patch.object(VAD.E4, "decode_chunk", side_effect=native_asr) as decode:
                records = VAD.evaluate_case(args, case, selection,
                    {clip["id"]: dict(text="synthetic")}, source, output, replay)
            decode.assert_called_once()
            self.assertEqual(raw, pcm.read_bytes())
            self.assertEqual(case["pcm_sha256"], records[0]["source_pcm_sha256"])
            self.assertEqual(selection["vad_input"], records[0]["vad_input"])
            self.assertEqual("synthetic", records[0]["hypothesis"])
            self.assertEqual(0, records[0]["metrics"]["word_errors"])

    def test_engine_dependency_and_model_provenance_mismatch_fail(self):
        engine = dict(source_checkout_commit=VAD.ENGINE_COMMIT, shared_libraries=[dict(name="libwhisper.so")])
        with patch.object(VAD.E4, "checked_engine", return_value=engine):
            self.assertEqual(engine, VAD.checked_engine(Path("probe")))
        for changed in (dict(engine, source_checkout_commit="other"), dict(engine, shared_libraries=[])):
            with patch.object(VAD.E4, "checked_engine", return_value=changed), self.assertRaises(ValueError):
                VAD.checked_engine(Path("probe"))
        with tempfile.TemporaryDirectory() as directory:
            model, provenance = Path(directory) / "model", Path(directory) / "provenance.json"
            model.write_bytes(b"changed")
            provenance.write_text(json.dumps(VAD.VAD_MODEL))
            with self.assertRaisesRegex(ValueError, "size or SHA"):
                VAD.verify_vad_model(model, provenance)
            provenance.write_text(json.dumps(dict(VAD.VAD_MODEL, revision="other")))
            with self.assertRaisesRegex(ValueError, "pinned"):
                VAD.verify_vad_model(model, provenance)


if __name__ == "__main__":
    unittest.main()

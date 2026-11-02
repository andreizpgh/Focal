import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
from unittest.mock import patch, MagicMock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import unittest
import wave

SCRIPT = Path(__file__).resolve().parents[1] / "prepare-gate-experiment.py"
SPEC = importlib.util.spec_from_file_location("prepare_gate", SCRIPT)
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class GatePreparationGuardsTest(unittest.TestCase):
    def source(self, root, channels=1):
        path = root / "source.wav"
        with wave.open(str(path), "wb") as output:
            output.setnchannels(channels)
            output.setsampwidth(2)
            output.setframerate(16000)
            output.writeframes(bytes(16000 * channels * 2))
        return dict(id="synthetic", audio=path.name, language="en", split="dev",
                    audio_sha256=hashlib.sha256(path.read_bytes()).hexdigest())

    def test_held_out_clips_cannot_fill_missing_dev_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = self.source(root)
            rows = [dict(row, id=language, language=language, split="test") for language in PREPARE.LANGUAGES]
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps(rows))
            with self.assertRaisesRegex(ValueError, "held-out"):
                PREPARE.select_sources(manifest, 1)

    def test_duplicate_source_ids_and_unbounded_selection_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = self.source(root)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps([row, row]))
            with self.assertRaisesRegex(ValueError, "unique"):
                PREPARE.select_sources(manifest, 1)
            with self.assertRaisesRegex(ValueError, "1–12"):
                PREPARE.select_sources(manifest, 13)

    def test_held_out_pcm_and_groups_cannot_duplicate_development(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = self.source(root)
            rows = [dict(row, id=language, language=language, split="dev") for language in PREPARE.LANGUAGES]
            # Add a RIFF chunk so the whole-file hash differs but audio PCM matches.
            source = (root / row["audio"]).read_bytes()
            altered = bytearray(source + b"JUNK" + (2).to_bytes(4, "little") + b"xx")
            altered[4:8] = (len(altered) - 8).to_bytes(4, "little")
            test_path = root / "test.wav"
            test_path.write_bytes(altered)
            rows.append(dict(row, id="heldout", split="test", audio=test_path.name,
                             audio_sha256=hashlib.sha256(altered).hexdigest()))
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps(rows))
            with self.assertRaisesRegex(ValueError, "Identical PCM"):
                PREPARE.select_sources(manifest, 1)
            with wave.open(str(test_path), "wb") as output:
                output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
                output.writeframes(b"\x01\x00" * 16000)
            rows[-1]["audio_sha256"] = hashlib.sha256(test_path.read_bytes()).hexdigest()
            rows[0]["group"] = "synthetic-speaker"
            rows[-1]["group"] = "synthetic-speaker"
            manifest.write_text(json.dumps(rows))
            with self.assertRaisesRegex(ValueError, "group"):
                PREPARE.select_sources(manifest, 1)

    def test_replay_stdout_and_stderr_are_bounded(self):
        from replay_compiler import bounded_process
        for stream in ("stdout", "stderr"):
            with self.subTest(stream=stream), self.assertRaisesRegex(ValueError, "output limit"):
                bounded_process([sys.executable, "-c", f"import sys; sys.{stream}.write('x'*200000)"], 5, limit=1024)

    def test_replay_timeout_is_bounded(self):
        import subprocess
        from replay_compiler import bounded_process
        with self.assertRaises(subprocess.TimeoutExpired):
            bounded_process([sys.executable, "-c", "import time; time.sleep(2)"], .1)

    def test_gain_rounding_and_source_sample_offsets(self):
        import struct
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "signed.wav"
            pcm = struct.pack("<4h", 32767, -32768, 15, 25) + bytes((16000 - 4) * 2)
            with wave.open(str(path), "wb") as output:
                output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
                output.writeframes(pcm)
            row = dict(id="synthetic", audio=path.name, language="en", split="dev",
                       audio_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
            dest = root / "stream.pcm"
            intervals, padding, total = PREPARE.write_stream(dest, [row, row], root, 0.1, 10, 5, 3)
            self.assertEqual([(10, 16010), (16015, 32015)], [(r["start_sample"], r["end_sample"]) for r in intervals])
            self.assertEqual([(0, 10), (16010, 16015), (32015, 32018)], padding)
            self.assertEqual(32018, total)
            self.assertEqual((3277, -3277, 2, 2), struct.unpack_from("<4h", dest.read_bytes(), 20))

    def test_fresh_compile_binds_sources_and_class_tree(self):
        from replay_compiler import REPLAY_SOURCES, compile_replay
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in REPLAY_SOURCES:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(name)
            compiler = root / "compiler.jar"; compiler.write_bytes(b"compiler")
            stdlib = root / "stdlib.jar"; stdlib.write_bytes(b"stdlib")
            cp = root / "cp.txt"; cp.write_text(str(compiler))
            classes = root / "classes"
            def fake_compiler(command, timeout, limit, **kwargs):
                if command[-1] == "-version":
                    return "synthetic compiler"
                (classes / "Replay.class").write_bytes(b"compiled")
                return ""
            with patch("replay_compiler.bounded_process", side_effect=fake_compiler):
                result = compile_replay(root, "java", cp, stdlib, classes, 10)
            self.assertEqual(hashlib.sha256(b"compiled").hexdigest(), result["classes"]["Replay.class"])
            self.assertEqual(hashlib.sha256(b"stdlib").hexdigest(), result["stdlib_sha256"])
            self.assertEqual(set(REPLAY_SOURCES), set(result["sources"]))

    def test_source_container_has_bounded_size_before_hashing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = self.source(root)
            path = root / row["audio"]
            with path.open("ab") as output:
                output.write(bytes(4 * 1024 * 1024))
            row["audio_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "container size"):
                PREPARE.validate_source(row, root)

    def test_partial_ignore_rule_and_other_worktree_are_rejected(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / ".gitignore").write_text("manifest.json\n")
            with self.assertRaisesRegex(ValueError, "ignored directory"):
                PREPARE.require_private_output(root / "partial", SCRIPT.parent.parent)
            (root / ".gitignore").write_text("ignored/\n")
            self.assertEqual((root / "ignored").resolve(), PREPARE.require_private_output(root / "ignored", SCRIPT.parent.parent))

    def test_replay_timeout_terminates_inherited_pipe_descendant(self):
        import subprocess
        import time
        from replay_compiler import bounded_process
        child = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time; time.sleep(20)']); time.sleep(20)"
        start = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired):
            bounded_process([sys.executable, "-c", child], .3)
        self.assertLess(time.monotonic() - start, 4)

    def test_git_errors_do_not_make_worktree_output_public(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            error = subprocess.CompletedProcess([], 128, "", "fatal: detected dubious ownership")
            with patch.object(PREPARE.subprocess, "run", return_value=error):
                with self.assertRaisesRegex(ValueError, "Could not determine"):
                    PREPARE.require_private_output(Path(directory) / "output", SCRIPT.parent.parent)

    def test_replay_interrupt_terminates_child(self):
        import io
        from replay_compiler import bounded_process
        process = MagicMock()
        process.pid = 99999999
        process.stdout = io.BytesIO(); process.stderr = io.BytesIO()
        process.wait.side_effect = [KeyboardInterrupt(), 0]
        with patch("replay_compiler.subprocess.Popen", return_value=process):
            with self.assertRaises(KeyboardInterrupt):
                bounded_process(["java"], 5)
        process.kill.assert_called()

    def test_source_checksum_and_wav_format_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = self.source(root)
            with self.assertRaisesRegex(ValueError, "checksum"):
                PREPARE.validate_source(dict(row, audio_sha256="0" * 64), root)
            row = self.source(root, channels=2)
            with self.assertRaisesRegex(ValueError, "mono"):
                PREPARE.validate_source(row, root)

    def test_truncated_pcm_and_source_path_escape_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = self.source(root)
            path = root / row["audio"]
            path.write_bytes(path.read_bytes()[:-10])
            row["audio_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "truncated"):
                PREPARE.validate_source(row, root)
            child = root / "child"
            child.mkdir()
            with self.assertRaisesRegex(ValueError, "inside"):
                PREPARE.validate_source(dict(row, audio="../source.wav"), child)

    def test_public_or_nonempty_output_preserves_existing_files(self):
        repository = SCRIPT.parent.parent
        with self.assertRaisesRegex(ValueError, "ignored"):
            PREPARE.require_private_output(repository / "scripts" / "gate-output", repository)
        with self.assertRaisesRegex(ValueError, "metadata"):
            PREPARE.require_private_output(repository / ".git" / "gate-output", repository)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            preserved = root / "existing.json"
            preserved.write_text("preserved")
            with self.assertRaisesRegex(ValueError, "empty"):
                PREPARE.require_private_output(root, repository)
            self.assertEqual("preserved", preserved.read_text())


if __name__ == "__main__":
    unittest.main()

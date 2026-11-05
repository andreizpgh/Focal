"""Tests for scripts/fetch-model.py against synthetic lock files. Nothing touches the network.

Run: python3 -m unittest discover -s scripts/tests
"""
import contextlib
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "fetch-model.py"
_spec = importlib.util.spec_from_file_location("fetch_model", SCRIPT)
fetch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fetch)

MODEL = {"encoder.int8.onnx": bytes(range(256)) * 40, "tokens.txt": b"<blk> 0\nhello 1\n"}
RUNTIME = b"synthetic runtime archive " * 300
URL = "https://example.invalid/"


def pin(data, name):
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "url": URL + name}


class Response:
    """A urlopen() result that serves its body in small blocks, then optionally fails."""

    def __init__(self, body, fail=None, on_read=None):
        self.body = io.BytesIO(body)
        self.fail = fail
        self.on_read = on_read

    def read(self, size=-1):
        if self.on_read:
            self.on_read()
        block = self.body.read(min(size, 1000))
        if not block and self.fail:
            raise self.fail
        return block

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FetchModelTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.models = self.root / "models"
        self.models.mkdir()
        self.model_dir = self.models / "parakeet-ultra"
        self.runtime_file = self.root / "libs/runtime.aar"
        self.model_lock = {"directory": "models/parakeet-ultra",
                           "files": [{"name": name, **pin(data, name)} for name, data in MODEL.items()]}
        self.runtime_lock = {"path": "libs/runtime.aar", **pin(RUNTIME, "runtime.aar")}
        self.bodies = {URL + name: data for name, data in MODEL.items()}
        self.bodies[URL + "runtime.aar"] = RUNTIME
        self.requested = []
        for name, value in [("ROOT", self.root), ("MODEL_LOCK", self.models / "parakeet-ultra.json"),
                            ("RUNTIME_LOCK", self.models / "sherpa-onnx.json")]:
            patcher = mock.patch.object(fetch, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(fetch.urllib.request, "urlopen", side_effect=self.urlopen)
        patcher.start()
        self.addCleanup(patcher.stop)

    def urlopen(self, url, timeout):
        self.requested.append(url)
        body = self.bodies[url]
        return body if isinstance(body, Response) else Response(body)

    def run_script(self, *argv):
        (self.models / "parakeet-ultra.json").write_text(json.dumps(self.model_lock))
        (self.models / "sherpa-onnx.json").write_text(json.dumps(self.runtime_lock))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = fetch.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def assert_installed(self, *names):
        self.assertEqual(sorted(names), sorted(p.name for p in self.model_dir.iterdir()))
        for name in names:
            self.assertEqual(MODEL[name], (self.model_dir / name).read_bytes())

    def assert_no_partial_files(self):
        self.assertEqual([], [p for p in self.root.rglob("*") if p.name.endswith(".partial")])

    def test_downloads_and_verifies_every_file(self):
        code, out, err = self.run_script()
        self.assertEqual((0, ""), (code, err))
        self.assertEqual(3, out.count(": downloaded"))
        self.assert_installed(*MODEL)
        self.assertEqual(RUNTIME, self.runtime_file.read_bytes())
        self.assert_no_partial_files()

    def test_verified_files_are_left_alone(self):
        self.run_script()
        self.requested.clear()
        code, out, _ = self.run_script()
        self.assertEqual(0, code)
        self.assertEqual([], self.requested)
        self.assertEqual(3, out.count("already verified"))

    def test_check_verifies_without_network_or_new_directories(self):
        code, _, err = self.run_script("--check")
        self.assertEqual(1, code)
        self.assertIn("missing or does not match", err)
        self.assertEqual([], self.requested)
        self.assertFalse(self.model_dir.exists())
        self.run_script()
        self.requested.clear()
        self.assertEqual(0, self.run_script("--check")[0])
        self.assertEqual([], self.requested)

    def test_runtime_only_fetches_just_the_runtime(self):
        code, _, _ = self.run_script("--runtime-only")
        self.assertEqual(0, code)
        self.assertEqual([URL + "runtime.aar"], self.requested)
        self.assertFalse(self.model_dir.exists())

    def test_truncated_download_is_rejected_and_the_other_files_still_install(self):
        self.bodies[URL + "encoder.int8.onnx"] = MODEL["encoder.int8.onnx"][:-10]
        code, _, err = self.run_script()
        self.assertEqual(1, code)
        self.assertIn("does not match the SHA-256", err)
        self.assert_installed("tokens.txt")
        self.assert_no_partial_files()

    def test_oversized_download_stops_at_the_locked_size(self):
        self.bodies[URL + "encoder.int8.onnx"] = MODEL["encoder.int8.onnx"] + b"x" * 5000
        code, _, err = self.run_script()
        self.assertEqual(1, code)
        self.assertIn("is larger than", err)
        self.assert_installed("tokens.txt")
        self.assert_no_partial_files()

    def test_same_size_with_other_content_is_rejected(self):
        data = MODEL["encoder.int8.onnx"]
        self.bodies[URL + "encoder.int8.onnx"] = data[:-1] + bytes([data[-1] ^ 1])
        code, _, err = self.run_script()
        self.assertEqual(1, code)
        self.assertIn("does not match the SHA-256", err)
        self.assertFalse((self.model_dir / "encoder.int8.onnx").exists())
        self.assert_no_partial_files()

    def test_connection_dropped_mid_body_is_reported_and_the_run_continues(self):
        self.bodies[URL + "encoder.int8.onnx"] = Response(
            MODEL["encoder.int8.onnx"][:3000], fail=http.client.IncompleteRead(b"", 7240))
        code, _, err = self.run_script()
        self.assertEqual(1, code)
        self.assertIn("IncompleteRead", err)
        self.assertIn(URL + "tokens.txt", self.requested)
        self.assert_installed("tokens.txt")
        self.assertEqual(RUNTIME, self.runtime_file.read_bytes())
        self.assert_no_partial_files()

    def test_partial_model_files_are_staged_outside_the_packaged_directory(self):
        seen = []

        def snapshot():
            seen.append(sorted(p.name for p in self.model_dir.iterdir()) if self.model_dir.exists() else [])
            seen.append(sorted(p.name for p in (self.models / "parakeet-ultra.partial").iterdir()))

        self.bodies[URL + "encoder.int8.onnx"] = Response(MODEL["encoder.int8.onnx"], on_read=snapshot)
        self.assertEqual(0, self.run_script()[0])
        self.assertIn(["encoder.int8.onnx.partial"], seen)
        self.assertTrue(all(name in MODEL for names in seen[::2] for name in names))
        self.assertFalse((self.models / "parakeet-ultra.partial").exists())

    def local_sources(self):
        source = self.root / "verified"
        source.mkdir()
        for name, data in MODEL.items():
            (source / name).write_bytes(data)
        (source / "runtime.aar").write_bytes(RUNTIME)
        return source

    def test_local_copies_are_hard_linked(self):
        source = self.local_sources()
        code, out, _ = self.run_script("--from", str(source), "--aar", str(source / "runtime.aar"))
        self.assertEqual(0, code)
        self.assertEqual([], self.requested)
        self.assertEqual(3, out.count(": hard-linked"))
        for name in MODEL:
            self.assertTrue((self.model_dir / name).samefile(source / name))
        self.assert_no_partial_files()

    def test_local_copies_are_copied_when_linking_fails(self):
        source = self.local_sources()
        with mock.patch.object(fetch.os, "link", side_effect=OSError(18, "Invalid cross-device link")):
            code, out, _ = self.run_script("--from", str(source), "--aar", str(source / "runtime.aar"))
        self.assertEqual(0, code)
        self.assertEqual(3, out.count(": copied"))
        self.assert_installed(*MODEL)
        self.assertFalse((self.model_dir / "tokens.txt").samefile(source / "tokens.txt"))
        self.assert_no_partial_files()

    def test_damaged_local_copy_is_refused(self):
        source = self.local_sources()
        (source / "tokens.txt").write_bytes(b"<blk> 0\nhullo 1\n")
        code, _, err = self.run_script("--from", str(source), "--aar", str(source / "runtime.aar"))
        self.assertEqual(1, code)
        self.assertIn("local tokens.txt", err)
        self.assert_installed("encoder.int8.onnx")
        self.assert_no_partial_files()

    def test_unexpected_file_in_the_model_directory_fails(self):
        self.run_script()
        (self.model_dir / "notes.txt").write_text("not part of the model")
        code, _, err = self.run_script()
        self.assertEqual(1, code)
        self.assertIn("Unexpected files in models/parakeet-ultra: notes.txt", err)

    def test_plain_http_is_refused(self):
        self.model_lock["files"][1]["url"] = "http://example.invalid/tokens.txt"
        code, _, err = self.run_script()
        self.assertEqual(1, code)
        self.assertIn("refusing a non-HTTPS URL", err)
        self.assertNotIn("http://example.invalid/tokens.txt", self.requested)
        self.assertFalse((self.model_dir / "tokens.txt").exists())


if __name__ == "__main__":
    unittest.main()

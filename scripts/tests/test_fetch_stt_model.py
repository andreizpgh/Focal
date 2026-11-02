"""Tiny mocked transfer regressions; no network or real model bytes are used."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stt_artifacts

SPEC = importlib.util.spec_from_file_location("fetch_stt_model", Path(__file__).resolve().parents[1] / "fetch-stt-model.py")
MODEL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODEL)


class Response(io.BytesIO):
    def __init__(self, body):
        super().__init__(body)
        self.status = 200
        self.headers = {"Content-Length": str(len(body))}


class PinnedModelTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.repository = self.directory / "repository"
        (self.repository / "models").mkdir(parents=True)
        self.entry = dict(filename="tiny.bin", bytes=4, sha256=stt_artifacts.digest(b"abcd"))
        self.lock = dict(repository="test/model", revision="a" * 40, license="MIT", models=dict(tiny=self.entry))
        (self.repository / "models/stt-candidates.json").write_text(json.dumps(self.lock))
        self.output = self.directory / "output"
        self.output.mkdir()
        self.target = self.output / "tiny.bin"
        self.ledger = self.directory / "ledger.json"
        self.metadata = [dict(path="tiny.bin", lfs=dict(oid=self.entry["sha256"], size=4))]
        self.fake_location = patch.object(MODEL, "__file__", str(self.repository / "scripts/fetch-stt-model.py"))
        self.fake_location.start()
        self.addCleanup(self.fake_location.stop)

    def run_main(self):
        with patch.object(sys, "argv", ["fetch-stt-model.py", "tiny", "--output", str(self.output), "--ledger", str(self.ledger)]), \
                contextlib.redirect_stdout(io.StringIO()):
            MODEL.main()

    def reservations(self):
        return json.loads(self.ledger.read_text())["reservations"]

    def cache_metadata(self):
        path = self.output / "metadata" / (self.lock["revision"] + ".json")
        path.parent.mkdir()
        data = json.dumps(self.metadata).encode()
        path.write_bytes(data)
        path.with_suffix(".json.sha256").write_text(stt_artifacts.digest(data))

    def test_verified_existing_model_avoids_network_and_reservation(self):
        self.target.write_bytes(b"abcd")
        with patch("urllib.request.urlopen", side_effect=AssertionError("Unexpected network request")) as request:
            self.run_main()
        request.assert_not_called()
        self.assertFalse(self.ledger.exists())
        self.assertEqual(self.target.read_bytes(), b"abcd")

    def test_different_existing_model_is_preserved_and_never_redownloaded(self):
        self.target.write_bytes(b"wxyz")
        with patch("urllib.request.urlopen", side_effect=AssertionError("Unexpected network request")) as request:
            with self.assertRaisesRegex(ValueError, "differs from its lock"):
                self.run_main()
        request.assert_not_called()
        self.assertEqual(self.target.read_bytes(), b"wxyz")

    def test_upstream_metadata_must_match_both_locked_hash_and_size(self):
        for field, wrong in (("oid", "0" * 64), ("size", 5)):
            with self.subTest(field=field):
                metadata = json.loads(json.dumps(self.metadata))
                metadata[0]["lfs"][field] = wrong
                with patch("urllib.request.urlopen", return_value=Response(json.dumps(metadata).encode())) as request:
                    with self.assertRaisesRegex(ValueError, "metadata differs"):
                        self.run_main()
                self.assertEqual(request.call_count, 1)
                self.assertFalse(self.target.exists())
                # A fresh metadata cache is needed for the second independent request.
                for path in (self.output / "metadata").iterdir():
                    path.unlink()

    def test_locked_hash_is_checked_before_atomic_publication(self):
        def verified_before_publication(data):
            self.assertFalse(self.target.exists())
            return stt_artifacts.digest(data)

        responses = [Response(json.dumps(self.metadata).encode()), Response(b"abcd")]
        with patch("urllib.request.urlopen", side_effect=responses), patch.object(MODEL, "digest", side_effect=verified_before_publication):
            self.run_main()
        self.assertEqual(self.target.read_bytes(), b"abcd")
        self.assertEqual(self.target.with_suffix(".bin.sha256").read_text().strip(), self.entry["sha256"])
        self.assertFalse(self.target.with_suffix(".bin.partial").exists())
        provenance = json.loads(self.target.with_suffix(".provenance.json").read_text())
        self.assertEqual(provenance["sha256"], self.entry["sha256"])
        self.assertEqual(provenance["revision"], self.lock["revision"])
        self.assertEqual([entry["kind"] for entry in self.reservations()], ["metadata", "model"])

    def test_failed_checksum_is_removed_and_failed_transfer_stays_charged(self):
        responses = [Response(json.dumps(self.metadata).encode()), Response(b"wxyz")]
        with patch("urllib.request.urlopen", side_effect=responses) as request:
            with self.assertRaisesRegex(ValueError, "checksum"):
                self.run_main()
        self.assertEqual(request.call_count, 2)
        self.assertFalse(self.target.exists())
        self.assertFalse(self.target.with_suffix(".bin.partial").exists())
        self.assertFalse(self.target.with_suffix(".bin.partial.sha256").exists())
        self.assertEqual([entry["bytes"] for entry in self.reservations()], [1024 * 1024, 5])
        self.assertEqual([entry["kind"] for entry in self.reservations()], ["metadata", "model"])

    def test_network_failure_keeps_reservation_without_automatic_retry(self):
        responses = [Response(json.dumps(self.metadata).encode()), urllib.error.URLError("synthetic offline failure")]
        with patch("urllib.request.urlopen", side_effect=responses) as request:
            with self.assertRaises(urllib.error.URLError):
                self.run_main()
        self.assertEqual(request.call_count, 2)
        self.assertFalse(self.target.exists())
        self.assertEqual([entry["bytes"] for entry in self.reservations()], [1024 * 1024, 5])

    def test_shared_budget_rejects_before_model_request(self):
        self.cache_metadata()
        self.ledger.write_text(json.dumps(dict(limit=stt_artifacts.LIMIT,
                                              reservations=[dict(bytes=stt_artifacts.LIMIT - 4, kind="model")])))
        with patch("urllib.request.urlopen", side_effect=AssertionError("Unexpected network request")) as request:
            with self.assertRaisesRegex(ValueError, "budget"):
                self.run_main()
        request.assert_not_called()
        self.assertFalse(self.target.exists())
        self.assertEqual(len(self.reservations()), 1)


if __name__ == "__main__":
    unittest.main()

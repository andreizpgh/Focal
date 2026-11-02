import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SPEC = importlib.util.spec_from_file_location("fetch_corpus", Path(__file__).resolve().parents[1] / "fetch-stt-corpus.py")
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)


class Response(io.BytesIO):
    def __init__(self, body, status=206, content_range="bytes 0-3/100"):
        super().__init__(body)
        self.status = status
        self.headers = {"Content-Range": content_range, "Content-Length": str(len(body))}


class BoundedCorpusTest(unittest.TestCase):
    def test_server_ignoring_range_cannot_download_whole_shard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            response = Response(b"full archive", status=200)
            with patch("urllib.request.urlopen", return_value=response):
                with self.assertRaisesRegex(ValueError, "exact bounded"):
                    FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100)
            self.assertFalse((root / "a").exists())
            # Failed requests consume the full reservation; retries cannot hide it.
            self.assertEqual(4, json.loads((root / "ledger.json").read_text())["reservations"][0]["bytes"])

    def test_wrong_range_or_incomplete_body_is_never_cached(self):
        for response in (Response(b"abcd", content_range="bytes 4-7/100"), Response(b"abc")):
            with self.subTest(response=response), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with patch("urllib.request.urlopen", return_value=response):
                    with self.assertRaises(ValueError):
                        FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100)
                self.assertFalse((root / "a").exists())

    def test_cache_is_verified_and_does_not_consume_another_reservation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("urllib.request.urlopen", return_value=Response(b"abcd")) as request:
                self.assertEqual(b"abcd", FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100))
                self.assertEqual(b"abcd", FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100))
                self.assertEqual(1, request.call_count)
                (root / "a").write_bytes(b"bad!")
                with self.assertRaisesRegex(ValueError, "changed"):
                    FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100)
            self.assertEqual(1, len(json.loads((root / "ledger.json").read_text())["reservations"]))

    def test_combined_model_corpus_budget_rejects_before_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            FETCH.save_json(root / "ledger.json", dict(limit=1_000_000_000, reservations=[dict(bytes=1_000_000_000 - 3, kind="model")]))
            with patch("urllib.request.urlopen") as request:
                with self.assertRaisesRegex(ValueError, "budget"):
                    FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100)
                request.assert_not_called()

    def test_truncated_reference_file_is_rejected_even_when_parseable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("urllib.request.urlopen", return_value=Response(b"a\tb\tc\n", status=200)):
                with self.assertRaisesRegex(ValueError, "Truncated source"):
                    FETCH.fetch("https://example.com/references", root / "a", 20, root / "ledger.json", expected_size=19)
            self.assertFalse((root / "a").exists())

    def test_cached_content_still_must_match_requested_range_size(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "a").write_bytes(b"abc")
            (root / "a.sha256").write_text(FETCH.digest(b"abc"))
            with self.assertRaisesRegex(ValueError, "length"):
                FETCH.fetch("https://example.com/a", root / "a", 4, root / "ledger.json", (0, 3), 100)

    def test_partial_tar_never_returns_partial_wav_or_extracts_paths(self):
        import gzip
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode="w") as archive:
            for name, body in (("../../first.wav", b"first"), ("second.wav", b"second" * 1000)):
                entry = tarfile.TarInfo(name); entry.size = len(body)
                archive.addfile(entry, io.BytesIO(body))
        # A complete first member and a partial second member, compressed as a prefix.
        prefix = gzip.compress(raw.getvalue()[:2000])
        self.assertEqual([("first.wav", b"first")], list(FETCH.complete_wavs(prefix)))


if __name__ == "__main__":
    unittest.main()

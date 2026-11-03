import importlib.util
import io
import hashlib
import json
from pathlib import Path
import tarfile
import tempfile
import sys
import unittest
from unittest.mock import patch
import wave

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
    def wav(self, value):
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(value.to_bytes(2, "little", signed=True) * 16000)
        return output.getvalue()

    def test_prior_ids_and_audio_are_excluded_before_counting_fresh_clips(self):
        used, raw_used, fresh = self.wav(1), self.wav(2), self.wav(3)
        # Raw PCM identity also catches equivalent WAVs with a different header.
        changed_header = bytearray(raw_used + b"JUNK\x02\x00\x00\x00xx")
        changed_header[4:8] = (len(changed_header) - 8).to_bytes(4, "little")
        with tempfile.TemporaryDirectory() as directory:
            provenance = Path(directory) / "provenance.json"
            provenance.write_text(json.dumps(dict(dataset="google/fleurs", revision=FETCH.REVISION, clips=[
                dict(id="fleurs-en_us-test-1", pcm16_sha256=FETCH.digest(used), pcm_sha256=FETCH.pcm_hash(raw_used)),
            ])))
            excluded = FETCH.load_exclusions([provenance])
            sources = [("1.wav", b"ID excluded before conversion"), ("2.wav", used),
                       ("3.wav", bytes(changed_header)), ("4.wav", fresh)]
            with patch.object(FETCH, "complete_wavs", return_value=iter(sources)):
                with patch.object(FETCH, "pcm16", side_effect=lambda source: (source, 1.)) as convert:
                    selected = FETCH.select_wavs(b"unused prefix", "en_us", "test", 2, excluded)
            self.assertEqual(["4.wav"], [row[0] for row in selected])
            self.assertEqual(3, convert.call_count)
            self.assertEqual([dict(sha256=FETCH.digest(provenance.read_bytes()), clips=1)], excluded["sources"])

    def test_all_prior_cohorts_apply_without_reference_text_or_archive_reordering(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index in (1, 2):
                path = Path(directory) / f"prior-{index}.json"
                path.write_text(json.dumps(dict(dataset="google/fleurs", revision=FETCH.REVISION,
                    clips=[dict(id=f"fleurs-ru_ru-dev-{index}", pcm16_sha256=FETCH.digest(self.wav(index)))])))
                paths.append(path)
            excluded = FETCH.load_exclusions(paths)
            sources = [("9.wav", self.wav(2)), ("8.wav", self.wav(3)), ("7.wav", self.wav(1)), ("6.wav", self.wav(4))]
            with patch.object(FETCH, "complete_wavs", return_value=iter(sources)):
                with patch.object(FETCH, "pcm16", side_effect=lambda source: (source, 1.)):
                    selected = FETCH.select_wavs(b"unused", "ru_ru", "test", 2, excluded)
            self.assertEqual(["8.wav", "6.wav"], [row[0] for row in selected])
            self.assertEqual(2, len(excluded["sources"]))

    def test_invalid_or_empty_exclusions_fail_before_fetching(self):
        valid = dict(dataset="google/fleurs", revision=FETCH.REVISION,
                     clips=[dict(id="fleurs-en_us-test-1", pcm16_sha256="a" * 64)])
        invalid = [dict(valid, revision="other"), dict(valid, dataset="other"), dict(valid, clips=[]),
                   dict(valid, clips=[dict(valid["clips"][0], pcm16_sha256="bad")]),
                   dict(valid, clips=[dict(valid["clips"][0], id="../source")]),
                   dict(valid, clips=[dict(valid["clips"][0], pcm_sha256=None)])]
        with tempfile.TemporaryDirectory() as directory:
            provenance = Path(directory) / "provenance.json"
            for document in invalid:
                with self.subTest(document=document):
                    provenance.write_text(json.dumps(document))
                    with patch.object(sys, "argv", ["fetch-stt-corpus.py", "--exclude-provenance", str(provenance)]):
                        with patch.object(FETCH, "fetch") as fetch:
                            with self.assertRaises(ValueError):
                                FETCH.main()
                            fetch.assert_not_called()

    def test_repeated_selected_pcm_is_not_counted_as_another_clip(self):
        source = self.wav(1)
        with patch.object(FETCH, "complete_wavs", return_value=iter([("1.wav", source), ("2.wav", source)])):
            with patch.object(FETCH, "pcm16", return_value=(source, 1.)):
                with self.assertRaisesRegex(ValueError, "Repeated"):
                    FETCH.select_wavs(b"unused", "en_us", "test", 2, FETCH.load_exclusions([]))

    def test_existing_evidence_and_exclusion_alias_are_preserved_before_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("manifest.json", "provenance.json", "source.wav"):
                with self.subTest(name=name):
                    existing = root / name
                    existing.write_bytes(b"preserved synthetic evidence")
                    arguments = ["fetch-stt-corpus.py", "--output", str(root), "--splits", "test",
                                 "--exclude-provenance", str(existing)]
                    with patch.object(sys, "argv", arguments), patch.object(FETCH, "fetch") as fetch:
                        with self.assertRaisesRegex(ValueError, "existing evidence is preserved"):
                            FETCH.main()
                        fetch.assert_not_called()
                    self.assertEqual(b"preserved synthetic evidence", existing.read_bytes())
                    existing.unlink()
            (root / "source" / FETCH.REVISION).mkdir(parents=True)
            (root / "source" / FETCH.REVISION / "cached.gzpart").write_bytes(b"source cache")
            FETCH.validate_output(root)

    def test_test_only_fetch_records_exclusions_and_never_requests_development(self):
        used, fresh = self.wav(1), self.wav(2)
        tsv = b"1\t1.wav\tUsed synthetic reference\n2\t2.wav\tFresh synthetic reference\n"
        tree = [dict(path="data/en_us/test.tsv", size=len(tsv),
                     oid=hashlib.sha1(f"blob {len(tsv)}\0".encode() + tsv).hexdigest())]
        archives = [dict(path="data/en_us/audio/test.tar.gz", size=16 * FETCH.BLOCK + 1, lfs=dict(oid="a" * 64))]
        requested = []
        def fake_fetch(url, target, *args, **kwargs):
            requested.append(target.name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.name == "en_us-tree.json":
                return json.dumps(tree).encode()
            if target.name == "en_us-audio-tree.json":
                return json.dumps(archives).encode()
            if target.name == "en_us-test.tsv":
                return tsv
            if target.name == "en_us-test-00.gzpart":
                return b"synthetic bounded prefix"
            self.fail("Unexpected source request")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prior = root / "prior.json"
            prior.write_text(json.dumps(dict(dataset="google/fleurs", revision=FETCH.REVISION,
                clips=[dict(id="fleurs-en_us-test-1", pcm16_sha256=FETCH.digest(used))])))
            output = root / "corpus"
            output.mkdir(mode=0o700)
            (output / "source").mkdir()
            arguments = ["fetch-stt-corpus.py", "--output", str(output), "--ledger", str(root / "ledger.json"),
                         "--splits", "test", "--test-clips", "1", "--exclude-provenance", str(prior)]
            with patch.object(sys, "argv", arguments), patch.object(FETCH, "CONFIGS", dict(en="en_us")):
                with patch.object(FETCH, "fetch", side_effect=fake_fetch), patch("builtins.print"), patch.object(FETCH.os, "umask"):
                    with patch.object(FETCH, "complete_wavs", return_value=[("1.wav", used), ("2.wav", fresh)]):
                        with patch.object(FETCH, "pcm16", side_effect=lambda source: (source, 1.)):
                            FETCH.main()
            manifest = json.loads((output / "manifest.json").read_text())
            provenance = json.loads((output / "provenance.json").read_text())
            self.assertEqual(["fleurs-en_us-test-2"], [row["id"] for row in manifest])
            self.assertEqual(["test"], provenance["selected_splits"])
            self.assertEqual(FETCH.digest(prior.read_bytes()), provenance["excluded_provenance"][0]["sha256"])
            self.assertEqual(FETCH.pcm_hash(fresh), provenance["clips"][0]["pcm_sha256"])
            self.assertEqual(["en_us-tree.json", "en_us-audio-tree.json", "en_us-test.tsv", "en_us-test-00.gzpart"], requested)

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

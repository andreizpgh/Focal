"""Tests for the pure parts of scripts/release.py: APK content checks on synthetic zips, the
parsers for apksigner, aapt2 and docs/testing.md, and provenance assembly. Nothing runs Gradle,
git or the Android build-tools.

Run: python3 -m unittest discover -s scripts/tests
"""
import contextlib
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "release.py"
_spec = importlib.util.spec_from_file_location("release", SCRIPT)
release = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(release)

ROOT = SCRIPT.parents[1]
STORED, DEFLATED = zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED
MODEL = {"encoder.int8.onnx": bytes(range(256)) * 40, "silero_vad.onnx": b"vad" * 500,
         "tokens.txt": b"<blk> 0\nhello 1\n" * 20}
LOCK = {"release": "https://example.invalid/v1", "conversion_revision": "abc",
        "files": [{"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                  for name, data in MODEL.items()]}
CERT = "3e407a18880c9277982d6ad6222b39738484a3b768e4d268415eb795709aa3bc"

# apksigner 0.9 (build-tools 37.0.0) on a debug-signed APK, with the JVM's warnings mixed in.
APKSIGNER = f"""WARNING: A restricted method in java.lang.System has been called
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module

Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
V3.0 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V3.0 Signer: certificate SHA-256 digest: {CERT}
V3.0 Signer: certificate SHA-1 digest: 7fc8d49f8e00384fa1cc9ae5381114c8c1749f13
V3.0 Signer: key algorithm: RSA
"""

BADGING = """package: name='app.focal' versionCode='10' versionName='0.2.0' platformBuildVersionName='17'
application-label:'Focal'
native-code: 'arm64-v8a'
"""

TESTING = """# Testing

## What has been verified

- [x] Something already done.

## Phone validation

Pending. Use the exact release APK.

**Long recording and background**

- [ ] 60 minutes of recording with the screen off,
      on both phones.
- [x] A check that has been done.
- [ ] Memory: `adb shell dumpsys meminfo app.focal`.

**Speech**

- [ ] Short replies ("Да", "No") alone.

## Afterwards

- [ ] Not a phone check.
"""


def libraries(abi, compress=STORED):
    return [(f"lib/{abi}/{name}", b"\x7fELF" + name.encode(), compress) for name in release.SPEECH_LIBRARIES]


def model_entries(**compress):
    return [(f"assets/{name}", data, compress.get(name.replace(".", "_"),
                                                 STORED if name.endswith(".onnx") else DEFLATED))
            for name, data in MODEL.items()]


class ApkCheckTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)

    def apk(self, entries):
        path = self.directory / "app.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"\x03\x00manifest")
            archive.writestr("classes.dex", b"dex\n035")
            for name, data, compress in entries:
                archive.writestr(zipfile.ZipInfo(name), data, compress_type=compress)
        return path

    def problems(self, entries):
        with self.assertRaises(release.ReleaseError) as caught:
            release.check_apk(self.apk(entries), LOCK)
        return str(caught.exception)

    def test_a_release_apk_passes_and_reports_what_it_holds(self):
        found = release.check_apk(self.apk(libraries("arm64-v8a") + model_entries()), LOCK)
        self.assertEqual(found["abis"], ["arm64-v8a"])
        self.assertEqual(found["native_libraries"], sorted(release.SPEECH_LIBRARIES))
        self.assertEqual(found["stored_model_files"], ["encoder.int8.onnx", "silero_vad.onnx"])
        self.assertEqual(found["model_files"], sorted(MODEL))

    def test_x86_64_libraries_fail(self):
        message = self.problems(libraries("arm64-v8a") + libraries("x86_64") + model_entries())
        self.assertIn("arm64-v8a, x86_64", message)

    def test_no_native_libraries_fail(self):
        self.assertIn("no ABI", self.problems(model_entries()))

    def test_a_missing_speech_library_fails(self):
        entries = [entry for entry in libraries("arm64-v8a") if "onnxruntime" not in entry[0]]
        self.assertIn("lacks libonnxruntime.so", self.problems(entries + model_entries()))

    def test_a_compressed_native_library_fails(self):
        message = self.problems(libraries("arm64-v8a", DEFLATED) + model_entries())
        self.assertIn("libfocal_speech.so is compressed", message)

    def test_a_compressed_onnx_file_fails(self):
        message = self.problems(libraries("arm64-v8a") + model_entries(encoder_int8_onnx=DEFLATED))
        self.assertIn("assets/encoder.int8.onnx is compressed", message)

    def test_a_compressed_tokens_file_is_fine(self):
        release.check_apk(self.apk(libraries("arm64-v8a") + model_entries(tokens_txt=DEFLATED)), LOCK)

    def test_a_missing_model_file_fails(self):
        entries = [entry for entry in model_entries() if "tokens" not in entry[0]]
        self.assertIn("assets/tokens.txt is missing", self.problems(libraries("arm64-v8a") + entries))

    def test_a_model_file_with_other_bytes_fails(self):
        entries = [(name, data[:-1] + b"!" if "silero" in name else data, compress)
                   for name, data, compress in model_entries()]
        message = self.problems(libraries("arm64-v8a") + entries)
        self.assertIn("assets/silero_vad.onnx does not match the SHA-256", message)

    def test_a_model_file_of_another_size_fails(self):
        entries = [(name, data + b"!" if "silero" in name else data, compress)
                   for name, data, compress in model_entries()]
        self.assertIn("the lock says", self.problems(libraries("arm64-v8a") + entries))

    def test_an_unlocked_onnx_file_fails(self):
        entries = libraries("arm64-v8a") + model_entries() + [("assets/extra.onnx", b"onnx", STORED)]
        self.assertIn("unlocked ONNX files in the APK: assets/extra.onnx", self.problems(entries))

    def test_every_problem_is_reported_at_once(self):
        message = self.problems(libraries("x86_64") + model_entries(encoder_int8_onnx=DEFLATED))
        self.assertIn("lib/ holds x86_64", message)
        self.assertIn("lib/arm64-v8a lacks", message)
        self.assertIn("encoder.int8.onnx is compressed", message)


class ParserTest(unittest.TestCase):
    def test_apksigner_output(self):
        signature = release.parse_apksigner(APKSIGNER)
        self.assertEqual(signature, {"schemes": ["v2", "v3"], "signers": 1, "certificates": [CERT]})
        release.check_signature(signature, CERT)

    def test_apksigner_without_verifies_fails(self):
        with self.assertRaises(release.ReleaseError):
            release.parse_apksigner(APKSIGNER.replace("Verifies\n", "DOES NOT VERIFY\n"))

    def test_another_certificate_or_signer_count_fails(self):
        signature = release.parse_apksigner(APKSIGNER)
        with self.assertRaisesRegex(release.ReleaseError, "is not the expected"):
            release.check_signature(signature, "0" * 64)
        with self.assertRaisesRegex(release.ReleaseError, "exactly one signer"):
            release.check_signature({**signature, "signers": 2}, CERT)
        with self.assertRaisesRegex(release.ReleaseError, "is not the expected"):
            release.check_signature({**signature, "certificates": [CERT, "1" * 64]}, CERT)

    def test_badging(self):
        badging = release.parse_badging(BADGING)
        self.assertEqual(badging, {"package": "app.focal", "version_code": 10, "version_name": "0.2.0",
                                   "native_code": ["arm64-v8a"]})
        release.check_badging(badging, "0.2.0", 10)
        with self.assertRaisesRegex(release.ReleaseError, "version_code is 10, expected 11"):
            release.check_badging(badging, "0.2.0", 11)
        two_abis = release.parse_badging(BADGING.replace("'arm64-v8a'", "'arm64-v8a' 'x86_64'"))
        with self.assertRaisesRegex(release.ReleaseError, "native_code"):
            release.check_badging(two_abis, "0.2.0", 10)

    def test_version_from_the_gradle_script(self):
        text = 'defaultConfig {\n        versionCode = 12\n        versionName = "0.3.1"\n}\n'
        self.assertEqual(release.read_version(text), ("0.3.1", 12))
        with self.assertRaises(release.ReleaseError):
            release.read_version(text + '        versionName = "0.4.0"\n')
        name, code = release.read_version((ROOT / release.GRADLE_BUILD).read_text())
        self.assertRegex(name, r"^\d+\.\d+\.\d+")
        self.assertGreater(code, 9)

    def test_certificate_digests_are_normalised(self):
        colons = ":".join(CERT[i:i + 2] for i in range(0, 64, 2)).upper()
        self.assertEqual(release.normalise_certificate(colons), CERT)
        for wrong in ("", "abc", CERT + "0", "g" * 64):
            with self.assertRaises(release.ReleaseError):
                release.normalise_certificate(wrong)

    def test_passwords_stay_off_the_command_line(self):
        self.assertEqual(release.password_spec("env:KS", {"KS": "secret"}), "env:KS")
        with self.assertRaisesRegex(release.ReleaseError, "not set"):
            release.password_spec("env:KS", {})
        with self.assertRaisesRegex(release.ReleaseError, "never pass:"):
            release.password_spec("pass:secret", {})
        with self.assertRaisesRegex(release.ReleaseError, "unsupported"):
            release.password_spec("secret", {})
        with tempfile.NamedTemporaryFile() as password:
            self.assertEqual(release.password_spec(f"file:{password.name}", {}), f"file:{password.name}")
        with self.assertRaisesRegex(release.ReleaseError, "no such file"):
            release.password_spec("file:/nonexistent/password", {})

    def test_pending_phone_checks(self):
        self.assertEqual(release.pending_phone_checks(TESTING), [
            "Long recording and background: 60 minutes of recording with the screen off, on both phones.",
            "Long recording and background: Memory: `adb shell dumpsys meminfo app.focal`.",
            'Speech: Short replies ("Да", "No") alone.',
        ])

    def test_no_pending_phone_checks_is_an_error(self):
        with self.assertRaisesRegex(release.ReleaseError, "no pending phone checks"):
            release.pending_phone_checks("## Phone validation\n\n- [x] Done.\n")
        with self.assertRaisesRegex(release.ReleaseError, "no '## Phone validation'"):
            release.pending_phone_checks("# Testing\n")

    def test_a_validated_guide_needs_the_explicit_flag(self):
        self.assertEqual(release.pending_phone_checks("## Phone validation\n\n- [x] Done.\n", allow_none=True), [])

    def test_the_committed_testing_guide_parses(self):
        pending = release.pending_phone_checks((ROOT / "docs/testing.md").read_text(encoding="utf-8"),
                                               allow_none=True)
        self.assertTrue(all(":" in item and "\n" not in item and "  " not in item for item in pending))


class ProvenanceTest(unittest.TestCase):
    def facts(self, **overrides):
        model_bytes = json.dumps(LOCK).encode()
        runtime = {"version": "2.0.1", "revision": "af2d8d8", "onnxruntime": "1.28.2", "bytes": 49441341,
                   "sha256": "a8d0" * 16}
        runtime_bytes = json.dumps(runtime).encode()
        facts = dict(
            version="0.2.0", version_code=10, tag="v0.2.0",
            source={"commit": "8999953b" * 5, "tag_commit": "8999953b" * 5, "clean": True},
            apk={"name": "Focal-0.2.0.apk", "bytes": 700447173, "sha256": "8ebf" * 16, "unsigned_sha256": "1a93" * 16,
                 "abis": ["arm64-v8a"], "native_libraries": sorted(release.SPEECH_LIBRARIES),
                 "stored_model_files": ["encoder.int8.onnx"], "model_files": sorted(MODEL)},
            signing={"by_script": True, "schemes": ["v2", "v3"], "certificate_sha256": CERT},
            locks=release.lock_summary(LOCK, model_bytes, runtime, runtime_bytes),
            build={"by_script": True, "command": " ".join(release.GRADLE_COMMAND),
                   "toolchain": {"gradle": "9.8.0", "build_tools": "37.0.0"}},
            checks=["zipalign -c -P 16 4"],
            not_established=["Speech: Quiet speech, a distant phone and a noisy room."],
            created=datetime(2026, 11, 7, 10, 18, 5, tzinfo=timezone.utc),
        )
        facts.update(overrides)
        return facts, model_bytes, runtime_bytes

    def test_provenance_carries_the_schema_and_every_required_field(self):
        facts, model_bytes, runtime_bytes = self.facts()
        provenance = release.build_provenance(**facts)
        self.assertEqual(provenance["schema"], "focal-release/1")
        self.assertEqual([provenance[key] for key in ("version", "version_code", "tag")], ["0.2.0", 10, "v0.2.0"])
        self.assertTrue(provenance["prerelease"])
        self.assertEqual(provenance["created_utc"], "2026-11-07T10:18:05Z")
        self.assertTrue(provenance["source"]["clean"])
        self.assertEqual(provenance["apk"]["name"], "Focal-0.2.0.apk")
        self.assertEqual(provenance["signing"]["certificate_sha256"], CERT)
        self.assertEqual(provenance["model"]["lock_sha256"], hashlib.sha256(model_bytes).hexdigest())
        self.assertEqual(provenance["model"]["files"]["tokens.txt"]["bytes"], len(MODEL["tokens.txt"]))
        self.assertEqual(provenance["runtime"]["lock_sha256"], hashlib.sha256(runtime_bytes).hexdigest())
        self.assertEqual(provenance["runtime"]["sherpa_onnx"], "2.0.1")
        self.assertIn("-Pfocal.requireModel=true", provenance["build"]["command"])
        self.assertEqual(provenance["not_established"], ["Speech: Quiet speech, a distant phone and a noisy room."])
        self.assertEqual(json.loads(json.dumps(provenance, ensure_ascii=False)), provenance)

    def test_provenance_holds_no_local_paths(self):
        facts, _, _ = self.facts()
        strings = []

        def collect(value):
            if isinstance(value, dict):
                [collect(item) for item in value.values()]
            elif isinstance(value, list):
                [collect(item) for item in value]
            elif isinstance(value, str):
                strings.append(value)

        collect(release.build_provenance(**facts))
        self.assertFalse([value for value in strings if value.startswith("/") or str(Path.home()) in value])

    def test_provenance_without_pending_checks_is_refused(self):
        facts, _, _ = self.facts(not_established=[])
        with self.assertRaises(release.ReleaseError):
            release.build_provenance(**facts)

    def test_the_committed_lock_files_summarise(self):
        model_bytes = (ROOT / release.MODEL_LOCK).read_bytes()
        runtime_bytes = (ROOT / release.RUNTIME_LOCK).read_bytes()
        summary = release.lock_summary(json.loads(model_bytes), model_bytes, json.loads(runtime_bytes), runtime_bytes)
        self.assertIn("encoder.int8.onnx", summary["model"]["files"])
        self.assertRegex(summary["runtime"]["aar_sha256"], r"^[0-9a-f]{64}$")

    def test_sha256sums_format(self):
        self.assertEqual(release.sha256sums([("Focal-0.2.0.apk", "ab" * 32), ("p.json", "cd" * 32)]),
                         f"{'ab' * 32}  Focal-0.2.0.apk\n{'cd' * 32}  p.json\n")


class ArgumentTest(unittest.TestCase):
    def parse(self, argv, environ):
        stderr = io.StringIO()
        with mock.patch.dict(release.os.environ, environ, clear=True), \
                contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as caught:
            release.parse_arguments(argv)
        self.assertEqual(caught.exception.code, 2)
        return stderr.getvalue()

    def test_the_expected_certificate_is_required(self):
        self.assertIn("--expected-cert", self.parse(["--signed-apk", "x.apk"], {}))

    def test_signing_needs_a_keystore_and_alias(self):
        self.assertIn("--keystore and --key-alias", self.parse(["--expected-cert", CERT], {}))

    def test_a_password_on_the_command_line_is_refused(self):
        with tempfile.NamedTemporaryFile() as keystore:
            message = self.parse(["--expected-cert", CERT, "--keystore", keystore.name, "--key-alias", "a",
                                  "--ks-pass", "pass:android"], {})
        self.assertIn("never pass:", message)

    def test_a_source_commit_needs_an_existing_apk(self):
        self.assertIn("--source-commit needs", self.parse(["--expected-cert", CERT, "--source-commit", "HEAD"], {}))

    def test_the_signed_apk_mode_needs_no_signing_material(self):
        with mock.patch.dict(release.os.environ, {}, clear=True):
            args = release.parse_arguments(["--signed-apk", "x.apk", "--source-commit", "HEAD",
                                            "--expected-cert", CERT.upper()])
        self.assertEqual(args.expected_cert, CERT)

    def test_a_signed_apk_needs_its_source_commit(self):
        self.assertIn("--signed-apk needs --source-commit", self.parse(["--expected-cert", CERT, "--signed-apk", "x.apk"], {}))


if __name__ == "__main__":
    unittest.main()

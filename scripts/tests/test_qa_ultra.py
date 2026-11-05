import hashlib
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("qa_ultra_draft", Path(__file__).resolve().parents[1] / "qa-local.py")
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)


class UltraQaTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = {name: (name + " synthetic").encode() for name in qa.ULTRA_FILES}
        self.identities = {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                           for name, data in self.assets.items()}
        self.license = b"synthetic notice"
        identity = {"bytes": len(self.license), "sha256": hashlib.sha256(self.license).hexdigest()}
        license_patch = patch.object(qa, "ULTRA_LICENSE", identity)
        self.addCleanup(license_patch.stop)
        license_patch.start()
        self.apk = self.root / "app.apk"

    def write_apk(self, *, missing=None, corrupt=None, compressed=None, missing_lib=None):
        with zipfile.ZipFile(self.apk, "w") as apk:
            for name, data in {**self.assets, qa.ULTRA_LICENSE_ASSET: self.license}.items():
                if name == missing:
                    continue
                if name == corrupt:
                    data = b"x" * len(data)
                method = zipfile.ZIP_DEFLATED if name == compressed else zipfile.ZIP_STORED
                apk.writestr("assets/" + name, data, compress_type=method)
            for abi in ("arm64-v8a", "x86_64"):
                for name in qa.ULTRA_LIBRARIES:
                    path = f"lib/{abi}/{name}"
                    if path != missing_lib:
                        apk.writestr(path, b"synthetic")

    def test_apk_checks_all_model_files_compression_and_both_abis(self):
        self.write_apk()
        qa.verify_ultra_apk(self.apk, self.identities)
        self.write_apk(missing="tokens.txt")
        with self.assertRaisesRegex(qa.QAError, "exactly one Ultra asset"):
            qa.verify_ultra_apk(self.apk, self.identities)
        self.write_apk(corrupt="encoder.int8.onnx")
        with self.assertRaisesRegex(qa.QAError, "SHA-256 mismatch"):
            qa.verify_ultra_apk(self.apk, self.identities)
        self.write_apk(compressed="joiner.int8.onnx")
        with self.assertRaisesRegex(qa.QAError, "uncompressed"):
            qa.verify_ultra_apk(self.apk, self.identities)
        self.write_apk(missing_lib="lib/x86_64/libonnxruntime.so")
        with self.assertRaisesRegex(qa.QAError, "runtime library missing"):
            qa.verify_ultra_apk(self.apk, self.identities)

    def test_ultra_license_is_required_only_in_ultra_apk_and_must_match(self):
        self.write_apk(missing=qa.ULTRA_LICENSE_ASSET)
        with self.assertRaisesRegex(qa.QAError, "exactly one Ultra asset: ultra-licenses.txt"):
            qa.verify_ultra_apk(self.apk, self.identities)
        self.write_apk(corrupt=qa.ULTRA_LICENSE_ASSET)
        with self.assertRaisesRegex(qa.QAError, "SHA-256 mismatch: ultra-licenses.txt"):
            qa.verify_ultra_apk(self.apk, self.identities)
        self.write_apk()
        with self.assertRaisesRegex(qa.QAError, "Non-Ultra APK unexpectedly contains the Ultra license asset"):
            qa.verify_apks(self.apk, self.apk, {}, {})

    def test_source_rejects_extra_and_changed_files_and_bad_aar(self):
        folder = self.root / "build/stt/parakeet-ultra-20261105/model"
        folder.mkdir(parents=True)
        for name, data in self.assets.items():
            (folder / name).write_bytes(data)
        aar = self.root / "build/dependencies/sherpa-onnx-2.0.1.aar"
        aar.parent.mkdir(parents=True)
        aar.write_bytes(b"synthetic aar")
        sha = hashlib.sha256(aar.read_bytes()).hexdigest()
        with patch.object(qa, "ROOT", self.root), patch.object(qa, "ULTRA_FILES", self.identities), patch.object(qa, "ULTRA_AAR_SHA256", sha):
            qa.verify_ultra_model_source()
            (folder / "unrelated.txt").write_text("private")
            with self.assertRaisesRegex(qa.QAError, "exactly the four"):
                qa.verify_ultra_model_source()
            (folder / "unrelated.txt").unlink()
            (folder / "tokens.txt").write_bytes(b"x" * len(self.assets["tokens.txt"]))
            with self.assertRaisesRegex(qa.QAError, "missing or corrupt"):
                qa.verify_ultra_model_source()
            (folder / "tokens.txt").write_bytes(self.assets["tokens.txt"])
            aar.write_bytes(b"wrong aar")
            with self.assertRaisesRegex(qa.QAError, "runtime AAR"):
                qa.verify_ultra_model_source()

    def test_opt_in_extends_full_suite_without_research_batch(self):
        base = self.root / "app/src/androidTest/java/app/focal"
        alternative = self.root / "app/src/alternativeTest/java/app/focal"
        extra = self.root / "app/src/ultraTest/java/app/focal"
        research = self.root / "app/src/parakeetPreviewTest/java/app/focal"
        for folder in (base, alternative, extra, research):
            folder.mkdir(parents=True)
        (base / "BaseTest.kt").write_text("package app.focal\nclass BaseTest { @Test fun required() {} }")
        (alternative / "AlternativeTest.kt").write_text("package app.focal\nclass AlternativeTest { @Test fun required() {} }")
        (extra / "UltraTest.kt").write_text("package app.focal\nclass UltraTest { @Test fun required() {} }")
        (research / "PrivateBatch.kt").write_text("package app.focal\nclass PrivateBatch { @Test fun privateBatch() {} }")
        base_test = ("app.focal.BaseTest", "required")
        alternative_test = ("app.focal.AlternativeTest", "required")
        extra_test = ("app.focal.UltraTest", "required")
        with patch.object(qa, "ROOT", self.root), patch.object(qa, "MODEL_TESTS", {base_test}), patch.object(qa, "ULTRA_TESTS", {alternative_test, extra_test}):
            self.assertEqual({base_test}, qa.expected_instrumentation_tests())
            self.assertEqual({base_test, alternative_test, extra_test}, qa.expected_instrumentation_tests(ultra=True))
            (alternative / "AlternativeTest.kt").unlink()
            with self.assertRaisesRegex(qa.QAError, "missing required extraction or real-preview tests"):
                qa.expected_instrumentation_tests(ultra=True)
            (alternative / "AlternativeTest.kt").write_text("package app.focal\nclass AlternativeTest { @Test fun required() {} }")
            (extra / "UltraTest.kt").unlink()
            with self.assertRaisesRegex(qa.QAError, "missing required extraction or real-preview tests"):
                qa.expected_instrumentation_tests(ultra=True)

    def test_flag_controls_both_debug_and_release_build_without_changing_defaults(self):
        normal = qa.build_tasks(release_checks=True)
        ultra = qa.build_tasks(ultra=True, release_checks=True)
        self.assertEqual(["-PfocalUltra=true"] + normal, ultra)
        parser = qa.argument_parser()
        required = ["--serial", "emulator-5554", "--dedicated-test-avd"]
        self.assertFalse(parser.parse_args(required).ultra)
        self.assertTrue(parser.parse_args(required + ["--ultra"]).ultra)

    def test_installed_apk_must_match_exact_artifact(self):
        wanted = "a" * 64
        def adb(*args, **_kwargs):
            if args[1:3] == ("pm", "path"):
                return "package:/data/app/example/base.apk"
            return wanted + "  /data/app/example/base.apk"
        self.assertEqual(wanted, qa.verify_installed_apk(adb, "app.focal", wanted))
        with self.assertRaisesRegex(qa.QAError, "differs"):
            qa.verify_installed_apk(adb, "app.focal", "b" * 64)


if __name__ == "__main__":
    unittest.main()

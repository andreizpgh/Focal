import importlib.util
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("qa_local", Path(__file__).resolve().parents[1] / "qa-local.py")
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)


def instrumentation(cases, *, code=0, final=-1, announced=None):
    total = len(cases) if announced is None else announced
    parts = []
    for classname, name in cases:
        for status in (1, code):
            parts.extend((f"INSTRUMENTATION_STATUS: class={classname}",
                          f"INSTRUMENTATION_STATUS: test={name}",
                          f"INSTRUMENTATION_STATUS: numtests={total}",
                          f"INSTRUMENTATION_STATUS_CODE: {status}"))
    parts.append(f"INSTRUMENTATION_CODE: {final}")
    return "\n".join(parts)


class FakeAdb:
    def __init__(self, values):
        self.values = dict(values)
        self.commands = []
        self.fail_once = None

    def __call__(self, *args):
        self.commands.append(args)
        operation, key = args[2], args[4]
        if self.fail_once == (operation, key):
            self.fail_once = None
            raise qa.QAError("synthetic adb failure")
        if operation == "get":
            return self.values.get(key, "null")
        if operation == "put":
            self.values[key] = args[5]
        elif operation == "delete":
            self.values.pop(key, None)
        else:
            raise AssertionError(args)
        return ""


class ParserTests(unittest.TestCase):
    def test_complete_suite_and_required_model_checks(self):
        cases = sorted(qa.MODEL_TESTS) + [("app.focal.OtherTest", "alsoRan")]
        outcomes, problems = qa.parse_instrumentation(instrumentation(cases), set(cases))
        self.assertEqual(len(cases), len(outcomes))
        self.assertEqual([], problems)

    def test_fails_closed_for_missing_skip_crash_and_count_mismatch(self):
        cases = sorted(qa.MODEL_TESTS)
        samples = [
            instrumentation(cases[:-1]),
            instrumentation(cases, code=-4),
            instrumentation(cases, code=-3),
            instrumentation(cases, code=-2),
            instrumentation(cases, final=0),
            instrumentation(cases, announced=100),
            instrumentation(cases).replace("INSTRUMENTATION_CODE: -1", "Process crashed."),
            instrumentation(cases) + "\nINSTRUMENTATION_RESULT: shortMsg=Process crashed",
            instrumentation(cases + [cases[0]]),
        ]
        for output in samples:
            with self.subTest(output=output[-100:]):
                self.assertTrue(qa.parse_instrumentation(output, set(cases))[1])
        self.assertTrue(qa.parse_instrumentation(instrumentation(cases), set(cases) | {("x", "absent")})[1])

    def test_source_suite_contains_all_required_checks(self):
        cases = qa.expected_instrumentation_tests()
        self.assertTrue(qa.MODEL_TESTS <= cases)
        self.assertGreater(len(cases), len(qa.MODEL_TESTS))

    def test_junit_marks_crash_and_failures(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as folder:
            path = Path(folder) / "results.xml"
            qa.write_junit(path, {("example.Class", "test"): -2}, ["runner crash"])
            text = path.read_text(encoding="utf-8")
            self.assertIn('failures="2"', text)
            self.assertIn("runner crash", text)

    def test_apk_model_and_fixture_are_verified_before_install(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as folder:
            app = Path(folder) / "app.apk"
            tests = Path(folder) / "test.apk"
            content = b"synthetic model"
            model = {"repository": "fixture", "revision": "r", "filename": qa.MODEL_NAME,
                     "sha256": hashlib.sha256(content).hexdigest()}
            presence_content = b"synthetic vad"
            presence = {"filename": qa.PRESENCE_NAME, "bytes": len(presence_content),
                        "sha256": hashlib.sha256(presence_content).hexdigest()}
            def app_with_presence(data):
                with zipfile.ZipFile(app, "w") as apk:
                    apk.writestr("assets/model-provenance.json", json.dumps(model))
                    apk.writestr("assets/" + qa.MODEL_NAME, content)
                    if data is not None:
                        apk.writestr("assets/" + qa.PRESENCE_NAME, data)
            app_with_presence(presence_content)
            with zipfile.ZipFile(tests, "w") as apk:
                apk.writestr("assets/jfk.wav", b"fixture")
            qa.verify_apks(app, tests, model, presence)
            with self.assertRaisesRegex(qa.QAError, "provenance"):
                qa.verify_apks(app, tests, {**model, "sha256": "0" * 64}, presence)
            app_with_presence(None)
            with self.assertRaisesRegex(qa.QAError, "missing the speech presence model"):
                qa.verify_apks(app, tests, model, presence)
            app_with_presence(b"short")
            with self.assertRaisesRegex(qa.QAError, "speech presence model size"):
                qa.verify_apks(app, tests, model, presence)
            app_with_presence(b"x" * len(presence_content))
            with self.assertRaisesRegex(qa.QAError, "speech presence model SHA-256"):
                qa.verify_apks(app, tests, model, presence)
            app_with_presence(presence_content)
            with zipfile.ZipFile(tests, "w"):
                pass
            with self.assertRaisesRegex(qa.QAError, "speech fixture"):
                qa.verify_apks(app, tests, model, presence)

    def test_presence_source_rejects_missing_corrupt_and_changed_lock(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "models").mkdir()
            assets = root / "app/src/main/assets"
            assets.mkdir(parents=True)
            content = b"synthetic vad"
            digest = hashlib.sha256(content).hexdigest()
            lock = {"repository": qa.PRESENCE_REPOSITORY, "revision": qa.PRESENCE_REVISION,
                    "filename": qa.PRESENCE_NAME, "bytes": len(content), "sha256": digest}
            lock_path = root / "models/speech-presence.json"
            lock_path.write_text(json.dumps(lock))
            model_path = assets / qa.PRESENCE_NAME
            with patch.object(qa, "ROOT", root), patch.object(qa, "PRESENCE_BYTES", len(content)), patch.object(qa, "PRESENCE_SHA256", digest):
                with self.assertRaisesRegex(qa.QAError, "missing or differs"):
                    qa.verify_presence_model_source()
                model_path.write_bytes(content)
                self.assertEqual(lock, qa.verify_presence_model_source())
                model_path.write_bytes(b"x" * len(content))
                with self.assertRaisesRegex(qa.QAError, "missing or differs"):
                    qa.verify_presence_model_source()
                lock_path.write_text(json.dumps({**lock, "revision": "changed"}))
                with self.assertRaisesRegex(qa.QAError, "model lock"):
                    qa.verify_presence_model_source()


class PlanningAndDeviceTests(unittest.TestCase):
    def test_serial_is_always_explicit_and_gradle_is_platform_specific(self):
        self.assertEqual(["adb", "-s", "emulator-5554", "shell", "settings", "get"],
                         qa.adb_command("adb", "emulator-5554", "shell", "settings", "get"))
        self.assertTrue(qa.gradle_command([":core:test"], windows=False)[0].endswith("gradlew"))
        with patch.dict("os.environ", {"COMSPEC": "cmd.exe"}):
            command = qa.gradle_command([":core:test"], windows=True)
        self.assertEqual(["cmd.exe", "/d", "/v:off", "/s", "/c"], command[:5])
        self.assertIn("%FOCAL_QA_GRADLE_WRAPPER%", command[5])
        self.assertIn(":core:test", command[5])
        with patch.object(qa, "ROOT", Path("/a & checkout/%NAME%!")):
            command = qa.gradle_command([":core:test"], windows=True)
            environment = qa.gradle_environment()
        self.assertTrue(command[5].startswith('""%FOCAL_QA_GRADLE_WRAPPER%"'))
        self.assertNotIn("&", command[5])
        self.assertIn("& checkout/%NAME%!", environment["FOCAL_QA_GRADLE_WRAPPER"])

    def test_fake_adb_restores_all_animation_settings_after_failure(self):
        original = dict(zip(qa.ANIMATIONS, ["0.5", "2", "null"]))
        fake = FakeAdb({k: v for k, v in original.items() if v != "null"})
        with self.assertRaisesRegex(RuntimeError, "test failure"):
            with qa.normal_animations(fake):
                self.assertEqual(["1", "1", "1"], [fake.values[k] for k in qa.ANIMATIONS])
                raise RuntimeError("test failure")
        self.assertEqual({k: v for k, v in original.items() if v != "null"}, fake.values)

    def test_fake_adb_restores_after_partial_setup(self):
        original = dict(zip(qa.ANIMATIONS, ["0", "1.5", "2"]))
        fake = FakeAdb(original)
        fake.fail_once = ("put", qa.ANIMATIONS[1])
        captured = []
        with self.assertRaises(qa.QAError):
            with qa.normal_animations(fake, captured.append):
                self.fail("setup should have failed")
        self.assertEqual(original, fake.values)
        self.assertEqual([original], captured)

    def test_adb_timeout_is_reported_for_bounded_cleanup(self):
        import subprocess
        with patch.object(qa.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 10)):
            with self.assertRaisesRegex(qa.QAError, "timed out"):
                qa.run(["adb", "-s", "emulator-5554", "get-state"], timeout=10)

    def test_installs_can_use_a_longer_bounded_timeout(self):
        with patch.object(qa, "run", return_value="Success") as execute:
            qa.device_call("adb", "emulator-5554", "install", "-r", "app.apk", timeout=120)
        self.assertEqual(120, execute.call_args.kwargs["timeout"])
        self.assertEqual(["adb", "-s", "emulator-5554", "install", "-r", "app.apk"], execute.call_args.args[0])

    def test_installed_model_must_match_even_if_apk_was_verified(self):
        calls = []
        expected = "a" * 64
        def fake_adb(*args, **kwargs):
            calls.append(args)
            if args[1:3] == ("am", "start"):
                return "Starting"
            if len(calls) == 2:
                raise qa.QAError("No such file or directory")
            return expected + "  files/" + qa.MODEL_NAME
        self.assertEqual(expected, qa.verify_installed_model(fake_adb, expected, poll_seconds=0))
        self.assertEqual(3, len(calls))
        def different_model(*args, **kwargs):
            return "b" * 64 + "  files/" + qa.MODEL_NAME
        with self.assertRaisesRegex(qa.QAError, "different speech model"):
            qa.verify_installed_model(different_model, expected, poll_seconds=0)

    def test_windows_wrapper_rejects_unsafe_paths_before_device_access(self):
        qa.validate_windows_paths("C:/checkout with spaces", "C:/jdk 17", windows=True)
        for root, java in (("C:/work&other", "C:/jdk"), ("C:/work", "C:/jdk%VERSION%"), ("C:/work!", None)):
            with self.subTest(root=root, java=java), self.assertRaises(qa.QAError):
                qa.validate_windows_paths(root, java, windows=True)

    def test_dedicated_confirmation_and_repeat_bounds_are_required(self):
        parser = qa.argument_parser()
        self.assertEqual(20, parser.parse_args(["--serial", "emulator-5554", "--dedicated-test-avd", "--repeat", "20"]).repeat)
        with patch("sys.stderr"):
            for args in (["--serial", "emulator-5554"],
                         ["--serial", "emulator-5554", "--dedicated-test-avd", "--repeat", "21"]):
                with self.assertRaises(SystemExit):
                    parser.parse_args(args)


if __name__ == "__main__":
    unittest.main()

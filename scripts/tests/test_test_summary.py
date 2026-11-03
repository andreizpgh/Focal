"""Exercise the CI model gate against synthetic Android JUnit reports."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).resolve().parents[1] / "test-summary.py"
MODEL_CASES = (
    ("app.focal.NativeWhisperTest", "cancellationIsTerminalAndCloseIsIdempotent"),
    ("app.focal.NativeWhisperTest", "invalidLanguageAndPcmAreRejectedBeforeInference"),
    ("app.focal.NativeWhisperTest", "closingAndCancellingOneEngineFromDifferentThreadsIsSafe"),
    ("app.focal.SpeechWorkflowTest", "realModelRecognizesSpeechThroughAndroidJni"),
    ("app.focal.SpeechWorkflowTest", "captureContinuesWithoutAnActivityAndCanPauseAndFinish"),
    ("app.focal.NativeWhisperTest", "constantPcmProducesNoSegmentsInAnyLanguageEvenWithContext"),
    ("app.focal.SpeechWorkflowTest", "quietVaryingSpeechStillReachesTheDecoder"),
    ("app.focal.SpeechWorkflowTest", "recoveryOfConstantAudioKeepsOriginalAndManualEditsWithoutAddingPassages"),
)


class ModelGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.reports = self.root / "app/build/outputs/androidTest-results/connected/device"

    def report(self, cases, filename="TEST-device.xml"):
        self.reports.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", tests=str(len(cases)), failures="0", errors="0", skipped="0")
        for classname, name, outcome in cases:
            case = ET.SubElement(suite, "testcase", classname=classname, name=name)
            if outcome:
                ET.SubElement(case, outcome, message="synthetic result")
        ET.ElementTree(suite).write(self.reports / filename, encoding="utf-8", xml_declaration=True)

    def run_summary(self, require=True):
        environment = os.environ.copy()
        environment.pop("GITHUB_STEP_SUMMARY", None)
        return subprocess.run([sys.executable, str(SCRIPT)] + (["--require-model-tests"] if require else []),
                              cwd=self.root, env=environment, text=True, capture_output=True, check=False)

    def test_default_summary_remains_compatible_without_android_results(self):
        self.assertEqual(self.run_summary(require=False).returncode, 0)
        self.assertIn("Android | Not run", self.run_summary(require=False).stdout)

    def test_model_gate_rejects_missing_reports_and_missing_cases(self):
        self.assertNotEqual(self.run_summary().returncode, 0)
        self.report([(c, n, None) for c, n in MODEL_CASES[:-1]])
        result = self.run_summary()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(MODEL_CASES[-1][1], result.stdout)

    def test_all_five_required_cases_must_pass(self):
        self.report([(c, n, None) for c, n in MODEL_CASES])
        self.assertEqual(self.run_summary().returncode, 0)

    def test_skips_failures_errors_and_wrong_class_fail_closed(self):
        for outcome in ("skipped", "failure", "error"):
            with self.subTest(outcome=outcome):
                self.report([(c, n, outcome if i == 0 else None) for i, (c, n) in enumerate(MODEL_CASES)])
                self.assertNotEqual(self.run_summary().returncode, 0)
        self.report([("wrong.Class" if i == 0 else c, n, None) for i, (c, n) in enumerate(MODEL_CASES)])
        self.assertNotEqual(self.run_summary().returncode, 0)

    def test_a_skipped_duplicate_on_another_device_cannot_be_hidden(self):
        self.report([(c, n, None) for c, n in MODEL_CASES])
        self.report([(MODEL_CASES[0][0], MODEL_CASES[0][1], "skipped")], "TEST-other.xml")
        self.assertNotEqual(self.run_summary().returncode, 0)


if __name__ == "__main__":
    unittest.main()

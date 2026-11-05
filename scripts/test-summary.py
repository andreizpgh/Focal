#!/usr/bin/env python3
"""Publish test outcomes in the CI summary and public annotations."""
import argparse
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def escape(value):
    return str(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--require-model-tests", action="store_true",
                    help="Fail unless every real-model Android check ran and passed")
args = parser.parse_args()

required_model_tests = {
    ("app.focal.NativeWhisperTest", "cancellationIsTerminalAndCloseIsIdempotent"),
    ("app.focal.NativeWhisperTest", "invalidLanguageAndPcmAreRejectedBeforeInference"),
    ("app.focal.NativeWhisperTest", "closingAndCancellingOneEngineFromDifferentThreadsIsSafe"),
    ("app.focal.SpeechWorkflowTest", "realModelRecognizesSpeechThroughAndroidJni"),
    ("app.focal.SpeechWorkflowTest", "captureContinuesWithoutAnActivityAndCanPauseAndFinish"),
    ("app.focal.NativeWhisperTest", "constantPcmProducesNoSegmentsInAnyLanguageEvenWithContext"),
    ("app.focal.SpeechWorkflowTest", "quietVaryingSpeechStillReachesTheDecoder"),
    ("app.focal.SpeechWorkflowTest", "recoveryOfConstantAudioKeepsOriginalAndManualEditsWithoutAddingPassages"),
}
model_outcomes = {test: [] for test in required_model_tests}
rows = []
for label, root in (("Core", Path("core/build/test-results/test")),
                    ("App JVM", Path("app/build/test-results/testDebugUnitTest")),
                    ("Android", Path("app/build/outputs/androidTest-results/connected"))):
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    files = list(root.rglob("TEST-*.xml")) if root.exists() else []
    for file in files:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.iter("testcase"):
            identity = (case.get("classname"), case.get("name"))
            if label == "Android" and identity in model_outcomes:
                model_outcomes[identity].append(
                    not any(case.find(kind) is not None for kind in ("failure", "error", "skipped")))
            for kind in ("failure", "error"):
                for failure in case.findall(kind):
                    message = (failure.get("message") or failure.text or "No detail")[:2500]
                    print(f"::error title={escape(label + ': ' + case.get('name', '?'))}::{escape(message)}")
    result = (f"{totals['tests']} tests, {totals['failures']} failed, "
              f"{totals['errors']} errors, {totals['skipped']} skipped") if files else "Not run"
    print(f"::notice title={label} tests::{result}")
    rows.append(f"| {label} | {result} |")

summary = "| Suite | Result |\n| --- | --- |\n" + "\n".join(rows) + "\n"
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
        output.write(summary)
else:
    print(summary)

if args.require_model_tests:
    incomplete = [f"{classname}.{name}" for (classname, name), outcomes in sorted(model_outcomes.items())
                  if not outcomes or not all(outcomes)]
    if incomplete:
        for test in incomplete:
            print(f"::error title=Required model test missing or unsuccessful::{escape(test)}")
        raise SystemExit(1)

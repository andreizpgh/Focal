#!/usr/bin/env python3
"""Publish test outcomes in the CI summary and public annotations."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def escape(value):
    return str(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


rows = []
for label, root in (("Core", Path("core/build/test-results/test")),
                    ("Android", Path("app/build/outputs/androidTest-results/connected"))):
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    files = list(root.rglob("TEST-*.xml")) if root.exists() else []
    for file in files:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.iter("testcase"):
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

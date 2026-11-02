#!/usr/bin/env python3
"""Build once and run the complete Android suite on a dedicated local emulator."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from contextlib import contextmanager
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MODEL_TESTS = {
    ("app.focal.NativeWhisperTest", "cancellationIsTerminalAndCloseIsIdempotent"),
    ("app.focal.NativeWhisperTest", "invalidLanguageAndPcmAreRejectedBeforeInference"),
    ("app.focal.NativeWhisperTest", "closingAndCancellingOneEngineFromDifferentThreadsIsSafe"),
    ("app.focal.SpeechWorkflowTest", "realModelRecognizesSpeechThroughAndroidJni"),
    ("app.focal.SpeechWorkflowTest", "captureContinuesWithoutAnActivityAndCanPauseAndFinish"),
}
ANIMATIONS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
MODEL_NAME = "ggml-base-q5_1.bin"


class QAError(Exception):
    pass


def sha_stream(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def file_hash(path):
    with path.open("rb") as stream:
        return sha_stream(stream)


def run(args, *, check=True, timeout=30, env=None):
    try:
        result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace", check=False, timeout=timeout, env=env)
    except subprocess.TimeoutExpired as error:
        raise QAError(f"{Path(args[0]).name} timed out after {timeout} seconds") from error
    except OSError as error:
        raise QAError(f"Could not run {Path(args[0]).name}: {error}") from error
    if check and result.returncode != 0:
        raise QAError(f"{Path(args[0]).name} failed (exit {result.returncode}): {result.stdout.strip()}")
    return result.stdout.strip()


def adb_command(adb, serial, *args):
    return [adb, "-s", serial, *args]


def device_call(adb, serial, *args, timeout=10):
    return run(adb_command(adb, serial, *args), timeout=timeout)


def validate_windows_paths(root, java_home, windows=None):
    if windows is None:
        windows = os.name == "nt"
    if windows and any(re.search(r"[&|<>^%!()]", str(path)) for path in (root, java_home) if path):
        raise QAError("The Windows Gradle wrapper cannot safely use cmd metacharacters in the checkout or JAVA_HOME path. Move them to paths without & | < > ^ % ! ( ).")


@contextmanager
def normal_animations(adb, on_read=lambda _originals: None):
    originals = {}
    try:
        for key in ANIMATIONS:
            value = adb("shell", "settings", "get", "global", key)
            if value != "null" and not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value):
                raise QAError(f"Unexpected animation setting for {key}; refusing to change it.")
            originals[key] = value
        # Persist before the first write so a disconnected emulator can be repaired.
        on_read(dict(originals))
        for key in ANIMATIONS:
            adb("shell", "settings", "put", "global", key, "1")
        yield dict(originals)
    finally:
        failures = []
        for key, value in originals.items():
            try:
                if value == "null":
                    adb("shell", "settings", "delete", "global", key)
                else:
                    adb("shell", "settings", "put", "global", key, value)
            except QAError as error:
                failures.append(str(error))
        if failures:
            raise QAError("Could not restore animation settings: " + "; ".join(failures))


def gradle_command(tasks, windows=None):
    if windows is None:
        windows = os.name == "nt"
    wrapper = ROOT / ("gradlew.bat" if windows else "gradlew")
    if not windows:
        return [str(wrapper), *tasks, "--console=plain"]
    # Expand a single environment variable inside quotes. cmd does not expand
    # percent sequences a second time inside its value; delayed expansion is off.
    command = '"%FOCAL_QA_GRADLE_WRAPPER%" ' + subprocess.list2cmdline([*tasks, "--console=plain"])
    return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/v:off", "/s", "/c", '"' + command + '"']


def gradle_environment():
    env = os.environ.copy()
    env["FOCAL_QA_GRADLE_WRAPPER"] = str(ROOT / "gradlew.bat")
    return env


def host_memory_bytes():
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes

        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", wintypes.DWORD), ("load", wintypes.DWORD)] + [
                (name, ctypes.c_ulonglong) for name in ("physical_total", "physical_available", "page_total",
                                                        "page_available", "virtual_total", "virtual_available", "extended_available")]
        info = MemoryStatus()
        info.length = ctypes.sizeof(info)
        return int(info.physical_total) if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(info)) else None
    try:
        return os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (ValueError, OSError, AttributeError):
        return None


def source_snapshot():
    head = run(["git", "rev-parse", "HEAD"])
    dirty = bool(run(["git", "status", "--porcelain", "--untracked-files=normal"]))
    submodules = run(["git", "submodule", "status", "--recursive"])
    if any(line.startswith(("-", "+", "U")) for line in submodules.splitlines()):
        raise QAError("Initialize submodules at the recorded commits before running QA.")
    for line in submodules.splitlines():
        path = line.strip().split()[1]
        if run(["git", "-C", str(ROOT / path), "status", "--porcelain", "--untracked-files=normal"]):
            raise QAError("Submodule worktrees must be clean so the manifest identifies their source.")
    # Capture dirty tracked and untracked bytes without recording file names or contents.
    digest = hashlib.sha256()
    diff = subprocess.run(["git", "diff", "--binary", "HEAD", "--"], cwd=ROOT, stdout=subprocess.PIPE, check=True).stdout
    digest.update(diff)
    names = subprocess.run(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT, stdout=subprocess.PIPE, check=True).stdout
    for name in sorted(filter(None, names.split(b"\0"))):
        path = ROOT / os.fsdecode(name)
        digest.update(name + b"\0")
        if path.is_symlink():
            digest.update(os.fsencode(os.readlink(path)))
        elif path.is_file():
            with path.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
    return {"head": head, "dirty": dirty, "source_change_sha256": digest.hexdigest(),
            "submodule_commits": [line.strip().split()[0] for line in submodules.splitlines()]}


def verify_model_source():
    lock = json.loads((ROOT / "models/model.json").read_text(encoding="utf-8"))
    assets = ROOT / "app/src/main/assets"
    provenance = json.loads((assets / "model-provenance.json").read_text(encoding="utf-8"))
    if any(provenance.get(key) != lock.get(key) for key in ("repository", "revision", "filename", "sha256")):
        raise QAError("Bundled model provenance does not match models/model.json.")
    model = assets / MODEL_NAME
    if lock.get("filename") != MODEL_NAME or model.stat().st_size != provenance.get("bytes") or file_hash(model) != lock.get("sha256"):
        raise QAError("Bundled model size or SHA-256 does not match the committed model lock.")
    return lock


def verify_apks(app_apk, test_apk, model):
    with zipfile.ZipFile(app_apk) as apk:
        info = json.loads(apk.read("assets/model-provenance.json"))
        if any(info.get(key) != model.get(key) for key in ("repository", "revision", "filename", "sha256")):
            raise QAError("Built APK model provenance does not match the lock.")
        with apk.open("assets/" + MODEL_NAME) as stream:
            actual = sha_stream(stream)
        if actual != model["sha256"]:
            raise QAError("Built APK model SHA-256 does not match the lock.")
    with zipfile.ZipFile(test_apk) as apk:
        if "assets/jfk.wav" not in apk.namelist():
            raise QAError("The instrumentation APK is missing its real speech fixture.")


def verify_installed_model(adb, expected, *, deadline_seconds=180, poll_seconds=2):
    # install -r preserves an imported/old model, so APK verification alone
    # does not establish which model the application will actually use.
    adb("shell", "am", "start", "-n", "app.focal/.MainActivity", timeout=30)
    deadline = time.monotonic() + deadline_seconds
    while True:
        try:
            output = adb("shell", "run-as", "app.focal", "sha256sum", "files/" + MODEL_NAME, timeout=60)
            match = re.match(r"^([a-fA-F0-9]{64})\s", output)
            if not match:
                raise QAError("Unexpected installed-model SHA-256 output.")
            actual = match.group(1).lower()
            if actual != expected:
                raise QAError("The installed app has a different speech model. Use a fresh dedicated AVD or replace the model explicitly; no app data was deleted.")
            return actual
        except QAError as error:
            if "No such file or directory" not in str(error):
                raise
            if time.monotonic() >= deadline:
                raise QAError("The bundled speech model did not finish installing on the emulator.") from error
            time.sleep(poll_seconds)


def expected_instrumentation_tests():
    root = ROOT / "app/src/androidTest/java"
    expected = set()
    for path in root.rglob("*"):
        if path.suffix not in (".kt", ".java"):
            continue
        text = path.read_text(encoding="utf-8")
        annotations = re.findall(r"@Test\b", text)
        if not annotations:
            continue
        if path.suffix != ".kt":
            raise QAError("Java instrumentation tests need explicit discovery support before running QA.")
        package = re.search(r"(?m)^package\s+([\w.]+)\s*$", text)
        names = re.findall(r"@Test(?:\s*\([^)]*\))?\s+fun\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", text)
        if not package or len(names) != len(annotations) or not re.search(r"\bclass\s+" + re.escape(path.stem) + r"\b", text):
            raise QAError(f"Cannot safely discover instrumentation tests in {path.name}.")
        for name in names:
            case = (package.group(1) + "." + path.stem, name)
            if case in expected:
                raise QAError("Duplicate instrumentation test identity.")
            expected.add(case)
    if not MODEL_TESTS <= expected:
        raise QAError("The source suite is missing required real-model tests.")
    return expected


def parse_instrumentation(output, expected=None):
    """Require every announced case to finish exactly once with success."""
    status = {}
    starts, outcomes = set(), {}
    announced = set()
    final_codes = []
    problems = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.rsplit(":", 1)[1].strip())
                if "numtests" in status:
                    announced.add(int(status["numtests"]))
            except ValueError:
                problems.append("Malformed status code or test count")
                status = {}
                continue
            case = (status.get("class"), status.get("test"))
            if code == 1:
                if not all(case) or case in starts:
                    problems.append("Missing or duplicate test start")
                starts.add(case)
            elif code in (0, -1, -2, -3, -4):
                if not all(case) or case not in starts or case in outcomes:
                    problems.append("Missing, unmatched, or duplicate test result")
                outcomes[case] = code
                if code != 0:
                    problems.append(f"Unsuccessful test: {case[0]}.{case[1]} (status {code})")
            else:
                problems.append(f"Unexpected instrumentation status {code}")
            status = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            try:
                final_codes.append(int(line.rsplit(":", 1)[1].strip()))
            except ValueError:
                problems.append("Malformed final instrumentation code")
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_RESULT: longMsg=")):
            problems.append("Instrumentation reported a runner failure")
    if final_codes != [-1]:
        problems.append("Instrumentation did not finish successfully")
    if len(announced) != 1 or next(iter(announced), 0) != len(starts) or len(starts) != len(outcomes):
        problems.append("Announced, started, and completed test counts differ")
    missing = MODEL_TESTS - {case for case, code in outcomes.items() if code == 0}
    if missing:
        problems.append("Missing or unsuccessful real-model checks: " + ", ".join(f"{a}.{b}" for a, b in sorted(missing)))
    if expected is not None and set(outcomes) != expected:
        problems.append("Executed test identities differ from the complete source suite")
    return outcomes, problems


def write_junit(path, outcomes, problems):
    suite = ET.Element("testsuite", name="Focal Android instrumentation", tests=str(len(outcomes) + bool(problems)),
                       failures=str(sum(code != 0 for code in outcomes.values()) + bool(problems)))
    for (classname, name), code in sorted(outcomes.items(), key=lambda item: str(item[0])):
        case = ET.SubElement(suite, "testcase", classname=classname or "unknown", name=name or "unknown")
        if code != 0:
            ET.SubElement(case, "failure", message=f"Instrumentation status {code}")
    if problems:
        case = ET.SubElement(suite, "testcase", classname="runner", name="complete_suite")
        ET.SubElement(case, "failure", message="Incomplete or failed instrumentation").text = "\n".join(problems)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit emulator serial, e.g. emulator-5554")
    parser.add_argument("--dedicated-test-avd", action="store_true", required=True,
                        help="Confirm this emulator is dedicated to testing; its Focal app and settings will be changed")
    parser.add_argument("--repeat", type=int, choices=range(1, 21), default=1, metavar="1..20")
    parser.add_argument("--timeout-minutes", type=int, choices=range(5, 121), default=30, metavar="5..120")
    parser.add_argument("--release-checks", action="store_true", help="Also build release lint and unsigned release APK")
    parser.add_argument("--adb", default="adb", help="Path to adb (default: adb on PATH)")
    return parser


def main(argv=None):
    args = argument_parser().parse_args(argv)
    validate_windows_paths(ROOT, os.environ.get("JAVA_HOME"))
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        raise QAError("--serial must identify a local emulator (emulator-NNNN).")
    adb = lambda *command, timeout=10: device_call(args.adb, args.serial, *command, timeout=timeout)
    if adb("get-state") != "device" or adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise QAError("The selected serial is not an online Android emulator.")
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        raise QAError("The selected emulator has not finished booting.")
    initial_source = source_snapshot()
    model = verify_model_source()
    expected = expected_instrumentation_tests()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    report = ROOT / "build/local-emulator-qa" / stamp
    report.mkdir(mode=0o700, parents=True, exist_ok=False)
    if os.name != "nt" and report.stat().st_mode & 0o077:
        raise QAError("Could not restrict report directory permissions to the current user.")
    print(f"Private report directory: {report}", flush=True)
    metadata = {"schema": 1, "source": initial_source, "serial": args.serial, "repeat": args.repeat,
                "expected_instrumentation_tests": sorted(f"{a}.{b}" for a, b in expected),
                "release_checks": args.release_checks, "model_sha256": model["sha256"],
                "host": {"os": platform.system(), "os_release": platform.release(), "arch": platform.machine(),
                         "logical_cpus": os.cpu_count(), "memory_bytes": host_memory_bytes()},
                "device": {"api": adb("shell", "getprop", "ro.build.version.sdk"),
                           "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                           "memory_total": next((s for s in adb("shell", "cat", "/proc/meminfo").splitlines() if s.startswith("MemTotal:")), "unknown"),
                           "cpu_present": adb("shell", "cat", "/sys/devices/system/cpu/present")},
                "tools": {"adb": run([args.adb, "version"]).splitlines()[0],
                          "java": run(["java", "-version"]).splitlines()[0],
                          "python": platform.python_version()}}
    (report / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    tasks = [":core:test", ":app:lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest"]
    if args.release_checks:
        tasks += [":app:lintRelease", ":app:assembleRelease"]
    print("Building and running host checks once; see build.log.", flush=True)
    with (report / "build.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(gradle_command(tasks), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=False, env=gradle_environment())
    if result.returncode:
        raise QAError(f"Gradle failed (exit {result.returncode}); see {report / 'build.log'}")
    shutil.copytree(ROOT / "core/build/test-results/test", report / "core-test-results")
    for name in ("lint-results-debug.html", "lint-results-debug.xml", "lint-results-release.html", "lint-results-release.xml"):
        if "release" in name and not args.release_checks:
            continue
        source = ROOT / "app/build/reports" / name
        if source.exists():
            shutil.copy2(source, report / name)
    app_apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    verify_apks(app_apk, test_apk, model)
    if source_snapshot() != initial_source:
        raise QAError("Source changed during the build; rerun QA against a stable checkout.")
    metadata["artifacts"] = {"debug_apk_sha256": file_hash(app_apk), "test_apk_sha256": file_hash(test_apk)}
    metadata["tools"]["gradle"] = run(gradle_command(["--version"]), env=gradle_environment(), timeout=60).split("Gradle ")[-1].splitlines()[0]
    emulator_name = "emulator.exe" if os.name == "nt" else "emulator"
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    emulator = str(Path(sdk) / "emulator" / emulator_name) if sdk else emulator_name
    try:
        metadata["tools"]["emulator"] = run([emulator, "-version"]).splitlines()[0]
    except (QAError, IndexError):
        metadata["tools"]["emulator"] = "unavailable"
    (report / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print("Installing verified debug and test APKs; existing app data is retained.", flush=True)
    adb("install", "-r", str(app_apk), timeout=120)
    adb("install", "-r", str(test_apk), timeout=120)
    print("Verifying the model actually installed in the app.", flush=True)
    metadata["installed_model_sha256"] = verify_installed_model(adb, model["sha256"])
    adb("shell", "am", "force-stop", "app.focal")
    (report / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    def save_originals(originals):
        metadata["original_animation_scales"] = originals
        (report / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")

    with normal_animations(adb, save_originals):
        for index in range(1, args.repeat + 1):
            if source_snapshot() != initial_source:
                raise QAError("Source changed during QA; results no longer match the manifest.")
            folder = report / f"run-{index:02d}"
            folder.mkdir()
            log_path = folder / "instrumentation.log"
            print(f"Running instrumentation {index}/{args.repeat}; timeout {args.timeout_minutes} minutes.", flush=True)
            started = time.monotonic()
            with log_path.open("w", encoding="utf-8") as log:
                process = subprocess.Popen(adb_command(args.adb, args.serial, "shell", "am", "instrument", "-r", "-w",
                                           "app.focal.test/androidx.test.runner.AndroidJUnitRunner"),
                                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                try:
                    code = process.wait(timeout=args.timeout_minutes * 60)
                except (subprocess.TimeoutExpired, KeyboardInterrupt):
                    process.kill()
                    process.wait()
                    for package in ("app.focal.test", "app.focal"):
                        try:
                            run(adb_command(args.adb, args.serial, "shell", "am", "force-stop", package), check=False, timeout=10)
                        except QAError:
                            pass
                    log.flush()
                    outcomes, problems = parse_instrumentation(log_path.read_text(encoding="utf-8", errors="replace"), expected)
                    problems.append("Run timed out or was interrupted")
                    write_junit(folder / "results.xml", outcomes, problems)
                    (folder / "summary.json").write_text(json.dumps({"tests": len(outcomes), "elapsed_seconds": round(time.monotonic() - started, 3), "problems": problems}, indent=2) + "\n", encoding="utf-8")
                    raise
            outcomes, problems = parse_instrumentation(log_path.read_text(encoding="utf-8", errors="replace"), expected)
            if code:
                problems.append(f"adb exited with status {code}")
            write_junit(folder / "results.xml", outcomes, problems)
            (folder / "summary.json").write_text(json.dumps({"tests": len(outcomes), "elapsed_seconds": round(time.monotonic() - started, 3), "problems": problems}, indent=2) + "\n", encoding="utf-8")
            if problems:
                raise QAError(f"Run {index} failed: {'; '.join(problems)}. See {folder}.")
            print(f"Run {index} passed: {len(outcomes)} tests, no skips.", flush=True)
        if source_snapshot() != initial_source:
            raise QAError("Source changed during the final run; results no longer match the manifest.")
    print(f"All requested runs passed. Reports: {report}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (QAError, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"QA failed: {error}", file=sys.stderr)
        raise SystemExit(1)

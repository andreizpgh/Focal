"""Configure the optional ownership overlay; never build or load a speech model."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "third_party/whisper.cpp"
OVERLAY = ROOT / "cmake/focal-whisper-lifetime.cmake"
POSTIMAGE = "8cc010e36be180b2843287562cd69104a2b1f11e4d7a95827fffce7db70ed64b"


@unittest.skipUnless(shutil.which("cmake") and shutil.which("c++"), "CMake and a host C++ compiler are required")
class WhisperLifetimeOverlayTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.source = self.directory / "source"
        self.build = self.directory / "build"
        (self.source / "src").mkdir(parents=True)
        (self.source / "include").mkdir()
        shutil.copyfile(ENGINE / "src/whisper.cpp", self.source / "src/whisper.cpp")
        shutil.copyfile(ENGINE / "include/whisper.h", self.source / "include/whisper.h")
        (self.source / "src/extra.cpp").write_text("// Preserve unrelated target sources.\n")

    def configure(self, *, target=None, before="", after="", overlay=OVERLAY,
                  expected_error=None, deferred=False):
        if target is None:
            target = "add_library(whisper STATIC whisper.cpp ../include/whisper.h extra.cpp)"
        (self.source / "src/CMakeLists.txt").write_text(target + "\n")
        call = "focal_whisper_lifetime_overlay()" if not deferred else ""
        project = f'''cmake_minimum_required(VERSION 3.19)
project(whisper.cpp LANGUAGES CXX)
add_subdirectory(src)
{before}
include("{overlay.as_posix()}")
{call}
{after}
add_custom_target(check_configuration)
function(record_sources)
    get_target_property(actual whisper SOURCES)
    file(WRITE "${{CMAKE_BINARY_DIR}}/sources.txt" "${{actual}}")
endfunction()
cmake_language(DEFER CALL record_sources)
'''
        (self.source / "CMakeLists.txt").write_text(project)
        command = ["cmake", "-S", str(self.source), "-B", str(self.build)]
        if deferred:
            command.append(f"-DCMAKE_PROJECT_whisper.cpp_INCLUDE={ROOT / 'cmake/whisper-lifetime-research-entry.cmake'}")
        original = {p: p.read_bytes() for p in (self.source / "src/whisper.cpp", self.source / "include/whisper.h")}
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        output = result.stdout + result.stderr
        for path, content in original.items():
            self.assertEqual(content, path.read_bytes(), "Configuration changed a source file")
        if expected_error is not None:
            self.assertNotEqual(0, result.returncode, output)
            self.assertIn(expected_error, output)
            return
        self.assertEqual(0, result.returncode, output)
        generated = self.build / "src/focal-lifetime/whisper.cpp"
        self.assertEqual(POSTIMAGE, hashlib.sha256(generated.read_bytes()).hexdigest())
        self.assertEqual([str(generated), "../include/whisper.h", "extra.cpp"],
                         (self.build / "sources.txt").read_text().split(";"))

    def assert_incremental_change_is_rejected(self, path, error):
        command = ["cmake", "--build", str(self.build), "--target", "check_configuration"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        original = path.read_bytes()
        try:
            path.write_bytes(original + b"\n// Changed preimage.\n")
            # Make the change newer even on filesystems with coarse timestamps.
            future = time.time_ns() + 2_000_000_000
            os.utime(path, ns=(future, future))
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            output = result.stdout + result.stderr
            self.assertNotEqual(0, result.returncode, output)
            self.assertIn(error, output)
        finally:
            path.write_bytes(original)

    def test_lf_source_generates_exact_reviewed_engine(self):
        self.configure()

    def test_crlf_source_generates_same_engine_without_mutating_inputs(self):
        for path in (self.source / "src/whisper.cpp", self.source / "include/whisper.h"):
            path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
        self.configure()

    def test_deferred_project_hook_applies_once(self):
        entry = ROOT / "cmake/whisper-lifetime-research-entry.cmake"
        self.configure(deferred=True, after=f'include("{entry.as_posix()}")\ninclude("{entry.as_posix()}")')

    def test_changed_source_or_header_is_rejected(self):
        for relative in ("src/whisper.cpp", "include/whisper.h"):
            with self.subTest(relative=relative):
                path = self.source / relative
                content = path.read_bytes()
                path.write_bytes(content + b"\n// Changed preimage.\n")
                self.configure(expected_error="requires the pinned preimages")
                path.write_bytes(content)

    def test_incremental_build_rechecks_source_and_header(self):
        for relative in ("src/whisper.cpp", "include/whisper.h"):
            with self.subTest(relative=relative):
                self.configure()
                self.assert_incremental_change_is_rejected(
                    self.source / relative, "requires the pinned preimages")

    def test_incremental_fault_build_rechecks_engine_and_jni(self):
        fault_overlay = ROOT / "app/src/main/cpp/native_fault.cmake"
        jni = self.source / "whisper_jni.cpp"
        shutil.copyfile(ROOT / "app/src/main/cpp/whisper_jni.cpp", jni)
        (self.source / "src/CMakeLists.txt").write_text(
            "add_library(whisper STATIC whisper.cpp ../include/whisper.h extra.cpp)\n")
        for variant in ("baseline", "patched"):
            for path, error in (
                (self.source / "src/whisper.cpp", "preimages" if variant == "patched" else "exact selected"),
                (jni, "JNI source has changed"),
            ):
                with self.subTest(variant=variant, path=path.name):
                    lifetime = (f'include("{OVERLAY.as_posix()}")\n'
                                'focal_whisper_lifetime_overlay()\n') if variant == "patched" else ""
                    (self.source / "CMakeLists.txt").write_text(f'''cmake_minimum_required(VERSION 3.19)
project(fault_test LANGUAGES CXX)
set(FOCAL_NATIVE_FAULT_TESTS ON)
set(FOCAL_ANDROID_DEBUG_BUILD ON)
set(FOCAL_NATIVE_FAULT_VARIANT {variant})
add_subdirectory(src)
{lifetime}add_library(focal SHARED whisper_jni.cpp)
include("{fault_overlay.as_posix()}")
focal_native_fault_overlay()
add_custom_target(check_configuration)
''')
                    result = subprocess.run(["cmake", "-S", str(self.source), "-B", str(self.build)],
                                            capture_output=True, text=True, timeout=30)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assert_incremental_change_is_rejected(path, error)

    def test_missing_and_imported_targets_are_rejected(self):
        self.configure(target="", expected_error="requires the whisper target")
        self.configure(target="add_library(whisper STATIC IMPORTED GLOBAL)",
                       expected_error="requires a source-built whisper target")

    def test_missing_duplicate_or_replaced_engine_inputs_are_rejected(self):
        for sources in ("extra.cpp ../include/whisper.h", "whisper.cpp extra.cpp",
                        "whisper.cpp whisper.cpp ../include/whisper.h",
                        "whisper.cpp ../include/whisper.h ../include/whisper.h"):
            with self.subTest(sources=sources):
                self.configure(target=f"add_library(whisper STATIC {sources})",
                               expected_error="requires exactly one original")

    def test_repeated_application_is_rejected(self):
        self.configure(after="focal_whisper_lifetime_overlay()",
                       expected_error="requires exactly one original")

    def test_changed_replacement_cannot_publish_an_unknown_postimage(self):
        altered = self.directory / "altered-overlay.cmake"
        content = OVERLAY.read_text()
        self.assertIn("whisper_batch batch = {};", content)
        altered.write_text(content.replace("whisper_batch batch = {};", "whisper_batch batch = {}; // Changed", 1))
        self.configure(overlay=altered, expected_error="does not match the reviewed postimage")
        self.assertFalse((self.build / "src/focal-lifetime/whisper.cpp").exists())

    def test_speech_policy_overlay_composition_is_rejected_in_both_orders(self):
        for name in ("sot", "vad", "beam"):
            include = f'include("{(ROOT / f"cmake/focal-whisper-{name}.cmake").as_posix()}")'
            call = f"focal_whisper_{name}_overlay()"
            with self.subTest(overlay=name, order="before"):
                self.configure(before=f"{include}\n{call}", expected_error="requires exactly one original")
            with self.subTest(overlay=name, order="after"):
                self.configure(after=f"{include}\n{call}", expected_error="requires exactly one original")


if __name__ == "__main__":
    unittest.main()

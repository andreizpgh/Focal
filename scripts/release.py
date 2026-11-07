#!/usr/bin/env python3
"""Build, sign, verify and describe a Focal testing release on this machine.

CI cannot build a release: it has no 670 MB speech model. A release is built here, from a
clean checkout of the commit to be tagged, with the verified model in place. The script:

  1. checks that the working tree is clean, that the tag (vX.Y.Z from app/build.gradle.kts)
     does not point elsewhere, and that CHANGELOG.md has a section for the version;
  2. checks the model and runtime against models/*.json (scripts/fetch-model.py --check);
  3. runs the script tests and ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
     :app:assembleRelease -Pfocal.requireModel=true, unless --skip-build or --signed-apk;
  4. signs the unsigned APK with apksigner (v2 and v3), unless --signed-apk;
  5. verifies the signed APK: apksigner verify with exactly one signer whose certificate
     SHA-256 is --expected-cert, zipalign -c -P 16 4, aapt2 badging (app.focal and the
     version), lib/ holding arm64-v8a only with the speech libraries stored, and every
     locked model file in assets/ with its locked size and SHA-256, the ONNX files stored;
  6. writes Focal-X.Y.Z.apk, Focal-X.Y.Z-provenance.json (schema focal-release/1) and
     SHA256SUMS into a new output directory (default build/release/vX.Y.Z). An existing
     output directory is never overwritten.

Signing material never enters Git or the command line: the keystore and alias come from
--keystore/--key-alias or FOCAL_KEYSTORE/FOCAL_KEY_ALIAS, and passwords are apksigner
password specs, env:NAME (default env:FOCAL_KEYSTORE_PASS and, when set, env:FOCAL_KEY_PASS)
or file:PATH. Testing releases are signed with the Android debug key that signed Alpha 2 to
Alpha 9, so they install over those alphas in place.

Examples:
  python3 scripts/release.py --expected-cert SHA256
  python3 scripts/release.py --expected-cert SHA256 --skip-build   # sign the existing unsigned APK
  python3 scripts/release.py --expected-cert SHA256 --signed-apk FILE --source-commit SHA
      # describe an APK that was already signed and tested, without re-signing it

Only the Python standard library is used; Android build-tools (apksigner, zipalign, aapt2)
come from --build-tools, ANDROID_HOME, ANDROID_SDK_ROOT, local.properties or ~/Android/Sdk.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "focal-release/1"
APPLICATION_ID = "app.focal"
RELEASE_ABIS = ("arm64-v8a",)
# The JNI bridge and the sherpa-onnx runtime it calls: a release without them cannot transcribe.
SPEECH_LIBRARIES = ("libfocal_speech.so", "libsherpa-onnx-c-api.so", "libonnxruntime.so")
MODEL_LOCK = "models/parakeet-ultra.json"
RUNTIME_LOCK = "models/sherpa-onnx.json"
GRADLE_BUILD = "app/build.gradle.kts"
UNSIGNED_APK = "app/build/outputs/apk/release/app-release-unsigned.apk"
GRADLE_COMMAND = ("./gradlew", ":core:test", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleRelease",
                  "-Pfocal.requireModel=true", "--console=plain")
SCRIPT_TESTS = ("-m", "unittest", "discover", "-s", "scripts/tests")
# Files whose change since --source-commit would make an existing APK a different build.
BUILD_INPUTS = ("app", "core", "gradle", "models", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradlew", "gradlew.bat")
PAGE_SIZE_KB = "16"
BLOCK = 1024 * 1024


class ReleaseError(Exception):
    pass


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while block := source.read(BLOCK):
            digest.update(block)
    return digest.hexdigest()


# Pure parts: parsing, checks and provenance. Covered by scripts/tests/test_release.py.

def read_version(gradle_text):
    """(versionName, versionCode) from app/build.gradle.kts."""
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', gradle_text, re.MULTILINE)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)", gradle_text, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ReleaseError(f"expected one versionName and one versionCode in {GRADLE_BUILD}")
    return names[0], int(codes[0])


def read_setting(text, pattern, what):
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise ReleaseError(f"cannot find {what}")
    return match.group(1)


def normalise_certificate(value):
    digest = re.sub(r"[\s:]", "", value or "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ReleaseError("the expected certificate must be a SHA-256 digest: 64 hexadecimal digits")
    return digest


def password_spec(spec, environ):
    """An apksigner password spec that keeps the password off the command line."""
    if spec.startswith("env:"):
        name = spec[4:]
        if not environ.get(name):
            raise ReleaseError(f"{spec}: the environment variable {name} is not set")
        return spec
    if spec.startswith("file:"):
        if not Path(spec[5:]).is_file():
            raise ReleaseError(f"{spec}: no such file")
        return spec
    raise ReleaseError(f"unsupported password spec {spec.split(':', 1)[0]!r}: use env:NAME or file:PATH, "
                       "never pass: (it would put the password on the command line)")


def parse_badging(text):
    package = re.search(r"^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", text, re.MULTILINE)
    if not package:
        raise ReleaseError("aapt2 badging has no package line")
    native = re.search(r"^native-code: (.*)$", text, re.MULTILINE)
    return {"package": package.group(1), "version_code": int(package.group(2)), "version_name": package.group(3),
            "native_code": re.findall(r"'([^']+)'", native.group(1)) if native else []}


def parse_apksigner(text):
    """Schemes, signer count and certificate digests from apksigner verify --verbose --print-certs."""
    if not re.search(r"^Verifies$", text, re.MULTILINE):
        raise ReleaseError("apksigner did not report 'Verifies'")
    schemes = [scheme for scheme, verified in
               re.findall(r"^Verified using (v[\d.]+) scheme \([^)]*\): (true|false)$", text, re.MULTILINE)
               if verified == "true"]
    signers = re.search(r"^Number of signers: (\d+)$", text, re.MULTILINE)
    certificates = sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})$", text, re.MULTILINE)))
    return {"schemes": schemes, "signers": int(signers.group(1)) if signers else 0, "certificates": certificates}


def check_signature(signature, expected_certificate):
    if not signature["schemes"]:
        raise ReleaseError("apksigner verified no signature scheme")
    if signature["signers"] != 1:
        raise ReleaseError(f"expected exactly one signer, found {signature['signers']}")
    if signature["certificates"] != [expected_certificate]:
        found = ", ".join(signature["certificates"]) or "none"
        raise ReleaseError(f"signing certificate SHA-256 {found} is not the expected {expected_certificate}")


def check_badging(badging, version_name, version_code):
    wanted = {"package": APPLICATION_ID, "version_name": version_name, "version_code": version_code,
              "native_code": list(RELEASE_ABIS)}
    wrong = [f"{key} is {badging.get(key)!r}, expected {value!r}" for key, value in wanted.items()
             if badging.get(key) != value]
    if wrong:
        raise ReleaseError("aapt2 badging: " + "; ".join(wrong))


def check_apk(path, model_lock):
    """Checks the native libraries and the packaged model of an APK; returns what it found.

    lib/ must hold exactly RELEASE_ABIS, each with the speech libraries, every .so stored (the
    app does not extract them). Every locked model file must be in assets/ with its locked
    size and SHA-256; the ONNX files must be stored, since the recognizer
    streams them out of the APK; and no other ONNX file may ship.
    """
    problems = []
    with zipfile.ZipFile(path) as apk:
        entries = {info.filename: info for info in apk.infolist()}
        libraries = {}
        for name, info in entries.items():
            parts = name.split("/")
            if len(parts) >= 3 and parts[0] == "lib" and not name.endswith("/"):
                libraries.setdefault(parts[1], []).append(parts[-1])
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append(f"{name} is compressed; native libraries must be stored")
        abis = sorted(libraries)
        if abis != sorted(RELEASE_ABIS):
            problems.append(f"lib/ holds {', '.join(abis) or 'no ABI'}, expected {', '.join(RELEASE_ABIS)} only")
        for abi in RELEASE_ABIS:
            missing = sorted(set(SPEECH_LIBRARIES) - set(libraries.get(abi, [])))
            if missing:
                problems.append(f"lib/{abi} lacks {', '.join(missing)}")

        locked = {entry["name"]: entry for entry in model_lock["files"]}
        stored = []
        for name, entry in locked.items():
            info = entries.get(f"assets/{name}")
            if info is None:
                problems.append(f"assets/{name} is missing")
                continue
            if info.file_size != entry["bytes"]:
                problems.append(f"assets/{name} has {info.file_size:,} bytes, the lock says {entry['bytes']:,}")
                continue
            if name.endswith(".onnx"):
                if info.compress_type == zipfile.ZIP_STORED:
                    stored.append(name)
                else:
                    problems.append(f"assets/{name} is compressed; ONNX files must be stored")
            digest = hashlib.sha256()
            with apk.open(info) as source:
                while block := source.read(BLOCK):
                    digest.update(block)
            if digest.hexdigest() != entry["sha256"]:
                problems.append(f"assets/{name} does not match the SHA-256 in the lock")
        extra = sorted(name for name in entries
                       if name.startswith("assets/") and name.endswith(".onnx") and name[7:] not in locked)
        if extra:
            problems.append(f"unlocked ONNX files in the APK: {', '.join(extra)}")
    if problems:
        raise ReleaseError("APK contents:\n  " + "\n  ".join(problems))
    return {"abis": abis, "native_libraries": sorted(libraries[RELEASE_ABIS[0]]), "stored_model_files": sorted(stored),
            "model_files": sorted(locked)}


def pending_phone_checks(markdown, allow_none=False):
    """The unchecked items of the '## Phone validation' section, prefixed with their group."""
    section = re.search(r"^## Phone validation\s*$(.*?)(?=^## |\Z)", markdown, re.MULTILINE | re.DOTALL)
    if not section:
        raise ReleaseError("docs/testing.md has no '## Phone validation' section")
    items, group, current = [], None, None

    def close():
        if current is not None:
            text = " ".join(" ".join(current).split())
            items.append(f"{group}: {text}" if group else text)

    for line in section.group(1).splitlines():
        heading = re.fullmatch(r"\*\*(.+?)\*\*\s*", line)
        open_item = re.match(r"^- \[ \] (.*)$", line)
        if open_item or heading or re.match(r"^- \[[xX]\] ", line) or not line.strip() or not line[0].isspace():
            close()
            current = None
        if heading:
            group = heading.group(1).strip()
        elif open_item:
            current = [open_item.group(1)]
        elif current is not None:
            current.append(line.strip())
    close()
    if not items and not allow_none:
        raise ReleaseError("docs/testing.md lists no pending phone checks; pass --phone-validated "
                           "only if every phone check really passed on the release APK")
    return items


def lock_summary(model_lock, model_lock_bytes, runtime_lock, runtime_lock_bytes):
    return {
        "model": {
            "lock": MODEL_LOCK,
            "lock_sha256": hashlib.sha256(model_lock_bytes).hexdigest(),
            "release": model_lock.get("release"),
            "conversion_revision": model_lock.get("conversion_revision"),
            "files": {entry["name"]: {"bytes": entry["bytes"], "sha256": entry["sha256"]}
                      for entry in model_lock["files"]},
        },
        "runtime": {
            "lock": RUNTIME_LOCK,
            "lock_sha256": hashlib.sha256(runtime_lock_bytes).hexdigest(),
            "sherpa_onnx": runtime_lock.get("version"),
            "revision": runtime_lock.get("revision"),
            "onnxruntime": runtime_lock.get("onnxruntime"),
            "aar_bytes": runtime_lock["bytes"],
            "aar_sha256": runtime_lock["sha256"],
        },
    }


def build_provenance(*, version, version_code, tag, source, apk, signing, locks, build, checks, not_established,
                     created):
    if not not_established:
        raise ReleaseError("the provenance must say what remains unverified")
    return {
        "schema": SCHEMA,
        "application_id": APPLICATION_ID,
        "version": version,
        "version_code": version_code,
        "tag": tag,
        "prerelease": True,
        "created_utc": created.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": source,
        "apk": apk,
        "signing": signing,
        "model": locks["model"],
        "runtime": locks["runtime"],
        "build": build,
        "checks": list(checks),
        "not_established": list(not_established),
    }


def sha256sums(files):
    """sha256sum's format: digest, two spaces, file name; one line per (name, digest)."""
    return "".join(f"{digest}  {name}\n" for name, digest in files)


# Steps with side effects.

def run(command, capture=False):
    print("$ " + " ".join(str(part) for part in command), flush=True)
    result = subprocess.run([str(part) for part in command], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE if capture else None, stderr=subprocess.PIPE if capture else None)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip() if capture else ""
        raise ReleaseError(f"{Path(str(command[0])).name} {command[1] if len(command) > 1 else ''} failed"
                           + (f":\n{detail}" if detail else ""))
    return result.stdout if capture else ""


def git(*arguments, check=True):
    result = subprocess.run(["git", *arguments], cwd=ROOT, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise ReleaseError(f"git {arguments[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip() if result.returncode == 0 else None


def version_key(name):
    return tuple(int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", name))


def find_build_tools(explicit):
    tools = ("apksigner", "zipalign", "aapt2")
    if explicit:
        candidates = [Path(explicit)]
    else:
        sdks = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
        properties = ROOT / "local.properties"
        if properties.is_file():
            match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(encoding="utf-8"), re.MULTILINE)
            sdks.append(match and match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\"))
        sdks += [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]
        candidates = []
        for sdk in filter(None, sdks):
            directory = Path(sdk) / "build-tools"
            if directory.is_dir():
                candidates += sorted(directory.iterdir(), key=lambda path: version_key(path.name), reverse=True)
    for candidate in candidates:
        if all((candidate / tool).is_file() for tool in tools):
            return candidate
    raise ReleaseError("Android build-tools with apksigner, zipalign and aapt2 not found; pass --build-tools DIR")


def first_line(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    lines = (result.stdout + result.stderr).strip().splitlines()
    lines = [line for line in lines if not line.startswith(("WARNING:", "Picked up"))]
    return lines[0].strip() if lines else None


def toolchain(build_tools):
    gradle_text = (ROOT / GRADLE_BUILD).read_text(encoding="utf-8")
    catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    return {
        "gradle": read_setting(wrapper, r"gradle-([\w.]+)-(?:bin|all)\.zip", "the Gradle wrapper version"),
        "android_gradle_plugin": read_setting(catalog, r'^agp\s*=\s*"([^"]+)"', "the AGP version"),
        "kotlin": read_setting(catalog, r'^kotlin\s*=\s*"([^"]+)"', "the Kotlin version"),
        "compile_sdk": int(read_setting(gradle_text, r"^\s*compileSdk\s*=\s*(\d+)", "compileSdk")),
        "ndk": read_setting(gradle_text, r'^\s*ndkVersion\s*=\s*"([^"]+)"', "ndkVersion"),
        "java": first_line([java, "-version"]),
        "build_tools": build_tools.name,
        "apksigner": first_line([str(build_tools / "apksigner"), "--version"]),
        "python": platform.python_version(),
    }


def changed_build_inputs(source_commit):
    return git("diff", "--name-only", source_commit, "HEAD", "--", *BUILD_INPUTS).splitlines()


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--expected-cert", default=os.environ.get("FOCAL_CERT_SHA256"), metavar="SHA256",
                        help="the signing certificate's SHA-256 (default: $FOCAL_CERT_SHA256)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--skip-build", action="store_true", help="sign an existing unsigned APK instead of building")
    mode.add_argument("--signed-apk", type=Path, metavar="FILE",
                      help="verify and describe an APK that is already signed; it is copied, never re-signed")
    parser.add_argument("--unsigned-apk", type=Path, metavar="FILE",
                        help=f"the APK to sign with --skip-build (default: {UNSIGNED_APK})")
    parser.add_argument("--source-commit", metavar="SHA",
                        help="with --skip-build or --signed-apk: the commit the APK was built from (default: HEAD); "
                             "no build input may have changed between it and HEAD")
    parser.add_argument("--keystore", default=os.environ.get("FOCAL_KEYSTORE"), help="default: $FOCAL_KEYSTORE")
    parser.add_argument("--key-alias", default=os.environ.get("FOCAL_KEY_ALIAS"), help="default: $FOCAL_KEY_ALIAS")
    parser.add_argument("--ks-pass", default="env:FOCAL_KEYSTORE_PASS", metavar="SPEC",
                        help="keystore password as env:NAME or file:PATH (default: env:FOCAL_KEYSTORE_PASS)")
    parser.add_argument("--key-pass", default="env:FOCAL_KEY_PASS" if os.environ.get("FOCAL_KEY_PASS") else None,
                        metavar="SPEC", help="key password spec, when it differs from the keystore's "
                                             "(default: env:FOCAL_KEY_PASS if set)")
    parser.add_argument("--build-tools", type=Path, metavar="DIR", help="Android build-tools directory to use")
    parser.add_argument("--out", type=Path, metavar="DIR", help="output directory, which must not exist "
                                                               "(default: build/release/vX.Y.Z)")
    parser.add_argument("--phone-validated", action="store_true",
                        help="every phone check in docs/testing.md is ticked for this APK: record an empty "
                             "not_established list instead of refusing")
    parser.add_argument("--allow-dirty", action="store_true",
                        help="rehearse on a modified tree; the provenance then says clean: false. Never publish it")
    args = parser.parse_args(argv)
    if args.signed_apk and not args.source_commit:
        parser.error("--signed-apk needs --source-commit: name the commit the APK was built from")
    if args.source_commit and not (args.skip_build or args.signed_apk):
        parser.error("--source-commit needs --skip-build or --signed-apk: a fresh build is always of HEAD")
    if args.unsigned_apk and not args.skip_build:
        parser.error("--unsigned-apk needs --skip-build")
    try:
        if not args.expected_cert:
            raise ReleaseError("pass --expected-cert (or set FOCAL_CERT_SHA256): the signing certificate's SHA-256")
        args.expected_cert = normalise_certificate(args.expected_cert)
        if not args.signed_apk:
            if not args.keystore or not args.key_alias:
                raise ReleaseError("signing needs --keystore and --key-alias (or FOCAL_KEYSTORE and FOCAL_KEY_ALIAS)")
            if not Path(args.keystore).expanduser().is_file():
                raise ReleaseError(f"no keystore at {args.keystore}")
            args.ks_pass = password_spec(args.ks_pass, os.environ)
            args.key_pass = args.key_pass and password_spec(args.key_pass, os.environ)
    except ReleaseError as error:
        parser.error(str(error))
    return args


def release(args):
    version, version_code = read_version((ROOT / GRADLE_BUILD).read_text(encoding="utf-8"))
    tag = f"v{version}"
    out = (args.out or ROOT / "build/release" / tag).resolve()
    staging = out.with_name(out.name + ".partial")
    for path in (out, staging):
        if path.exists():
            raise ReleaseError(f"{path} already exists; release outputs are never overwritten")

    head = git("rev-parse", "HEAD")
    clean = not git("status", "--porcelain", "--untracked-files=normal")
    if not clean and not args.allow_dirty:
        raise ReleaseError("the working tree has changes; commit or stash them (or rehearse with --allow-dirty)")
    tagged = git("rev-parse", "-q", "--verify", f"refs/tags/{tag}^{{commit}}", check=False)
    if tagged and tagged != head:
        raise ReleaseError(f"tag {tag} points at {tagged}, not HEAD {head}")
    source_commit = git("rev-parse", "--verify", f"{args.source_commit or 'HEAD'}^{{commit}}")
    checks = [f"working tree {'clean' if clean else 'MODIFIED (rehearsal, do not publish)'} at {head[:12]}"]
    if source_commit != head:
        if git("merge-base", "--is-ancestor", source_commit, head, check=False) is None:
            raise ReleaseError(f"{source_commit} is not an ancestor of HEAD")
        changed = changed_build_inputs(source_commit)
        if changed:
            raise ReleaseError(f"build inputs changed since {source_commit[:12]}: {', '.join(changed)}")
        checks.append(f"no build input changed between {source_commit[:12]} and {head[:12]}")
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if not re.search(rf"^## \[{re.escape(version)}\]", changelog, re.MULTILINE):
        raise ReleaseError(f"CHANGELOG.md has no '## [{version}]' section")
    checks.append(f"CHANGELOG.md has a {version} section")

    run([sys.executable, "scripts/fetch-model.py", "--check"])
    checks.append("model and runtime match models/*.json (scripts/fetch-model.py --check)")
    build_tools = find_build_tools(args.build_tools)

    built = not (args.skip_build or args.signed_apk)
    if built:
        run([sys.executable, *SCRIPT_TESTS])
        run(GRADLE_COMMAND)
        checks += ["script tests", "Gradle: core tests, app unit tests, debug lint, release build with "
                   "verifyUltraModel and verifyReleaseAbis"]
    unsigned = None if args.signed_apk else (args.unsigned_apk or ROOT / UNSIGNED_APK)
    for given in filter(None, (unsigned, args.signed_apk)):
        if not given.is_file():
            raise ReleaseError(f"no APK at {given}")

    name = f"Focal-{version}.apk"
    provenance_name = f"Focal-{version}-provenance.json"
    staging.mkdir(parents=True)
    try:
        apk = staging / name
        if args.signed_apk:
            shutil.copyfile(args.signed_apk, apk)
        else:
            command = [build_tools / "apksigner", "sign", "--ks", Path(args.keystore).expanduser(),
                       "--ks-key-alias", args.key_alias, "--ks-pass", args.ks_pass]
            if args.key_pass:
                command += ["--key-pass", args.key_pass]
            command += ["--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                        "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", "--in", unsigned, "--out", apk]
            run(command)

        signature = parse_apksigner(run([build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk],
                                        capture=True))
        check_signature(signature, args.expected_cert)
        checks.append(f"apksigner verify: {', '.join(signature['schemes'])}; one signer with the expected certificate")
        run([build_tools / "zipalign", "-c", "-P", PAGE_SIZE_KB, "4", apk], capture=True)
        checks.append(f"zipalign -c -P {PAGE_SIZE_KB} 4")
        check_badging(parse_badging(run([build_tools / "aapt2", "dump", "badging", apk], capture=True)),
                      version, version_code)
        checks.append(f"aapt2 badging: {APPLICATION_ID} versionCode {version_code} versionName {version}, "
                      f"native code {', '.join(RELEASE_ABIS)}")
        model_lock_bytes = (ROOT / MODEL_LOCK).read_bytes()
        runtime_lock_bytes = (ROOT / RUNTIME_LOCK).read_bytes()
        model_lock = json.loads(model_lock_bytes)
        contents = check_apk(apk, model_lock)
        checks.append(f"lib/ holds {', '.join(contents['abis'])} only, with the speech libraries stored")
        checks.append(f"assets/ holds the {len(contents['model_files'])} locked model files byte for byte; "
                      f"{', '.join(contents['stored_model_files'])} stored")

        apk_sha256 = sha256_file(apk)
        provenance = build_provenance(
            version=version, version_code=version_code, tag=tag,
            source={"commit": source_commit, "tag_commit": head, "clean": clean},
            apk={"name": name, "bytes": apk.stat().st_size, "sha256": apk_sha256,
                 "unsigned_sha256": sha256_file(unsigned) if unsigned else None, **contents},
            signing={"by_script": not args.signed_apk, "schemes": signature["schemes"],
                     "certificate_sha256": signature["certificates"][0]},
            locks=lock_summary(model_lock, model_lock_bytes, json.loads(runtime_lock_bytes), runtime_lock_bytes),
            build={"by_script": built, "command": " ".join(GRADLE_COMMAND) if built else None,
                   "toolchain": toolchain(build_tools)},
            checks=checks,
            not_established=pending_phone_checks((ROOT / "docs/testing.md").read_text(encoding="utf-8"),
                                                 allow_none=args.phone_validated),
            created=datetime.now(timezone.utc),
        )
        (staging / provenance_name).write_text(json.dumps(provenance, indent=2, ensure_ascii=False) + "\n",
                                               encoding="utf-8")
        (staging / "SHA256SUMS").write_text(sha256sums(
            [(name, apk_sha256), (provenance_name, sha256_file(staging / provenance_name))]))
        os.replace(staging, out)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    print(f"\n{out}:\n  {name}  {apk_sha256}\n  {provenance_name}\n  SHA256SUMS")
    if not clean:
        print("The tree was modified: this is a rehearsal. Do not publish it.", file=sys.stderr)
    return out


def main(argv=None):
    args = parse_arguments(argv)
    try:
        release(args)
    except ReleaseError as error:
        print(f"release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

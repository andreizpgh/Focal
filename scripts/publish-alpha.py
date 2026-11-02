#!/usr/bin/env python3
"""Promote an exact, verified CI artifact to a public testing release."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD_INPUTS = [
    "app", "core", "gradle", "models", "third_party", ".gitmodules",
    "gradlew", "gradlew.bat", "gradle.properties", "settings.gradle.kts",
    "build.gradle.kts", "scripts/fetch-model.py", "scripts/smoke-stt.sh",
    "scripts/test-android-ci.sh", "scripts/test-summary.py",
]


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def api(path):
    return json.loads(run("gh", "api", path, capture_output=True, text=True).stdout)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    tag = os.environ["RELEASE_TAG"]
    require(re.fullmatch(r"v\d+\.\d+\.\d+-alpha\.\d+", tag), "Expected an alpha tag")
    repo = os.environ["GITHUB_REPOSITORY"]
    manifest = json.loads((ROOT / "releases" / f"{tag}.json").read_text())
    source = manifest["source_commit"]
    digest = manifest["artifact_sha256"]
    require(manifest["tag"] == tag, "Release tag differs from manifest")
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Invalid source commit")
    require(re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid artifact checksum")
    run("git", "merge-base", "--is-ancestor", source, "HEAD")
    run("git", "diff", "--exit-code", source, "HEAD", "--", *BUILD_INPUTS)

    workflow = api(f"repos/{repo}/actions/runs/{manifest['run_id']}")
    require(
        workflow["conclusion"] == "success"
        and workflow["head_sha"] == source
        and workflow["head_branch"] == "main"
        and workflow["event"] == "push"
        and workflow["path"] == ".github/workflows/android.yml"
        and workflow["repository"]["full_name"].lower() == repo.lower(),
        "Source must be a successful Android build on this repository's main branch",
    )
    artifact_path = f"repos/{repo}/actions/artifacts/{manifest['artifact_id']}"
    artifact = api(artifact_path)
    require(
        artifact["name"] == "Focal-debug"
        and not artifact["expired"]
        and artifact["workflow_run"]["id"] == manifest["run_id"]
        and artifact["workflow_run"]["head_sha"] == source
        and artifact["digest"] == f"sha256:{digest}",
        "Artifact metadata differs from the verified build",
    )

    destination = ROOT / "build" / "release-download"
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / "artifact.zip"
    with archive.open("wb") as stream:
        run("gh", "api", f"{artifact_path}/zip", stdout=stream)
    require(sha256(archive) == digest, "Artifact download checksum mismatch")
    with zipfile.ZipFile(archive) as bundle:
        for name in ("Focal.apk", "model-provenance.json"):
            require(bundle.getinfo(name).file_size < 200_000_000, "Unexpected artifact size")
            (destination / name).write_bytes(bundle.read(name))

    model = json.loads((ROOT / "models/model.json").read_text())
    provenance_path = destination / "model-provenance.json"
    provenance = json.loads(provenance_path.read_text())
    for key in ("repository", "filename", "revision", "sha256"):
        require(provenance[key] == model[key], f"Model provenance mismatch: {key}")
    apk = destination / "Focal.apk"
    with zipfile.ZipFile(apk) as package:
        require(
            json.loads(package.read("assets/model-provenance.json")) == provenance,
            "APK model provenance differs from the build artifact",
        )
        with package.open(f"assets/{model['filename']}") as stream:
            model_digest = hashlib.file_digest(stream, "sha256").hexdigest()
        require(model_digest == model["sha256"], "Embedded model checksum mismatch")

    checksums = destination / "SHA256SUMS"
    checksums.write_text("".join(f"{sha256(p)}  {p.name}\n" for p in (apk, provenance_path)))
    releases = api(f"repos/{repo}/releases?per_page=100")
    require(not any(r["tag_name"] == tag for r in releases), "Release already exists; inspect it before retrying")
    run(
        "gh", "release", "create", tag, "--repo", repo, "--verify-tag",
        "--draft", "--prerelease", "--title", manifest["title"],
        "--notes-file", str(ROOT / "docs/releases" / f"{tag}.md"),
        str(apk), str(checksums), str(provenance_path),
    )
    run("gh", "release", "edit", tag, "--repo", repo, "--draft=false")
    url = f"https://github.com/{repo}/releases/download/{tag}/Focal.apk"
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(f"[Download Focal.apk]({url})\n\nAPK SHA-256: `{sha256(apk)}`\n")
    print(f"Published {url}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        message = str(error)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            message += f": {error.stderr}"
        message = message[:1500].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Alpha publication failed::{message}")
        sys.exit(1)

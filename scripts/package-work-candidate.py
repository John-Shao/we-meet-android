"""Build and archive an auditable Android Debug candidate; no installation or upload."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


class CandidateError(RuntimeError):
    pass


def execute(args, cwd):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise CandidateError(f"Command failed ({result.returncode}): {args[0]}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def clean_commit(repo):
    if execute(["git", "status", "--porcelain", "--untracked-files=all"], repo).strip():
        raise CandidateError(f"Commit or isolate changes before packaging: {repo.name}")
    return execute(["git", "rev-parse", "HEAD"], repo).strip()


def candidate_metadata(meta):
    if meta.get("applicationId") != "com.we.meet" or meta.get("variantName") != "debug":
        raise CandidateError("Only the normal com.we.meet Debug app may be delivered; fixture packages are rejected")
    elements = meta.get("elements", [])
    if len(elements) != 1:
        raise CandidateError("Expected exactly one APK")
    item = elements[0]
    if item.get("outputFile") != "app-debug.apk":
        raise CandidateError("Unexpected APK filename")
    version = item.get("versionName", "")
    code = item.get("versionCode")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", version) or type(code) is not int or code < 1:
        raise CandidateError("Invalid application version")
    return version, code


def audit_apk(badging, signing, version, code):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != ("com.we.meet", str(code), version):
        raise CandidateError("APK identity/version differs from Gradle output metadata")
    if not re.search(r"^application-debuggable", badging, re.M):
        raise CandidateError("Expected an internal Debug APK")
    if not re.search(r"^Verifies\s*$", signing, re.M):
        raise CandidateError("APK signature verification did not pass")
    if not re.search(r"^Signer #1 certificate DN: .*CN=Android Debug(?:,|\s*$)", signing, re.M):
        raise CandidateError("This command archives Android Debug candidates only")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$", signing, re.M)
    if len(certificates) != 1:
        raise CandidateError("Expected exactly one verified signer")
    return certificates[0]


def test_summary(directory):
    paths = sorted(directory.glob("TEST-*.xml"))
    if not paths:
        raise CandidateError("Unit test results are missing")
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in paths:
        root = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(root.get(key, 0))
    if counts["tests"] <= 0 or counts["failures"] or counts["errors"]:
        raise CandidateError("Unit tests did not pass")
    return counts


def verify_archive(directory):
    manifest = json.loads((directory / "candidate.json").read_text("utf-8"))
    name = manifest["artifact"]["name"]
    if Path(name).name != name or name not in ("app-debug.apk",):
        raise CandidateError("Invalid archived artifact name")
    apk = directory / name
    if apk.stat().st_size != manifest["artifact"]["bytes"] or digest(apk) != manifest["artifact"]["sha256"]:
        raise CandidateError("Archived APK differs from its manifest")
    checksums = (directory / "SHA256SUMS").read_text("ascii")
    if checksums != f"{digest(apk)}  {name}\n{digest(directory / 'candidate.json')}  candidate.json\n":
        raise CandidateError("Checksum file differs from archive contents")
    return manifest


def sdk_path(repo, supplied):
    if supplied:
        return Path(supplied).resolve()
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return Path(os.environ[key]).resolve()
    local = repo / "local.properties"
    if local.exists():
        for line in local.read_text("utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")).resolve()
    raise CandidateError("Set ANDROID_HOME or pass --android-sdk")


def build(repo, sdk, build_tools):
    sdk_repo = repo.parent / "jusi-light-im"
    commit = clean_commit(repo)
    sdk_commit = clean_commit(sdk_repo)
    tools = sdk / "build-tools" / build_tools
    aapt = tools / ("aapt.exe" if os.name == "nt" else "aapt")
    apksigner = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    if not aapt.is_file() or not apksigner.is_file():
        raise CandidateError(f"Android Build Tools {build_tools} are required")
    execute([str(repo / ("gradlew.bat" if os.name == "nt" else "gradlew")),
             "checkDesignTokens", ":app:testDebugUnitTest", ":app:assembleDebug", "--console=plain"], repo)
    outputs = repo / "app/build/outputs/apk/debug"
    version, code = candidate_metadata(json.loads((outputs / "output-metadata.json").read_text("utf-8")))
    apk = outputs / "app-debug.apk"
    counts = test_summary(repo / "app/build/test-results/testDebugUnitTest")
    configuration = {}
    for name in ("local.properties", "gradle.properties", "app/build/generated/source/buildConfig/debug/com/we/meet/BuildConfig.java"):
        path = repo / name
        if path.is_file():
            configuration[name] = digest(path)
    release = (repo / "release").resolve()
    if not release.is_relative_to(repo.resolve()):
        raise CandidateError("Candidate directory must remain within this repository")
    release.mkdir(exist_ok=True)
    destination = release / f"{version}-{commit[:12]}"
    if destination.exists():
        raise CandidateError(f"Archive already exists; use verify mode: {destination}")
    # Audit the frozen copy that will be delivered, even if another build replaces its output.
    with tempfile.NamedTemporaryFile(prefix=".candidate-", suffix=".apk", dir=release, delete=False) as temporary:
        frozen = Path(temporary.name).resolve()
    try:
        shutil.copyfile(apk, frozen)
        signer = audit_apk(execute([str(aapt), "dump", "badging", str(frozen)], repo),
                           execute([str(apksigner), "verify", "--verbose", "--print-certs", str(frozen)], repo), version, code)
        if clean_commit(repo) != commit or clean_commit(sdk_repo) != sdk_commit:
            raise CandidateError("Source commits changed during packaging")
        for name, expected in configuration.items():
            if digest(repo / name) != expected:
                raise CandidateError("Build configuration changed during packaging")
        destination.mkdir()
        archived = destination / "app-debug.apk"
        frozen.replace(archived)
    finally:
        if frozen.is_file():
            frozen.unlink()
    manifest = {
        "schema": "work-android-candidate/v1", "kind": "android-debug-internal",
        "source_commit": commit, "im_sdk_commit": sdk_commit,
        "application_id": "com.we.meet", "version": version, "version_code": code,
        "signer_sha256": signer, "build_tools": build_tools,
        "configuration_sha256": configuration, "unit_tests": counts,
        "artifact": {"name": archived.name, "bytes": archived.stat().st_size, "sha256": digest(archived)},
    }
    (destination / "candidate.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    (destination / "SHA256SUMS").write_text(f"{digest(archived)}  app-debug.apk\n{digest(destination / 'candidate.json')}  candidate.json\n", encoding="ascii", newline="\n")
    verify_archive(destination)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-sdk")
    parser.add_argument("--build-tools", default="34.0.0")
    parser.add_argument("--verify", type=Path, help="Verify an existing archive without building/installing/uploading")
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"\d+\.\d+\.\d+", args.build_tools):
            raise CandidateError("Invalid Build Tools version")
        if args.verify:
            manifest = verify_archive(args.verify.resolve())
            print(json.dumps({"verified": str(args.verify), "sha256": manifest["artifact"]["sha256"]}))
        else:
            repo = Path(__file__).resolve().parents[1]
            destination = build(repo, sdk_path(repo, args.android_sdk), args.build_tools)
            print(json.dumps({"candidate": str(destination)}))
    except (CandidateError, OSError, ValueError, KeyError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

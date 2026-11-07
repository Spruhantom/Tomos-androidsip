#!/usr/bin/env python3
"""Check that a newly built TomOS SIP APK can update the latest published APK."""
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPOSITORY = "Spruhantom/Tomos-androidsip"
APK = Path("app/build/outputs/apk/debug/app-debug.apk")
EXPECTED_PACKAGE = "uk.co.tomos.sip"


def command_output(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def find_tool(name):
    roots = [Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")),
             Path(os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk"))]
    candidates = []
    for root in roots:
        candidates.extend(root.glob(f"build-tools/*/{name}"))
        candidates.extend(root.glob(f"cmdline-tools/*/bin/{name}"))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"Android SDK tool not found: {name}")


def apk_info(path):
    aapt = find_tool("aapt")
    apksigner = find_tool("apksigner")
    badging = command_output([aapt, "dump", "badging", str(path)])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)'", badging, re.M)
    if not package:
        raise RuntimeError(f"Could not read package/version from {path}")
    certs = command_output([apksigner, "verify", "--print-certs", str(path)])
    cert = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F:]+)", certs)
    if not cert:
        raise RuntimeError(f"Could not read signing certificate from {path}")
    return package.group(1), int(package.group(2)), cert.group(1).replace(":", "").lower()


def main():
    if not APK.is_file():
        raise RuntimeError(f"Built APK missing: {APK}")
    package, version, certificate = apk_info(APK)
    if package != EXPECTED_PACKAGE:
        raise RuntimeError(f"Wrong package ID: {package}; expected {EXPECTED_PACKAGE}")

    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "TomOS-SIP-update-check"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        releases = json.load(response)

    candidates = []
    for release in releases:
        if release.get("draft"):
            continue
        match = re.search(r"-build-(\d+)$", release.get("tag_name", ""))
        apk_asset = next((asset for asset in release.get("assets", [])
                          if asset.get("name", "").lower().endswith(".apk")), None)
        if match and apk_asset:
            candidates.append((int(match.group(1)), release, apk_asset))
    if not candidates:
        raise RuntimeError("No published TomOS SIP build release with an APK was found")

    latest_code, release, asset = max(candidates, key=lambda item: item[0])
    if version <= latest_code:
        raise RuntimeError(
            f"New APK versionCode {version} is not higher than published build {latest_code}"
        )

    download = urllib.request.Request(
        asset["browser_download_url"],
        headers={"User-Agent": "TomOS-SIP-update-check", "Accept": "application/octet-stream"},
    )
    with tempfile.TemporaryDirectory() as directory:
        previous_apk = Path(directory) / "published.apk"
        with urllib.request.urlopen(download, timeout=90) as response, previous_apk.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
        old_package, old_version, old_certificate = apk_info(previous_apk)

    if old_package != package:
        raise RuntimeError(f"Package ID changed: published={old_package}, new={package}")
    if certificate != old_certificate:
        raise RuntimeError(
            "APK signing certificate mismatch. Android will reject this as an in-place update. "
            f"Published={old_certificate}; new={certificate}"
        )
    if old_version != latest_code:
        raise RuntimeError(
            f"Release tag build number {latest_code} does not match APK versionCode {old_version}"
        )

    print("UPDATE COMPATIBILITY CHECK PASSED")
    print(f"Package: {package}")
    print(f"Published versionCode: {old_version}")
    print(f"New versionCode: {version}")
    print(f"Signing certificate SHA-256: {certificate}")
    print(f"Compared against: {release.get('html_url', release.get('tag_name'))}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"UPDATE COMPATIBILITY CHECK FAILED: {error}", file=sys.stderr)
        sys.exit(1)

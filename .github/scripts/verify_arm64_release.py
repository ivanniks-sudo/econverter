"""Verify a signed ARM64 release; write public build provenance, never key material."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def fingerprint(value):
    value = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Expected a SHA-256 certificate fingerprint (64 hexadecimal digits)")
    return value


def verify(apk, tools, expected):
    expected = fingerprint(expected)
    signature = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.M)
    if len(certificates) != 1 or fingerprint(certificates[0]) != expected:
        raise ValueError("Signing certificate does not match RELEASE_CERT_SHA256")
    run(str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk))
    badging = run(str(tools / "aapt2"), "dump", "badging", str(apk))
    for pattern in (
        r"^package: name='com\.econverter\.app' versionCode='1704' versionName='1\.5\.0'",
        r"^(?:minSdkVersion|sdkVersion):'24'$",
        r"^targetSdkVersion:'37'$",
        r"^native-code: 'arm64-v8a'\s*$",
    ):
        if not re.search(pattern, badging, re.M):
            raise ValueError("Unexpected application ID, version, SDK or architecture")
    if "application-debuggable" in badging:
        raise ValueError("Debuggable APK is not a release build")
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    if abis != {"arm64-v8a"}:
        raise ValueError(f"Unexpected native architectures: {sorted(abis)}")
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {"commit": run("git", "rev-parse", "HEAD").strip(), "apk": apk.name,
            "bytes": apk.stat().st_size, "sha256": digest,
            "certificate_sha256": expected, "abis": sorted(abis),
            "application_id": "com.econverter.app", "version_name": "1.5.0",
            "version_code": 1704, "min_sdk": 24, "target_sdk": 37}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('tools', type=Path)
    args = parser.parse_args()
    report = verify(args.apk, args.tools, os.environ['RELEASE_CERT_SHA256'])
    (args.apk.parent / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.apk.parent / 'SHA256SUMS').write_text(f"{report['sha256']}  {args.apk.name}\n")
    print(json.dumps(report, indent=2))

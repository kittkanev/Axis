import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def publish(apk, code, name, package_name, tag, changelog):
    destination = Path("/var/lib/axis-updates")
    if not re.fullmatch(r"Axis-[A-Za-z0-9._-]+\.apk", apk.name):
        raise ValueError("Expected an Axis APK filename")
    if not re.fullmatch(r"v[A-Za-z0-9._-]+", tag):
        raise ValueError("Expected a version tag")
    checksum = sha256(apk)
    target = destination / apk.name
    if target.exists() and sha256(target) != checksum:
        raise ValueError("APK filename already exists with different content")
    manifest = {"versionCode": int(code), "versionName": name, "packageName": package_name,
                "file": apk.name, "sha256": checksum, "tag": tag, "changelog": changelog[:8192]}
    with tempfile.TemporaryDirectory(dir=destination, prefix=".publish-") as work:
        staged_apk = Path(work) / apk.name
        if not target.exists():
            shutil.copyfile(apk, staged_apk)
            staged_apk.chmod(0o644)
            os.link(staged_apk, target)
        staged_manifest = Path(work) / "version.json"
        staged_manifest.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        staged_manifest.chmod(0o644)
        staged_manifest.replace(destination / "version.json")
    print(f"Serving {apk.name} with SHA-256 {checksum}")


if __name__ == "__main__":
    if len(sys.argv) != 7:
        sys.exit("Usage: publish-apk.py APK VERSION_CODE VERSION_NAME PACKAGE_NAME TAG CHANGELOG_FILE")
    _, path, code, name, package, tag, notes = sys.argv
    publish(Path(path), code, name, package, tag, Path(notes).read_text(encoding="utf-8"))

#!/usr/bin/env python3
"""Fetch and authenticate the exact Termux packages used by the ARM64 snapshot."""
from __future__ import annotations

import gzip
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import urllib.request

REPOSITORY = "https://packages.termux.dev/apt/termux-main"
SUITE_REL = "main/binary-aarch64/Packages.gz"
INDEX_REL = "dists/stable/" + SUITE_REL


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "dsh-source-build/1"})
    with urllib.request.urlopen(request, timeout=180) as response:
        return response.read()


def parse_stanzas(text: str):
    for block in text.split("\n\n"):
        record = {}
        key = None
        for line in block.splitlines():
            if line[:1].isspace() and key:
                record[key] += "\n" + line[1:]
            elif ": " in line:
                key, value = line.split(": ", 1)
                record[key] = value
        if "Package" in record:
            yield record


def signed_sha256(release_text: str, relative_path: str) -> tuple[str, int]:
    marker = "SHA256:\n"
    if marker not in release_text:
        raise ValueError("verified Termux Release has no SHA256 section")
    section = release_text.split(marker, 1)[1]
    for line in section.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[2] == relative_path:
            return fields[0].lower(), int(fields[1])
        if not line.strip():
            break
    raise ValueError(f"signed Termux Release does not list {relative_path}")


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: prepare-termux-signed-repo.py <extracted-bootstrap-usr> <webroot> <preinstall.json>")
    usr, webroot, preinstall_file = map(pathlib.Path, sys.argv[1:])
    key_dir = usr / "share" / "termux-keyring"
    keys = sorted(key_dir.glob("*.gpg"))
    if not keys:
        raise ValueError(f"official Termux signing keys are missing from {key_dir}")

    with tempfile.TemporaryDirectory(prefix="termux-gpg-") as temporary:
        home = pathlib.Path(temporary)
        home.chmod(0o700)
        for key in keys:
            subprocess.run(["gpg", "--homedir", str(home), "--batch", "--import", str(key)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        keyring = home / "termux-keys.gpg"
        with keyring.open("wb") as output:
            subprocess.run(["gpg", "--homedir", str(home), "--batch", "--export"],
                           check=True, stdout=output)

        inrelease = fetch(REPOSITORY + "/dists/stable/InRelease")
        inrelease_path = home / "InRelease"
        inrelease_path.write_bytes(inrelease)
        release_path = home / "Release"
        subprocess.run(["gpgv", "--keyring", str(keyring), "--output", str(release_path), str(inrelease_path)],
                       check=True)
        release_text = release_path.read_text(encoding="utf-8")
    # Release checksums are relative to the suite root; the repository URL
    # and local apt layout additionally include dists/stable/.
    expected_index_hash, expected_index_size = signed_sha256(release_text, SUITE_REL)

    index_gz = fetch(REPOSITORY + "/" + INDEX_REL)
    actual_index_hash = hashlib.sha256(index_gz).hexdigest()
    if len(index_gz) != expected_index_size or actual_index_hash != expected_index_hash:
        raise ValueError("Termux Packages.gz does not match the signed Release file")
    records = {record["Package"]: record for record in parse_stanzas(gzip.decompress(index_gz).decode("utf-8"))}
    config = json.loads(preinstall_file.read_text(encoding="utf-8"))
    targets = config["targets"]
    missing_required = [name for name in config.get("requiredTargets", []) if name not in records]
    if missing_required:
        raise ValueError(f"required Termux packages are absent from the signed index: {', '.join(missing_required)}")
    needed = set()
    queue = list(targets)
    while queue:
        name = queue.pop(0)
        if name in needed:
            continue
        record = records.get(name)
        if not record:
            print(f"skip package absent from signed index: {name}")
            continue
        needed.add(name)
        for dependency in record.get("Depends", "").split(","):
            candidate = dependency.strip().split(" ")[0]
            if candidate and "|" not in candidate and candidate not in needed:
                queue.append(candidate)

    webroot.mkdir(parents=True, exist_ok=True)
    index_path = webroot / INDEX_REL
    index_path.parent.mkdir(parents=True, exist_ok=True)
    index_path.write_bytes(index_gz)
    package_provenance = []
    for name in sorted(needed):
        record = records[name]
        filename = record.get("Filename")
        if not filename:
            raise ValueError(f"signed package index has no Filename for {name}")
        relative = pathlib.PurePosixPath(filename)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"unsafe Termux package filename: {filename}")
        expected = record.get("SHA256", "").lower()
        if len(expected) != 64:
            raise ValueError(f"signed package index has no SHA256 for {name}")
        data = fetch(REPOSITORY + "/" + filename)
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected:
            raise ValueError(f"Termux package digest mismatch: {name} {actual} != {expected}")
        target = webroot.joinpath(*relative.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        package_provenance.append({
            "package": name,
            "version": record.get("Version"),
            "architecture": record.get("Architecture"),
            "source": record.get("Source", name),
            "filename": filename,
            "sha256": actual,
        })
        print(f"verified signed Termux package {name}={record.get('Version')} sha256={actual}")

    inrelease_hash = hashlib.sha256(inrelease).hexdigest()
    provenance = {
        "repository": REPOSITORY,
        "suite": "stable",
        "index": {"path": INDEX_REL, "sha256": actual_index_hash, "signedReleaseSha256": inrelease_hash},
        "packageCount": len(package_provenance),
        "packages": package_provenance,
    }
    (webroot.parent / "termux-packages-provenance.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8"
    )
    print(f"verified {len(package_provenance)} Termux packages from signed InRelease {inrelease_hash}")


if __name__ == "__main__":
    main()

#!/bin/sh
# CMS v2 built-in bootstrap — one-time base environment for proot/Alpine aarch64.
# Idempotent: apk add --no-cache skips already-installed packages; safe to re-run.
# Marker .bootstrap.done is written next to this script; the host (Android) detects
# it under <homePath>/cms/_bootstrap/ to skip re-running on subsequent deploys.
set -e

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)
echo "[cms-bootstrap] installing base runtime (python3 / py3-pip / nodejs)..."
apk add --no-cache python3 py3-pip nodejs || {
    echo "[cms-bootstrap] FAILED: apk add failed (no network or mirror unavailable)"
    exit 1
}

# Optional isolated venv (modules may use system python3 by default).
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[cms-bootstrap] creating /root/cms-venv..."
    python3 -m venv /root/cms-venv || echo "[cms-bootstrap] WARN: venv creation failed (system python still usable)"
fi

# Common utilities (best effort; ignore failures).
apk add --no-cache curl wget jq || true

echo "[cms-bootstrap] base environment ready:"
echo "  python3 = $(python3 --version 2>&1)"
echo "  node    = $(node --version 2>&1)"
echo "  pip     = $(pip3 --version 2>&1 | head -1)"
touch "$BOOT_DIR/.bootstrap.done"
echo "[cms-bootstrap] marker written: $BOOT_DIR/.bootstrap.done"

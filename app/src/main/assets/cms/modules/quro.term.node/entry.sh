#!/bin/sh
PORT="${QURO_NODE_PORT:-${1:-8766}}"
echo "[quro.term.node] listening 0.0.0.0:$PORT"
exec node /root/cms/quro.term.node/backend.js "$PORT"

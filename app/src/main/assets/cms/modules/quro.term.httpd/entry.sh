#!/bin/sh
PORT="${QURO_HTTP_PORT:-${1:-8080}}"
DIR="${QURO_SERVE_DIR:-${2:-/root/cms/quro.term.httpd/www}}"
mkdir -p "$DIR"
if [ ! -f "$DIR/index.html" ]; then
  echo "<h1>Quro Terminal HTTPD</h1><p>ready</p>" > "$DIR/index.html"
fi
echo "[quro.term.httpd] dir=$DIR port=$PORT"
cd "$DIR"
exec python3 -m http.server "$PORT" --bind 0.0.0.0

#!/bin/sh
# Quro CMS 终端模块：静态文件 HTTP 服务（终端作为后端）
# 默认端口 8123（避开引擎 cms-static 的 8080 与 quro.term.python 的 8765 / node 的 8766）；
# 显式指定 QURO_HTTP_PORT 或位置参数 $1 时优先使用，否则自动探测 8123 起 5 个候选空闲端口。
if [ -n "${QURO_HTTP_PORT:-}" ]; then
  PORT="$QURO_HTTP_PORT"
elif [ -n "${1:-}" ]; then
  PORT="$1"
else
  PORT=8123
  for cand in 8123 8124 8125 8126 8127; do
    if ! (exec 3<>/dev/tcp/127.0.0.1/$cand) 2>/dev/null; then
      PORT=$cand; break
    fi
  done
fi
DIR="${QURO_SERVE_DIR:-${2:-/root/cms/quro.term.httpd/www}}"
mkdir -p "$DIR"
if [ ! -f "$DIR/index.html" ]; then
  echo "<h1>Quro Terminal HTTPD</h1><p>ready</p>" > "$DIR/index.html"
fi
echo "[quro.term.httpd] dir=$DIR port=$PORT"
cd "$DIR"
exec python3 -m http.server "$PORT" --bind 0.0.0.0

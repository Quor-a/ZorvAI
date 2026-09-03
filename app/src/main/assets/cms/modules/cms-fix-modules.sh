#!/bin/sh
# CMS 终端模块一键修复
# 用法: sh cms-fix-modules.sh

set -e

echo "🔧 修复 bootstrap.sh..."
BOOT="/root/cms/_bootstrap/bootstrap.sh"
ENG="/root/cms/_engine/bootstrap.sh"

# 删除有问题的 DNS heredoc || { } 块
sed -i '/cat > \/etc\/resolv.conf.*|| {/,/}/d' "$BOOT" 2>/dev/null
sed -i '/cat > \/etc\/resolv.conf.*|| {/,/}/d' "$ENG" 2>/dev/null

# 确保 DNS heredoc 语法正确
if ! sh -n "$BOOT" 2>/dev/null; then
    # 在 DNS if 块内插入正确的 heredoc
    sed -i '/\[ ! -f \/etc\/resolv.conf\]/,/fi/{
        /cat > \/etc\/resolv.conf/a\    cat > /etc/resolv.conf 2>/dev/null << '"'"'DNS'"'"'\nnameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 223.5.5.5\nnameserver 1.1.1.1\nnameserver 9.9.9.9\nDNS
    }' "$BOOT" 2>/dev/null
fi

# 同步到 _engine
cp "$BOOT" "$ENG" 2>/dev/null || true
sh -n "$BOOT" && echo "  ✅ bootstrap.sh 语法OK" || echo "  ⚠️ 需手动检查"

echo "🔧 修复 httpd entry.sh..."
cat > /root/cms/quro.term.httpd/entry.sh << 'HTTPD'
#!/bin/sh
# Quro CMS 终端模块：静态文件 HTTP 服务（终端作为后端）
# 默认端口 8123（避开引擎 cms-static 的 8080 与 quro.term.python 的 8765 / node 的 8766）
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
HTTPD
chmod +x /root/cms/quro.term.httpd/entry.sh
echo "  ✅ httpd entry.sh 修好（默认端口 8123，自动探测空闲端口）"

echo "🔧 修复 node backend.js + entry.sh..."
cat > /root/cms/quro.term.node/backend.js << 'NODEJS'
const port = parseInt(process.argv[2] || '8766', 10);
const http = require('http');
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({module: 'quro.term.node', status: 'ok', echo: req.url, body: body, method: req.method}));
  });
});
server.listen(port, '0.0.0.0', () => console.log('node backend on ' + port));
NODEJS

cat > /root/cms/quro.term.node/entry.sh << 'NODESH'
#!/bin/sh
PORT="${QURO_NODE_PORT:-${1:-8766}}"
echo "[quro.term.node] listening 0.0.0.0:$PORT"
exec node /root/cms/quro.term.node/backend.js "$PORT"
NODESH
chmod +x /root/cms/quro.term.node/entry.sh
echo "  ✅ node 修好"

echo "🔧 验证..."
sh -n /root/cms/quro.term.httpd/entry.sh && echo "  ✅ httpd 语法OK"
sh -n /root/cms/quro.term.node/entry.sh && echo "  ✅ node 语法OK"

echo ""
echo "🎉 修复完成！"
echo "  httpd: sh /root/cms/quro.term.httpd/entry.sh [port]"
echo "  node:  sh /root/cms/quro.term.node/entry.sh [port]"

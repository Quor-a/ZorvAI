#!/system/bin/sh
export PATH=/data/user/0/com.ai.assistance.quro/files/usr/bin:/system/bin
export LD_LIBRARY_PATH=/data/user/0/com.ai.assistance.quro/files/usr/lib
export HOME=/data/user/0/com.ai.assistance.quro/files/home
export OPENSSL_CONF=/data/user/0/com.ai.assistance.quro/files/usr/etc/tls/openssl.cnf
export TMPDIR=/data/user/0/com.ai.assistance.quro/files/home/tmp
mkdir -p $TMPDIR
cd /data/user/0/com.ai.assistance.quro/files/home
node --expose-internals /data/user/0/com.ai.assistance.quro/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile headless "$(cat /data/user/0/com.ai.assistance.quro/files/home/task.txt)" 2>&1 | tail -30
echo "=== EXIT: $? ==="
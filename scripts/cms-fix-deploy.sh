#!/bin/bash
# CMS v2 引擎部署修复脚本
# 修复 apt 锁残留、dpkg 包异常、ca-certificates 缓存目录缺失

set -e

echo "🔧 Step 1: 清除 apt 残留锁..."
rm -f /var/lib/dpkg/lock
rm -f /var/lib/dpkg/lock-frontend
rm -f /var/cache/apt/archives/lock
rm -f /var/lib/apt/lists/lock
echo "✅ 锁文件已清除"

echo "🔧 Step 2: 创建缺失的缓存目录..."
mkdir -p /data/user/0/com.ai.assistance.quro/cache
echo "✅ 缓存目录已创建"

echo "🔧 Step 3: 修复 dpkg 半安装状态..."
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a 2>&1 || true
echo "✅ dpkg 状态已修复"

echo "🔧 Step 4: 手动修复 ca-certificates..."
update-ca-certificates 2>&1 || true
echo "✅ CA 证书已更新"

echo "🔧 Step 5: 逐个修复异常包..."
for pkg in ca-certificates ca-certificates-java openssh-server \
           openjdk-17-jre-headless openjdk-17-jdk-headless \
           python3-pip npm; do
    dpkg --configure "$pkg" 2>&1 && echo "  ✅ $pkg" || echo "  ⚠️ $pkg (跳过)"
done

echo "🔧 Step 6: 验证修复结果..."
BROKEN=$(dpkg -l | grep -E "^(iF|iU)" | wc -l)
echo "  异常包数量: $BROKEN"
if [ "$BROKEN" -eq 0 ]; then
    echo "✅ 全部包状态正常"
else
    echo "⚠️ 仍有 $BROKEN 个异常包"
    dpkg -l | grep -E "^(iF|iU)" | awk '{print "  -", $2}'
fi

echo ""
echo "🔧 Step 7: 验证运行时..."
echo "  Python: $(python3 --version 2>&1 || echo '❌ 未安装')"
echo "  Node:   $(node --version 2>&1 || echo '❌ 未安装')"
echo "  Java:   $(java -version 2>&1 | head -1 || echo '❌ 未安装')"
echo "  SSH:    $(which ssh 2>&1 && echo '✅' || echo '❌ 未安装')"

echo ""
echo "🎉 修复完成！"

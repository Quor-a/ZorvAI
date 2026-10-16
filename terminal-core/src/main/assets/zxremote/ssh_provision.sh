#!/bin/bash
# ZorvAI 终端 · 远程 SSH：安装并启动 sshd（幂等，可反复执行）
# 由 RemoteAccessCenter 经 proot 一次性执行通道调用；退出码 = 执行结果。
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C
SSH_PORT=@SSH_PORT@
SSH_PASSWORD=@PASSWORD@

log(){ echo "[ssh] $*"; }
die(){ local c="$1"; shift; echo "[ssh] 失败：$*"; exit "$c"; }

log "步骤 1/5 · 检查 openssh-server"
if ! command -v sshd >/dev/null 2>&1 && [ ! -x /usr/sbin/sshd ]; then
  log "未安装，开始 apt-get（首次约 1~3 分钟，请保持应用在前台或小窗）"
  apt-get update -y || die 11 "apt-get update 失败（检查网络或到「环境配置」换镜像源）"
  apt-get install -y --no-install-recommends openssh-server || die 12 "openssh-server 安装失败"
else
  log "已安装，跳过"
fi
SSHD_BIN="$(command -v sshd 2>/dev/null)"
[ -n "$SSHD_BIN" ] || SSHD_BIN=/usr/sbin/sshd
[ -x "$SSHD_BIN" ] || die 13 "找不到 sshd 可执行文件"

log "步骤 2/5 · 生成主机密钥与运行目录"
mkdir -p /run/sshd /etc/ssh/sshd_config.d
ssh-keygen -A >/dev/null 2>&1 || true
[ -f /etc/ssh/ssh_host_ed25519_key ] || log "警告：主机密钥疑似未生成，可能导致客户端连接被拒"

log "步骤 3/5 · 写入 sshd 配置（端口 $SSH_PORT / 允许 root / 关闭 PAM）"
# 关键：Ubuntu 的 sshd 默认 UsePAM yes，而 proot 下 PAM 基本必然认证失败 ——
# 这正是「sshd 起来了但怎么都连不上」的根因，必须显式关掉。
cat > /etc/ssh/sshd_config.d/99-zorvai.conf <<'ZORVAI_SSH_EOF'
Port @SSH_PORT@
ListenAddress 0.0.0.0
PermitRootLogin yes
PasswordAuthentication yes
PermitEmptyPasswords no
UsePAM no
X11Forwarding yes
PrintMotd no
ZORVAI_SSH_EOF
CONF=/etc/ssh/sshd_config
[ -f "$CONF" ] || die 14 "缺少 $CONF"
# drop-in 只有被主配置 Include 才生效（Ubuntu 22.04+ 默认已有；没有就补上）
grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' "$CONF" \
  || sed -i '1i Include /etc/ssh/sshd_config.d/*.conf' "$CONF"
# OpenSSH 取「先出现」的值：drop-in 排在最前已足够，但为免歧义仍把主配置里的同名指令注释掉
sed -i -E 's/^[[:space:]]*(Port|ListenAddress|PermitRootLogin|PasswordAuthentication|UsePAM)[[:space:]]/# &/' "$CONF" 2>/dev/null || true

log "步骤 4/5 · 设置 root 登录密码"
# 没有密码就没有「密码登录」这条路 —— 旧实现漏了这一步，是连不上的第二个根因。
echo "root:$SSH_PASSWORD" | chpasswd || die 15 "chpasswd 失败（无法设置 root 密码）"

log "步骤 5/5 · 重启 sshd 并校验"
pkill -x sshd >/dev/null 2>&1 || true
sleep 1
rm -f /run/sshd/sshd.pid 2>/dev/null
nohup "$SSHD_BIN" -E /var/log/sshd-zorvai.log >/dev/null 2>&1 &
sleep 2
if pgrep -x sshd >/dev/null 2>&1; then
  echo "[ssh] 已启动 · 端口 $SSH_PORT · 用户 root · 密码 $SSH_PASSWORD"
  exit 0
fi
echo "[ssh] 失败：sshd 未能在后台驻留，日志如下"
tail -n 40 /var/log/sshd-zorvai.log 2>/dev/null
exit 16

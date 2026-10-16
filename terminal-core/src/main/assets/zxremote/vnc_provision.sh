#!/bin/bash
# ZorvAI 终端 · 远程 VNC：安装并启动 tigervnc（幂等，可反复执行）
# 由 RemoteAccessCenter 经 proot 一次性执行通道调用；退出码 = 执行结果。
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C
VNC_DISPLAY=@VNC_DISPLAY@
VNC_PORT=@VNC_PORT@
VNC_PASSWORD=@PASSWORD@

log(){ echo "[vnc] $*"; }
die(){ local c="$1"; shift; echo "[vnc] 失败：$*"; exit "$c"; }

log "步骤 1/5 · 检查 VNC 与最小桌面组件"
need=0
for b in vncserver vncpasswd Xvnc; do command -v "$b" >/dev/null 2>&1 || need=1; done
command -v xterm >/dev/null 2>&1 || need=1
command -v twm >/dev/null 2>&1 || need=1
if [ "$need" = "1" ]; then
  log "缺少组件，开始 apt-get（首次约 2~5 分钟，请保持应用在前台或小窗）"
  apt-get update -y || die 21 "apt-get update 失败（检查网络或到「环境配置」换镜像源）"
  # 只装 VNC 不带窗口管理器 => 连上是全黑屏。旧实现正是漏了 twm/xterm，
  # 这里补一个「极简桌面」（几 MB），保证 VNC 连上就有可用桌面。
  apt-get install -y --no-install-recommends \
      tigervnc-standalone-server tigervnc-common xauth \
      twm xterm x11-utils xfonts-base \
    || die 22 "VNC 组件安装失败"
else
  log "已安装，跳过"
fi
command -v vncserver >/dev/null 2>&1 || die 23 "未找到 vncserver（tigervnc-standalone-server 安装异常）"

log "步骤 2/5 · 写入 VNC 密码"
mkdir -p /root/.vnc
printf '%s\n%s\n' "$VNC_PASSWORD" "$VNC_PASSWORD" | vncpasswd -f > /root/.vnc/passwd 2>/dev/null \
  || die 24 "vncpasswd 失败"
chmod 600 /root/.vnc/passwd

log "步骤 3/5 · 写入 xstartup（twm 窗口管理器 + xterm 终端）"
cat > /root/.vnc/xstartup <<'ZORVAI_VNC_EOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
[ -r "$HOME/.Xresources" ] && xrdb "$HOME/.Xresources" >/dev/null 2>&1
if command -v twm >/dev/null 2>&1; then twm & fi
if command -v xterm >/dev/null 2>&1; then
  exec xterm -geometry 110x32 -fn fixed
fi
exec sleep infinity
ZORVAI_VNC_EOF
chmod +x /root/.vnc/xstartup

log "步骤 4/5 · 重启 Xvnc（显示 :$VNC_DISPLAY → 端口 $VNC_PORT）"
vncserver -kill ":$VNC_DISPLAY" >/dev/null 2>&1 || true
sleep 1
rm -f "/tmp/.X${VNC_DISPLAY}-lock" 2>/dev/null
mkdir -p /tmp/.X11-unix
rm -f "/tmp/.X11-unix/X${VNC_DISPLAY}" 2>/dev/null
nohup vncserver ":$VNC_DISPLAY" -geometry 1280x800 -depth 24 -localhost no \
  >/var/log/vnc-zorvai.log 2>&1 &
sleep 4

log "步骤 5/5 · 校验"
if pgrep -f Xvnc >/dev/null 2>&1; then
  echo "[vnc] 已启动 · 端口 $VNC_PORT · 密码 $VNC_PASSWORD"
  exit 0
fi
echo "[vnc] 失败：Xvnc 未能在后台驻留，日志如下"
tail -n 40 /var/log/vnc-zorvai.log 2>/dev/null
tail -n 20 /root/.vnc/*.log 2>/dev/null
exit 25

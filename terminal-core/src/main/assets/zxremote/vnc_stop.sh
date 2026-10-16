#!/bin/bash
# ZorvAI 终端 · 停止远程 VNC
VNC_DISPLAY=@VNC_DISPLAY@
vncserver -kill ":$VNC_DISPLAY" >/dev/null 2>&1 || true
sleep 1
pkill -f Xvnc >/dev/null 2>&1 || true
rm -f "/tmp/.X${VNC_DISPLAY}-lock" "/tmp/.X11-unix/X${VNC_DISPLAY}" 2>/dev/null
if pgrep -f Xvnc >/dev/null 2>&1; then
  echo "[vnc] 停止失败：仍有 Xvnc 进程存活"
  exit 1
fi
echo "[vnc] 已停止"
exit 0

#!/bin/bash
# ZorvAI 终端 · 停止远程 SSH
pkill -x sshd >/dev/null 2>&1 || true
sleep 1
if pgrep -x sshd >/dev/null 2>&1; then
  echo "[ssh] 停止失败：仍有 sshd 进程存活"
  exit 1
fi
echo "[ssh] 已停止"
exit 0

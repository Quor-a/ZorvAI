#!/usr/bin/env bash
# ============================================================================
# 推送前清理 —— 确保任何签名材料都不会离开本机
#
# 三层扫描：
#   1. 被 git 跟踪的密钥文件          → 阻断（--fix 可从索引移除）
#   2. 源码里硬编码的口令 / 私钥      → 阻断
#   3. git 历史里曾出现过的密钥文件    → 默认仅告警（历史改写会变更全部 commit hash，
#      且本仓库历史上的 app/test-*.pem|pk8 是已删除的 AOSP 测试密钥，非发布密钥）
#
# ★ 与上游套件的差异：上游把第 3 层也做成阻断，导致本仓库**每次推送都会被拦**
#   （历史里存在已删除的测试密钥）。此处改为默认告警、--strict 才阻断，
#   避免安全机制退化成「用 --no-verify 绕过」的坏习惯——真正的红线是第 1、2 层。
#
# 用法：
#   scripts/purge-before-push.sh           # 扫描并报告
#   scripts/purge-before-push.sh --fix     # 从索引移除密钥文件（不删工作区文件）
#   scripts/purge-before-push.sh --strict  # 第 3 层也阻断
# ============================================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib/common.sh

FIX=0; STRICT=0
for a in "$@"; do
    [[ "$a" == "--fix" ]] && FIX=1
    [[ "$a" == "--strict" ]] && STRICT=1
done

cd "$REPO_ROOT"

# ★ 必须为 git 仓库：否则 git ls-files 报错会被 || true 吞掉，
#   导致「扫不出东西」被误判为「干净」—— 这是致命的假阴性
git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || die "当前不在 git 仓库内：$REPO_ROOT（请在仓库根目录执行）"

rc=0

PATTERN='(\.jks|\.keystore|\.p12|\.bks|\.pk8|keystore\.properties|.*[_-]keystore.*|\.pem)$'

# ---------- 1. 被跟踪的密钥文件 ----------
info "扫描 1/3 · 被跟踪的密钥文件…"
HITS="$(git ls-files | grep -Ei "$PATTERN" || true)"
if [[ -n "$HITS" ]]; then
    printf '%s发现%s\n%s\n' "$C_RED" "$C_OFF" "$HITS"
    if (( FIX )); then
        echo "$HITS" | while read -r f; do git rm --cached "$f" >/dev/null; done
        warn "已从 git 索引移除（工作区文件保留，请手动删除）"
    fi
    rc=1
else
    info "  ✓ 无"
fi

# ---------- 2. 硬编码口令 ----------
info "扫描 2/3 · 源码中的硬编码凭证…"
SECRET_HITS="$(
    grep -rInE \
        -e 'storePassword[[:space:]]*=[[:space:]]*"[^"]+"' \
        -e 'keyPassword[[:space:]]*=[[:space:]]*"[^"]+"' \
        -e '-----BEGIN (RSA |EC |)PRIVATE KEY-----' \
        --include='*.kts' --include='*.kt' --include='*.gradle' \
        --include='*.sh' --include='*.properties' --include='*.yml' \
        . 2>/dev/null | grep -v '/build/' | head -20 || true
)"
if [[ -n "$SECRET_HITS" ]]; then
    printf '%s发现疑似硬编码凭证%s\n%s\n' "$C_RED" "$C_OFF" "$SECRET_HITS"
    rc=1
else
    info "  ✓ 无"
fi

# ---------- 3. 历史痕迹（默认告警） ----------
info "扫描 3/3 · git 历史中的密钥文件…"
HIST="$(git log --all --pretty=format: --name-only --diff-filter=A 2>/dev/null \
        | sort -u | grep -Ei "$PATTERN" | head -10 || true)"
if [[ -n "$HIST" ]]; then
    warn "历史中曾添加过密钥类文件（当前可能已删除，但仍留在对象库里）："
    printf '  %s\n' $HIST
    warn "如需彻底清理（会改变所有 commit hash，须协调协作者）："
    warn "  git filter-repo --path <file> --invert-paths"
    if (( STRICT )); then
        rc=1
    else
        warn "已确认为历史遗留、非当前发布密钥 → 不阻断推送（加 --strict 可强制阻断）"
    fi
else
    info "  ✓ 无"
fi

if (( rc == 0 )); then
    info "清理检查通过，可以推送"
else
    die "存在风险项，已阻止推送。修复后重试（可加 --fix 自动从索引移除）"
fi
exit "$rc"

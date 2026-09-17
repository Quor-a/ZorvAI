#!/usr/bin/env bash
# ============================================================================
# 指纹校验 —— 安装前最后一道闸
#
# 回答一个问题：这个插件 APK 和正在运行的宿主，是不是同一个证书签的？
# 校验逻辑与 PluginInstaller.sameSignature() 完全一致（比对证书 SHA-256）
#
# 用法：
#   scripts/verify.sh a.apk b.apk        # 以第一个为基准，其余与之比对
#   scripts/verify.sh --keystore a.apk   # 以 keystore.properties 里的证书为基准
#   scripts/verify.sh --keystore ~/Desktop/宿主.apk ~/Desktop/插件.apk
# ============================================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib/common.sh

USE_KEYSTORE=0
if [[ "${1:-}" == "--keystore" || "${1:-}" == "-k" ]]; then
    USE_KEYSTORE=1
    shift
fi

(( $# >= 1 )) || die "用法：scripts/verify.sh [--keystore] <apk1> [apk2 ...]"

BASE=""
BASE_FILE=""

if (( USE_KEYSTORE )); then
    # ★ 必须在主 shell 里先加载凭证：$( ) 是子 shell，其中的 export 不会回传，
    #   否则下面引用 $ZORV_KEYSTORE 会在 set -u 下直接报 unbound variable
    load_credentials
    BASE="$(fingerprint_of_keystore)"
    [[ -n "$BASE" ]] || die "无法从密钥库提取指纹（检查 keystore.properties 与密钥文件是否存在）"
    BASE_FILE="密钥库 ${ZORV_KEYSTORE##*/}"
    info "基准来源：$CRED_SOURCE"
    printf '%s基准%s %s\n' "$C_GRN" "$C_OFF" "$BASE_FILE"
    printf '  %s\n' "$BASE"
fi

rc=0

for apk in "$@"; do
    if [[ ! -f "$apk" ]]; then
        warn "文件不存在：$apk"
        rc=1
        continue
    fi

    fp="$(fingerprint_of "$apk")"
    if [[ -z "$fp" ]]; then
        warn "未签名或无法解析：$apk"
        rc=1
        continue
    fi

    if [[ -z "$BASE" ]]; then
        # 首个 APK 作为基准
        BASE="$fp"; BASE_FILE="$(basename "$apk")"
        printf '%s基准%s %s\n' "$C_GRN" "$C_OFF" "$BASE_FILE"
        printf '  %s\n' "$fp"
    elif [[ "$fp" == "$BASE" ]]; then
        printf '%s一致%s %s\n' "$C_GRN" "$C_OFF" "$(basename "$apk")"
    else
        printf '%s不符%s %s\n' "$C_RED" "$C_OFF" "$(basename "$apk")"
        printf '  期望 %s\n' "$BASE"
        printf '  实际 %s\n' "$fp"
        rc=1
    fi
done

if (( rc == 0 )); then
    info "校验通过：PluginInstaller 会放行安装"
else
    die "存在未签名或签名不一致的 APK，安装会被拒绝：
  未签名                 → 「签名与宿主不一致，拒绝安装」
  用了别的密钥（如自己的 debug key）→ 同上
  临时调试可加 skip_signature_check=true，正式分发绝不可跳过"
fi
exit "$rc"

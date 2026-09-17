#!/usr/bin/env bash
# ============================================================================
# ZorvAI 签名工具包 · 公共库
#
# 安全铁律（四条，任何改动都不得违反）：
#   1. 私钥内容永不打印到 stdout / 日志 / 提交信息；只允许打印公开的证书指纹
#   2. 口令只从 keystore.properties / env.sh(chmod 600) / 环境变量读取，不得硬编码
#   3. 项目目录内绝不新增私钥文件（本仓库沿用既有的 app/zorvai_release.jks）
#   4. 校验逻辑不得弱化：同签名是宿主接受插件的唯一信任来源
#
# 凭证解析优先级：
#   1) 调用方显式传入的环境变量（CI 注入）
#   2) 项目 keystore.properties  ← 本仓库默认来源，宿主与插件共用同一把钥匙
#   3) ~/.zorvai-signing/env.sh  ← fork / 独立密钥 / CI 场景
#
# ★ 与上游套件的一处关键差异：上游假设自己被放在仓库的一个子目录里，
#   用 `cd ..` 反推仓库根，直接内联到仓库 scripts/ 后会指到仓库外面。
#   此处改用 git rev-parse 取根，安装位置不再影响行为。
# ============================================================================

set -euo pipefail

# ---------- 路径 ----------
REPO_ROOT="${ZORV_REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
export REPO_ROOT

PROJ_PROPS="$REPO_ROOT/keystore.properties"

# 外部密钥位：fork / CI 用，本仓库默认不用
export KEY_HOME="${ZORV_KEY_HOME:-$HOME/.zorvai-signing}"
DEFAULT_KEYSTORE="$KEY_HOME/zorvai-release.jks"
ENV_FILE="$KEY_HOME/env.sh"

CRED_SOURCE="未配置"

# ---------- 读取 properties（不打印口令） ----------
props_get() {  # $1 = key
    [[ -f "$PROJ_PROPS" ]] || return 0
    sed -n -E "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*(.*)\$/\1/p" "$PROJ_PROPS" \
        | head -1 | tr -d '\r'
}

# ---------- 加载凭证 ----------
load_credentials() {
    # 调用方显式设置的环境变量优先（CI 注入场景）
    local caller_ks="${ZORV_KEYSTORE:-}"
    local caller_pass="${ZORV_STORE_PASS:-}"

    # ---- 2) 项目 keystore.properties ----
    if [[ -f "$PROJ_PROPS" ]]; then
        local p_store p_pass p_alias p_keypass
        p_store="$(props_get storeFile)"
        p_pass="$(props_get storePassword)"
        p_alias="$(props_get keyAlias)"
        p_keypass="$(props_get keyPassword)"
        if [[ -n "$p_store" && -n "$p_pass" ]]; then
            ZORV_KEYSTORE="${caller_ks:-$REPO_ROOT/${p_store#../}}"
            ZORV_STORE_PASS="${caller_pass:-$p_pass}"
            ZORV_KEY_ALIAS="${ZORV_KEY_ALIAS:-${p_alias:-zorvai}}"
            ZORV_KEY_PASS="${ZORV_KEY_PASS:-${p_keypass:-$p_pass}}"
            CRED_SOURCE="keystore.properties → ${ZORV_KEYSTORE#"$REPO_ROOT"/}"
            export ZORV_KEYSTORE ZORV_STORE_PASS ZORV_KEY_ALIAS ZORV_KEY_PASS CRED_SOURCE
            return 0
        fi
    fi

    # ---- 3) 外部密钥位 ----
    if [[ -f "$ENV_FILE" ]]; then
        # shellcheck disable=SC1090
        source "$ENV_FILE"
        CRED_SOURCE="env.sh → $ZORV_KEYSTORE"
    fi

    export ZORV_KEYSTORE ZORV_STORE_PASS ZORV_KEY_ALIAS ZORV_KEY_PASS CRED_SOURCE
}

require_credentials() {
    load_credentials

    local missing=()
    [[ -n "${ZORV_KEYSTORE:-}" && -f "$ZORV_KEYSTORE" ]] \
        || missing+=("密钥库文件不存在: ${ZORV_KEYSTORE:-<未设置>}")
    [[ -n "${ZORV_STORE_PASS:-}" ]] || missing+=("ZORV_STORE_PASS 未设置")
    [[ -n "${ZORV_KEY_PASS:-}" ]]   || missing+=("ZORV_KEY_PASS 未设置")

    if (( ${#missing[@]} > 0 )); then
        die "凭证缺失：
  - ${missing[*]}

本仓库应从 keystore.properties 读取；若该文件不存在（如 fork / CI），
请先执行：scripts/setup-keystore.sh
或注入环境变量：ZORV_KEYSTORE / ZORV_STORE_PASS / ZORV_KEY_PASS"
    fi
}

# ---------- 路径归一化 ----------
# Windows 形态（D:\Android\Sdk）→ MSYS/Git Bash 形态（/d/Android/Sdk）
# 否则 "D:\Android\Sdk"/build-tools/* 这类拼接在 bash 里解析不到
to_unix_path() {
    local p="$1"
    if [[ "$p" =~ ^([A-Za-z]):[\\/](.*)$ ]]; then
        local drive="${BASH_REMATCH[1]}"
        local rest="${BASH_REMATCH[2]//\\//}"
        printf '/%s/%s' "$(printf '%s' "$drive" | tr 'A-Z' 'a-z')" "$rest"
    else
        printf '%s' "$p"
    fi
}

# 是否运行在 MSYS / Git Bash / Cygwin 下
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) IS_MSYS=1 ;;
    *)                    IS_MSYS=0 ;;
esac
export IS_MSYS

# MSYS 形态（/c/Users/x/a.apk）→ Windows 原生形态（C:/Users/x/a.apk）
#
# ★ 必需：keytool / apksigner 是 Windows 原生的 java 程序，不认 MSYS 路径，
#   直接传 /c/... 会报 java.nio.file.NoSuchFileException: \c\Users\...
#   （在真 Unix 上本函数不变换，避免误伤形如 /c/opt/... 的合法路径）
to_win_path() {
    local p="$1"
    if (( IS_MSYS )) && [[ "$p" =~ ^/([A-Za-z])/(.*)$ ]]; then
        printf '%s:/%s' "$(printf '%s' "${BASH_REMATCH[1]}" | tr 'a-z' 'A-Z')" "${BASH_REMATCH[2]}"
    else
        printf '%s' "$p"
    fi
}

# ---------- Android SDK 工具 ----------
find_build_tools() {
    local root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [[ -n "$root" ]] || die "未找到 ANDROID_HOME / ANDROID_SDK_ROOT，请先设置 Android SDK 路径"
    root="$(to_unix_path "$root")"
    [[ -d "$root/build-tools" ]] || die "SDK 下没有 build-tools 目录：$root/build-tools"
    ls -1d "$root"/build-tools/* 2>/dev/null | sort -V | tail -1
}

# 在 build-tools 目录里定位工具：Unix 无扩展名 / Windows .exe / .bat
find_build_tool() {   # $1 = build-tools 目录, $2 = 工具名
    local bt="$1" base="$2" c
    for c in "$bt/$base" "$bt/$base.exe" "$bt/$base.bat"; do
        [[ -f "$c" ]] && { printf '%s' "$c"; return 0; }
    done
    return 1
}

require_tools() {
    local bt="$1"
    command -v java >/dev/null || die "缺少 java"
    command -v keytool >/dev/null || die "缺少 keytool（随 JDK 提供）"
    BT_APKSIGNER="$(find_build_tool "$bt" apksigner)" \
        || die "缺少 apksigner（已查 $bt/apksigner[.exe|.bat]）"
    BT_ZIPALIGN="$(find_build_tool "$bt" zipalign)" \
        || die "缺少 zipalign（已查 $bt/zipalign[.exe]）——签名前必须先对齐"
    export BT_APKSIGNER BT_ZIPALIGN
}

# ---------- 输出 ----------
if [[ -t 1 ]]; then
    C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
    C_RED=""; C_GRN=""; C_YEL=""; C_DIM=""; C_OFF=""
fi

info() { printf '%s[INFO]%s %s\n'  "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%s[WARN]%s %s\n'  "$C_YEL" "$C_OFF" "$*"; }
die()  { printf '%s[FAIL]%s %s\n'  "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

# ---------- 指纹提取 ----------
# 统一输出为「大写、冒号分隔」的 SHA-256，与 ZorvAI 官方手册口径一致
#
# ★ 必须吞掉管道的非零退出：本脚本全局 set -o pipefail，
#   未签名 APK 会让 grep 返回 1，进而使命令替换整体失败，
#   在 set -e 下直接静默退出 —— 调用方收不到任何提示
fingerprint_of() {
    local apk="$1"
    local out
    out="$(
        keytool -printcert -jarfile "$(to_win_path "$apk")" 2>/dev/null \
            | grep -i 'SHA256' | head -1 \
            | sed -E 's/.*SHA256[^:]*:[[:space:]]*//I' \
            | tr -d ' \t\r' | tr 'a-f' 'A-F'
    )" || out=""
    printf '%s' "$out"
}

# 密钥库自身证书的指纹（不打印口令）
fingerprint_of_keystore() {
    load_credentials
    local out
    out="$(
        keytool -list -v -keystore "$(to_win_path "$ZORV_KEYSTORE")" \
            -alias "$ZORV_KEY_ALIAS" \
            -storepass "$ZORV_STORE_PASS" 2>/dev/null \
            | grep -i 'SHA256' | head -1 \
            | sed -E 's/.*SHA256[^:]*:[[:space:]]*//I' \
            | tr -d ' \t\r' | tr 'a-f' 'A-F'
    )" || out=""
    printf '%s' "$out"
}

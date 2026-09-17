#!/usr/bin/env bash
# ============================================================================
# 统一签名入口 —— 宿主与所有插件用同一把钥匙签名
#
# 设计要点：
#   · 幂等：产物已与目标密钥同签名就直接跳过，不重复做 zipalign/签名
#   · 私钥只在脚本内通过变量引用，从不落盘到项目目录，从不打印
#   · 签完立即校验宿主与插件的证书指纹是否一致
#
# ★ 与上游套件的差异：上游要求 Gradle 侧一律不签名、全部由本脚本补签。
#   本仓库既有的 app/build.gradle.kts 与 plugin-*/build.gradle.kts 已在 Gradle
#   阶段直签（且这是 v1.0.86~90 已发布包的签名来源），擅自改成「Gradle 不签
#   → 本脚本补签」会引入发布链路风险。因此本版保留 Gradle 直签，把本脚本定位为：
#     a) 对「故意未签名」的产物补签（如 -Punsigned 构建出的测试插件）
#     b) 对任意已产出 APK 做对齐 + 补签
#     c) 签署后的一致性校验
#
# 用法：
#   scripts/sign.sh                          # 宿主 + 全部 plugin-* 模块
#   scripts/sign.sh app                      # 只处理宿主
#   scripts/sign.sh plugin-signcheck         # 只处理某个插件模块
#   scripts/sign.sh build/.../x-unsigned.apk # 直接对已产出的 APK 补签
# ============================================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib/common.sh

require_credentials
BT="$(find_build_tools)"
require_tools "$BT"

info "仓库根：$REPO_ROOT"
info "凭证来源：$CRED_SOURCE"
info "签名密钥：${ZORV_KEYSTORE##*/} (alias=$ZORV_KEY_ALIAS)"

# 目标指纹（用于幂等判断与最终一致性校验）
EXPECTED_FP="$(fingerprint_of_keystore)"
[[ -n "$EXPECTED_FP" ]] || die "无法从密钥库提取 SHA-256 指纹，密钥库或口令可能有误"
printf '  目标指纹：%s\n' "$EXPECTED_FP"

# ---------- 分类入参：模块名 vs APK 路径 ----------
MODULES=()
APKS=()
for a in "$@"; do
    case "$a" in
        *.apk) APKS+=("$a") ;;
        *)     MODULES+=("$a") ;;
    esac
done

# 无参数 = 宿主 + 全部插件模块
if (( $# == 0 )); then
    MODULES=("app")
    for d in "$REPO_ROOT"/plugin-*; do
        [[ -f "$d/build.gradle.kts" ]] || continue
        MODULES+=("$(basename "$d")")
    done
fi

# ---------- 构建模块（产出未签名或已签名的 APK 都接受） ----------
if (( ${#MODULES[@]} > 0 )); then
    info "步骤 1/2 · 构建模块：${MODULES[*]}"
    TASKS=()
    for m in "${MODULES[@]}"; do
        if [[ "$m" == "app" ]]; then
            TASKS+=(":app:assembleFullRelease")   # 宿主只有 full flavor 有 release 变体
        else
            TASKS+=(":$m:assembleRelease")
        fi
    done
    # -Punsigned：支持该开关的模块会刻意产出 *-unsigned.apk（不支持的模块忽略之）
    ( cd "$REPO_ROOT" && ./gradlew "${TASKS[@]}" -Punsigned -q )
    info "构建完成"
else
    info "步骤 1/2 · 跳过构建（直接对给定 APK 操作）"
fi

# 收集模块产物路径
find_module_apk() {
    local m="$1" dir="$REPO_ROOT/$m/build/outputs/apk"
    [[ -d "$dir" ]] || return 0
    # 优先未签名产物，其次 release 变体（排除已签名/已对齐的中间件）
    local f
    f="$(find "$dir" -type f -name '*-unsigned.apk' 2>/dev/null | head -1)"
    if [[ -z "$f" ]]; then
        f="$(find "$dir" -type f -name '*.apk' 2>/dev/null \
             | grep -vE 'signed|aligned' | sort | head -1 || true)"
    fi
    printf '%s' "$f"
}

for m in "${MODULES[@]:-}"; do
    [[ -n "$m" ]] || continue
    f="$(find_module_apk "$m")"
    if [[ -z "$f" ]]; then
        warn "跳过 $m：未找到 APK 产物"
        continue
    fi
    APKS+=("$f")
done

(( ${#APKS[@]} > 0 )) || die "没有任何 APK 需要处理"

# ---------- 对齐 + 签名（幂等） ----------
info "步骤 2/2 · 对齐并签名…"
OUTS=()
for src in "${APKS[@]}"; do
    [[ -f "$src" ]] || { warn "文件不存在：$src"; continue; }
    name="$(basename "$src" .apk)"
    name="${name%-release}"          # myplugin-release → myplugin

    cur_fp="$(fingerprint_of "$src")"
    if [[ -n "$cur_fp" && "$cur_fp" == "$EXPECTED_FP" ]]; then
        info "  = $name 已与目标密钥同签名，跳过"
        OUTS+=("$src")
        continue
    fi

    out_dir="$(dirname "$src")/signed"
    mkdir -p "$out_dir"
    aligned="$out_dir/${name}-aligned.apk"
    signed="$out_dir/${name}-signed.apk"

    "$BT_ZIPALIGN" -f -p 4 "$(to_win_path "$src")" "$(to_win_path "$aligned")"
    # 口令通过 env: 前缀由 apksigner 自己从环境变量读，不出现在进程参数（ps 可见）中
    #
    # ★ 所有交给 apksigner / zipalign 的路径都必须过 to_win_path：
    #   这两个是 Windows 原生 java 程序，**相对路径能对、MSYS 绝对路径（/c/...）会炸**
    #   （`java.io.FileNotFoundException: \c\Users\...`）。传相对路径时看不出问题，
    #   一旦调用方给的是 /c/... 就必错 —— 所以这里统一转换，不依赖调用方怎么写。
    "$BT_APKSIGNER" sign \
        --ks "$(to_win_path "$ZORV_KEYSTORE")" \
        --ks-key-alias "$ZORV_KEY_ALIAS" \
        --ks-pass env:ZORV_STORE_PASS \
        --key-pass env:ZORV_KEY_PASS \
        --min-sdk-version 26 \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out "$(to_win_path "$signed")" \
        "$(to_win_path "$aligned")"

    rm -f "$aligned"
    OUTS+=("$signed")
    info "  ✓ $name → ${signed#"$REPO_ROOT"/}"
done

# ---------- 一致性校验 ----------
info "校验指纹一致性…"
for apk in "${OUTS[@]}"; do
    fp="$(fingerprint_of "$apk")"
    if [[ -z "$fp" ]]; then
        die "签名后仍无法提取指纹（签名失败？）：$apk"
    fi
    if [[ "$fp" != "$EXPECTED_FP" ]]; then
        die "指纹与密钥库不一致！
  期望：$EXPECTED_FP
  实际：$fp  ($apk)"
    fi
done

info "全部通过：${#OUTS[@]} 个 APK 均为同一证书签名，宿主与插件可互相认可"

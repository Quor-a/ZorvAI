#!/usr/bin/env bash
# ============================================================================
# 初始化「外部密钥位」（fork / CI 场景）
#
# 产出：
#   ~/.zorvai-signing/zorvai-release.jks   私钥（chmod 600，项目外）
#   ~/.zorvai-signing/env.sh               凭证（chmod 600，项目外）
#
# ⚠️ 本仓库（ZorvAI 主仓）已有官方密钥 keystore.properties → app/zorvai_release.jks，
#    用它签出的包与已发布版本同签名。**在本仓库运行本脚本会产生一把新钥匙**，
#    签出的 APK 指纹与线上版本不同 → 老用户无法覆盖安装、旧插件全部失效。
#    因此无 --force 时直接拒绝执行。
#
# 用法：
#   scripts/setup-signing-env.sh            # 本仓库会被拒绝并提示
#   scripts/setup-signing-env.sh --force    # 确认要另起一把新钥匙（fork / CI）
# ============================================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib/common.sh

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

if [[ -f "$PROJ_PROPS" ]]; then
    if (( ! FORCE )); then
        die "本仓库已有官方密钥配置：$PROJ_PROPS
  签名脚本会自动使用它，无需初始化新钥匙。
  若确实要为 fork / CI 另起一把新钥匙，请显式加 --force：
      scripts/setup-signing-env.sh --force
  但请注意：新钥匙签出的 APK 与线上发布版本不同签名，老用户无法覆盖安装。"
    fi
    warn "已确认 --force：即将在项目之外生成一把新钥匙"
    warn "它的指纹与官方版本不同，仅适用于 fork / CI / 全新分发链"
fi

KEYSTORE="$DEFAULT_KEYSTORE"
ALIAS="${ZORV_KEY_ALIAS:-zorvai}"

if [[ -f "$KEYSTORE" ]]; then
    info "密钥库已存在：$KEYSTORE"
    info "如需重建，请先手动删除该文件（这会让已装插件全部失效）"
    exit 0
fi

info "密钥家目录：$KEY_HOME"
mkdir -p "$KEY_HOME"
chmod 700 "$KEY_HOME"

# 口令：优先环境变量；否则生成随机强口令并写入 env.sh
if [[ -z "${ZORV_STORE_PASS:-}" ]]; then
    STORE_PASS="$(openssl rand -base64 32 | tr -d '/+=' | head -c 24)"
    GENERATED=1
else
    STORE_PASS="$ZORV_STORE_PASS"
    GENERATED=0
fi
KEY_PASS="$STORE_PASS"

info "生成 PKCS12 密钥库（RSA 2048 / 有效期 10950 天）…"
keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=ZorvAI Plugin, OU=Dev, O=ZorvAI, L=Unknown, ST=Unknown, C=CN" \
    >/dev/null

chmod 600 "$KEYSTORE"

cat > "$ENV_FILE" <<EOF
# ZorvAI 签名凭证 —— 由 setup-keystore.sh 生成，chmod 600，禁止提交、禁止外传
# 此文件位于项目目录之外，AI 工具默认不可达
export ZORV_KEY_HOME="$KEY_HOME"
export ZORV_KEYSTORE="$KEYSTORE"
export ZORV_KEY_ALIAS="$ALIAS"
export ZORV_STORE_PASS='$STORE_PASS'
export ZORV_KEY_PASS='$KEY_PASS'
EOF
chmod 600 "$ENV_FILE"
unset STORE_PASS KEY_PASS

info "密钥库：$KEYSTORE"
info "凭证文件：$ENV_FILE"
info "证书指纹（可公开，用于校验插件是否签对）："
STOREPASS_TMP="$(sed -n -E "s/^export ZORV_STORE_PASS='(.*)'\$/\1/p" "$ENV_FILE" | head -1)"
keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" \
    -storepass "$STOREPASS_TMP" 2>/dev/null \
    | grep -i 'SHA256' | head -1 | sed 's/^/    /' || true
unset STOREPASS_TMP

if (( GENERATED )); then
    warn "密钥口令已随机生成并写入 $ENV_FILE（chmod 600）"
    warn "请立即备份该目录到密码管理器，丢失后无法重新签发同签名插件"
else
    info "使用外部提供的口令"
fi

cat <<EOF

下一步：
  1) 安装钩子：  cp git-hooks/pre-commit git-hooks/pre-push .git/hooks/
  2) 校验指纹：  scripts/verify.sh --keystore <某个.apk>
  3) 补签产物：  scripts/sign.sh <模块名 或 某个-unsigned.apk>
EOF

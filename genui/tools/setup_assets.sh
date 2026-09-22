#!/usr/bin/env bash
# ============================================================================
# ZorvAI GenUI 资产打包脚本（#634）
#
# 把 npm 上的运行时依赖（react / react-dom / recharts / lucide-react / sucrase）
# 经 esbuild 打包成浏览器可用的 ESM，落到 assets/genui/lib/，供 importmap 白名单引用。
#
# 注意：
#   - zorv-ui.js 是仓库自带的预置组件库，无需打包，直接随 assets 发布。
#   - 这些 lib 文件体积较大（react-dom ~130KB、recharts ~400KB），不进 git，
#     由本脚本在 CI / 本地构建前生成。已在 .gitignore 规则中排除 *.js（lib 目录）。
#
# 依赖：node >= 18、npx(esbuild)。首次运行会自动 `npm i`。
# 用法：bash tools/setup_assets.sh
# ============================================================================
set -uo pipefail
# 注：不使用 set -e，避免单个包打包失败中断其余产物

# ---- 版本（与 build.gradle.kts / shell importmap 对齐）-----------------------
REACT_V="18.3.1"
RECHARTS_V="2.12.7"
LUCIDE_V="0.379.0"
SUCRASE_V="3.35.0"

# ---- 路径 ------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 本脚本位于 <module>/tools/setup_assets.sh，模块根即 SCRIPT_DIR/..
MOD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LIB_DIR="$MOD_DIR/src/main/assets/genui/lib"
# esbuild 是 Windows 原生二进制，不认 Git Bash 的 /d/ 挂载路径，
# 必须传 Windows 原生路径（cygpath -w），否则产物会落到临时目录的相对路径下被清理。
LIB_DIR_WIN="$(cygpath -w "$LIB_DIR" 2>/dev/null || echo "$LIB_DIR")"
TMP="$(mktemp -d)"

mkdir -p "$LIB_DIR"

echo "==> 准备临时 npm 工程：$TMP"
cd "$TMP"
npm init -y >/dev/null 2>&1
npm pkg set type="module" >/dev/null 2>&1

echo "==> 安装依赖（react / react-dom / recharts / lucide-react / sucrase）"
npm i --no-audit --no-fund \
  "react@$REACT_V" "react-dom@$REACT_V" \
  "recharts@$RECHARTS_V" "lucide-react@$LUCIDE_V" "sucrase@$SUCRASE_V" esbuild@0.21.5 >/dev/null

# ---- 用 esbuild 把每个入口打包成 ESM ----------------------------------------
bundle() { # $1=入口  $2=输出文件名
  npx esbuild "$1" --bundle --format=esm --minify --legal-comments=none \
    --outfile="$LIB_DIR_WIN\\$2"
  echo "    -> $2"
}

echo "==> 打包 react / react-dom"
bundle "node_modules/react/index.js"                 "react.js"
bundle "node_modules/react/jsx-runtime.js"          "react-jsx-runtime.js"
bundle "node_modules/react-dom/client.js"           "react-dom-client.js"
bundle "node_modules/react-dom/index.js"            "react-dom.js"
bundle "node_modules/recharts/es6/index.js"         "recharts.js"
bundle "node_modules/lucide-react/dist/esm/lucide-react.js" "lucide.js"

# ---- sucrase：优先用 esbuild 打包，失败则回退 CDN UMD -----------------------
echo "==> 打包 sucrase"
if npx esbuild "node_modules/sucrase/dist/index.js" --bundle --format=iife \
     --global-name=sucrase --minify --outfile="$LIB_DIR_WIN\\sucrase.js" 2>/dev/null; then
  echo "    -> sucrase.js (esbuild iife)"
else
  echo "    esbuild 失败，回退下载官方 UMD 构建"
  curl -fsSL "https://cdn.jsdelivr.net/npm/sucrase@$SUCRASE_V/dist/sucrase.js" \
    -o "$LIB_DIR/sucrase.js"
  echo "    -> sucrase.js (cdn)"
fi

# ---- 校验 ------------------------------------------------------------------
echo "==> 校验产物"
for f in react.js react-jsx-runtime.js react-dom.js react-dom-client.js recharts.js lucide.js sucrase.js zorv-ui.js; do
  if [ -s "$LIB_DIR/$f" ]; then
    printf "  [ok] %-22s %6s KB\n" "$f" "$(du -k "$LIB_DIR/$f" | cut -f1)"
  else
    printf "  [MISSING] %s\n" "$f"
  fi
done

echo "完成。lib 目录：$LIB_DIR"
# 仅在全部成功产出后清理临时工程
rm -rf "$TMP"

# -*- coding: utf-8 -*-
"""把 zx 编译器（zxwidget 动态交互界面）从聊天运行时里抽成独立模块。

背景：用户 2026-10-13 明确「web UI 是 web UI，zxwidget 是 zxwidget」。
旧实现把 477 组件的编译器整个烙进聊天页的单 HTML 里，导致两件事分不开。
抽出 `assets/zx/zxwidget.js` 后：
  - 它是一个**独立模块**（组件契约 + 布局编译器），谁都能用；
  - 契约来源从「页面里内联的 <script>」改为「install() 注入 / fetch 加载」，
    于是不再需要把 195 KB JSON 内联进 HTML（那正是白屏与体积的来源）。

不改任何布局逻辑，纯搬运 + 改契约注入方式。原子写。

⚠️ 一次性脚本，**已执行、不可重跑**：源文件 `app/src/main/assets/zx_chat_runtime.html`
   已于 2026-10-13 删除（内容整体搬进 `assets/zx/zxwidget.js`）。
   现在要改 zxwidget 请直接改 `assets/zx/zxwidget.js`，别再跑本脚本。
   另注意它只搬了聊天运行时那一份编译器；单卡片运行时
   `zx_card_runtime.html` 里还有一份**未收敛**的内联拷贝，
   见 `scripts/zx/zxwidget_parity_check.js`。
"""
import io
import os
import re

ROOT = r"D:/Calw OS-project/QuroAI"
SRC = os.path.join(ROOT, "app/src/main/assets/zx_chat_runtime.html")
OUT_DIR = os.path.join(ROOT, "app/src/main/assets/zx")
OUT = os.path.join(OUT_DIR, "zxwidget.js")

BANNER_START = "/* ═══════════════════════════════════════════════════════════════\n   1. zxwidget"
BANNER_END = "/* ═══════════════════════════════════════════════════════════════\n   2. Markdown"

NEW_CONTRACTS_BLOCK = """/* ── 组件契约来源：由 install() 注入，不再内联在页面里 ── */
var ZX_CONTRACTS = {};
function zxInstallContracts(raw){
  var ct = raw;
  /* 宿主可能整包下发 {components, contracts}，这里统一解包到内层映射 */
  if(raw && typeof raw === "object" && raw.contracts && typeof raw.contracts === "object") ct = raw.contracts;
  ZX_CONTRACTS = (ct && typeof ct === "object") ? ct : {};
  return ZX_CONTRACTS;
}
"""


def main():
    src = io.open(SRC, encoding="utf-8").read()

    i = src.find(BANNER_START)
    j = src.find(BANNER_END)
    if i < 0 or j < 0 or j <= i:
        raise SystemExit("!! 找不到分节横幅，放弃（i=%s j=%s）" % (i, j))

    body = src[i:j]

    # 把页面专用的契约加载 IIFE 换成模块化的 install()
    ii = body.find("var ZX_CONTRACTS = (function(){")
    if ii < 0:
        raise SystemExit("!! 找不到 ZX_CONTRACTS IIFE")
    ie = body.find("})();", ii)
    if ie < 0:
        raise SystemExit("!! IIFE 结束标记缺失")
    body = body[:ii] + NEW_CONTRACTS_BLOCK + body[ie + len("})();"):]

    header = """/* ═══════════════════════════════════════════════════════════════════════
   zxwidget —— 动态交互界面组件库
   ═══════════════════════════════════════════════════════════════════════

   定位：zxwidget 是「动态交互界面」，本身既不是 ZorvAI 对话框也不是 Web UI。
   它是一套**可被任意界面复用的组件编译器**——ZorvAI 原生对话框、Web UI 对话框
   都只是「支持它」。所以它必须待在自己的文件里，不掺进任何一个对话框的实现。

   用法：
     zxInstallContracts(json)                  // 注入 477 组件的字段契约
     var node = compile(component, dsl, ctx)   // 编译成一个可插入的 DOM 节点
     ctx = { interact: function(action, payload){ ... } }   // 用户操作回传

   契约词汇（高规则，所以能通用推断）：
     n=名/标签 v=数值 d/desc=描述 t=文本 k=键 s/e=起止 at/when=时间
     st/status=状态 who=人 u/unit=单位 hex=颜色 e/icon=图标
     数组容器 items/rows/nodes/pts/steps/cols/fields/...
   ═══════════════════════════════════════════════════════════════════════ */

"""

    os.makedirs(OUT_DIR, exist_ok=True)
    tmp = OUT + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(header + body)
    os.replace(tmp, OUT)

    print("已写出:", os.path.relpath(OUT, ROOT).replace("\\", "/"))
    print("行数:", (header + body).count("\n") + 1)
    print("字节:", os.path.getsize(OUT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

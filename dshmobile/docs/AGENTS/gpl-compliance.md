# gpl-compliance.md — GPL 合规

## 7. GPL 合规（2026-08-23 定稿）

- 快照包：`usr/share/doc/<pkg>/copyright`（多数为软链 → `usr/share/LICENSES/<fam>.txt`）或 COPYING* 实体文件；`licenses` 包在 TARGETS 显式锁定（x86_64 曾漏带）。
- 仓库：`LICENSES/`（GPL-2.0/3.0、LGPL-2.1/3.0 全文）+ `THIRD_PARTY_NOTICES.md`（80 组件矩阵，含源码要约与再加工工具清单）+ `scripts/third-party-licenses.json` + `scripts/check-third-party.mjs`（矩阵覆盖 + copyleft 全文在场，三形态判定）；门禁接入 build-apk-013.ps1，缺失即拒打包。
- APK：`assets/licenses/`（LICENSES + notices，随包分发）。
- 声明文：`docs/RELEASE.md §7`（D 章合规声明 + 源码要约 + 修改工具）。

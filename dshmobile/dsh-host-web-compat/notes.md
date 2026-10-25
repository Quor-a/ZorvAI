# 手动主题接管修复

- 基于最新 `origin/main` 0392ef0，仅修改主题桥的画布写入所有权与回归测试。
- 空 `#root` 阶段保留系统底色与 `color-scheme`，消除首次加载白闪。
- UI 挂载后停止覆盖画布；OS 变化仍通知 MQL，由页面主题服务解析 system，手动浅色/深色保持页面选择。
- 代码层：44 项 watchdog 测试通过（含移除守卫时双模式必红负控）、其他 13 项测试通过，smoke 全通过。
- 设备：独立模拟器 16384，真实新壳 APK + guard 热推组合通过 12 项 CDP 语义检查、竖横真实 ADB 点选与截图、设置往返和重启持久化。热推版本基于 APK 旧镜像，不包含独立 clipboard 前置；最终镜像由独立前置 PR 合并后完整同步。证据见 `/mnt/d/coding/table-gesture-evidence/THEME-ACCEPTANCE.md`，不代表最终发布 APK 已验收。
- 未创建版本、tag 或 Release。

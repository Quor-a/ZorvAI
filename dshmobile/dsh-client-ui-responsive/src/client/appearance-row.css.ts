/**
 * 设置 → 通用设置 → 外观：把上游三个主题大卡片压成一行。
 *
 * 上游 `AppearanceRow`（ui-theme）的卡片是 `flex:180px` + `padding:20px 32px`，
 * 360 CSS px 竖屏下三张卡片必然一张一行，整块约 330px 高——设置页里最占纵向空间的一项。
 *
 * 为什么单独一张表、不塞进 mobile-settings.css.ts：那张表刻意只用属性选择器、不用 `:has()`
 * （老内核友好）。这里需要「父容器里恰好三个 `aria-pressed` 按钮」这个判据，拆成两条路径：
 *   - 按钮压缩用 `:first-child:nth-last-child(3)` 及其兄弟选择器，不依赖 `:has()`，老内核同样生效；
 *   - 「标题 + 选项同一行」必须选中父容器，只能 `:has()`；不支持时整条规则被浏览器丢弃，
 *     退化为「标题在上、三个紧凑选项一行」，仍然远小于原样。
 *
 * 上游类名是 CSS Module 哈希（`._8HJdBW_themeCube` 之类），这里只认结构：设置面板的稳定钩子
 * `data-shortcut-modal="settings"` + 按钮的 `aria-pressed`。设置页里还有 1–2 个 `aria-pressed`
 * 按钮的其它组（模型、市场等），`nth-last-child(3)` 的约束把它们排除在外——只有恰好三个按钮的
 * 外观组会被压缩。
 */
export const APPEARANCE_ROW_CSS: string = `
/* 三个主题卡片：等宽并排、紧凑内边距（不依赖 :has()，老内核同样生效）。 */
html[data-dsh-mobile-form] [data-shortcut-modal='settings'] div > button[aria-pressed]:first-child:nth-last-child(3),
html[data-dsh-mobile-form] [data-shortcut-modal='settings'] div > button[aria-pressed]:first-child:nth-last-child(3) ~ button[aria-pressed] {
  box-sizing: border-box;
  flex: 1 1 0;
  min-width: 0;
  flex-direction: row;
  justify-content: center;
  align-items: center;
  gap: 6px;
  padding: 8px 6px;
  border-radius: var(--dsw-radius-md);
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

html[data-dsh-mobile-form] [data-shortcut-modal='settings'] div > button[aria-pressed]:first-child:nth-last-child(3) > svg,
html[data-dsh-mobile-form] [data-shortcut-modal='settings'] div > button[aria-pressed]:first-child:nth-last-child(3) ~ button[aria-pressed] > svg {
  flex: none;
  width: 16px;
  height: 16px;
}

/* 整组一行：标题在左、选项在右。:has() 不支持时本条被丢弃，退化为标题在上。 */
html[data-dsh-mobile-form] [data-shortcut-modal='settings'] div:has(> div > button[aria-pressed]:first-child:nth-last-child(3)) {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

html[data-dsh-mobile-form] [data-shortcut-modal='settings'] div:has(> div > button[aria-pressed]:first-child:nth-last-child(3)) > div:has(> button[aria-pressed]) {
  flex: 1 1 auto;
  flex-wrap: nowrap;
  justify-content: flex-end;
}
`

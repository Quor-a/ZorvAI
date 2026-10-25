/**
 * 长按拖动重排的可见反馈（0.14.7 / Spec §2.2「激活时给出可见反馈」）。
 *
 * 只用属性做门：拖动中 <html> 带 `data-dsh-sidebar-reorder`（sidebar-reorder.ts 打/撤），
 * 源行带 `data-dsh-reorder-source`。两者都撤掉后样式回到完全无痕，无需任何 JS 还原步骤。
 *
 * 颜色与圆角全部走既有 `--dsw-*` 设计变量，避免硬编码颜色阻断主题继承。
 * 不改变行高与间距：位移/缩放都不做，只做底色与轮廓，避免列表在拖动中抖动。
 */
export const SIDEBAR_REORDER_CSS: string = `
/* 拖动中禁止列表滚动与文本选择：手指在列表上移动时若触发滚动，插入位置会漂移。 */
html[data-dsh-sidebar-reorder] [data-row-key^='session:'] {
  -webkit-user-select: none;
  user-select: none;
}

/* 源行：抬高 + 描边。只动视觉层，盒模型不变（不引发重排）。 */
html[data-dsh-sidebar-reorder] [data-dsh-reorder-source] {
  background: var(--dsw-alias-interactive-bg-hover, rgba(128, 128, 128, 0.16));
  border-radius: var(--dsw-radius-md, 10px);
  box-shadow: inset 0 0 0 1px var(--dsw-alias-border-l1, rgba(128, 128, 128, 0.4));
  opacity: 0.72;
}

/* 拖动期间抑制 Android WebView 的长按蓝蒙层：长按已被本功能占用。 */
html[data-dsh-sidebar-reorder] {
  -webkit-tap-highlight-color: transparent;
}
`

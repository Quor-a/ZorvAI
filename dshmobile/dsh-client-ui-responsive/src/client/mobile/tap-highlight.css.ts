/**
 * Android WebView 点按蓝色蒙层（0.14.7 / Spec 2.3）。
 *
 * 真机实测（MuMu PGBM10, WebView Chrome/110，600px 视口）：插件与上游源码里
 * **没有**任何 tap-highlight 声明，但页面上 770/770 个元素的
 * getComputedStyle().webkitTapHighlightColor 全部解析为 rgba(51, 181, 229, 0.4)
 * —— 即 #33b5e5（Android 5.0 之前的 ICS 蓝）。这是 WebView 的 UA 默认值，
 * 不是某个控件的样式，所以点任何可点元素都会闪一层蓝。
 *
 * 该属性是**可继承**的（设备上实测：只在 :root 声明一次，body 与深层 button 的
 * 解析值同时变成 rgba(0,0,0,0)），因此这里声明在 :root 即可覆盖全站，
 * 无需逐元素打补丁。
 *
 * 只抹掉这一条 UA 蓝色高亮，**不动** opacity / outline / transition：
 * 上游自身的 :active 底色与 :focus-visible 焦点环（ui-theme focus.css）必须保留，
 * 那是"正常点击反馈与可访问焦点环"。
 *
 * 本插件只跑在 Android 壳内，无需再加别处不需要的形态门。
 */
export const TAP_HIGHLIGHT_CSS: string = `
:root {
  -webkit-tap-highlight-color: transparent;
}
`

/**
 * 上游四列以上 Markdown 表格有稳定的 md-table-wide hook。
 * 桌面依赖 hover 才把 overflow-x:hidden 改为 scroll；触屏首触点没有 hover，
 * 侧栏仲裁会把它误认成普通内容。必须在触点落下前保持原生横向滚动和 touch-action。
 * 只覆盖触屏/窄屏，保留桌面鼠标行为、普通小表格与语义化 table 结构。
 */
export const MOBILE_TABLE_CSS: string = `
@media (max-width: 767px), (pointer: coarse) {
  [data-dsh-frame] .md-table-wide {
    max-width: 100%;
    /* 覆盖上游 resting / hover / focus 三态，避免首次触摸才切换滚动容器。 */
    overflow-x: auto !important;
    overscroll-behavior-x: contain;
    padding-bottom: 6px !important;
    scrollbar-width: thin;
  }

  [data-dsh-frame] .md-table-wide,
  [data-dsh-frame] .md-table-wide * {
    touch-action: auto;
  }

  [data-dsh-frame] .md-table-wide table {
    width: max-content;
    max-width: none;
  }

  [data-dsh-frame] .md-table-wide :is(th, td) {
    /* 不缩字号。窄屏的 30vw 上限会把说明挤成逐字竖排，改用可阅读的列宽。 */
    min-width: 100px;
    width: auto;
    max-width: min(55vw, 240px);
    padding: 10px 12px;
    white-space: normal;
    overflow-wrap: anywhere;
    vertical-align: top;
  }
}
`

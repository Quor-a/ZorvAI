/**
 * ZorvAI 品牌槽（#407 去 kelai141/dsh-mobile-apk 品牌化）。
 *
 * 上游在新会话空态（hero）里渲染品牌位：`conversation.hero.brand.mark` 是
 * `dsh-client-ui-conversation` 声明并持有的 single 槽（scope root，owner props
 * `{ size, className }`），回落件是 DeepSeek 鱼标 HeroFish。本包把该槽占位为
 * ZorvAI 品牌：白 Z 标记（深空渐变圆角底 + 青点）——与壳侧启动页
 * drawable/dshm_brand_logo.xml、宿主 ic_launcher_foreground 同源同构。
 *
 * 另一处品牌露出是 document.title：上游 ui-layout 的 AppFrame 把构建期常量
 * `productTitle = "DeepSeek Harness"` 写进 document.title（会话态为
 * `<会话名> — DeepSeek Harness`），且它在每次会话/面板切换时重写。没有槽能改它，
 * 故由 BrandTitle 在 DOM 层观测 <title> 文本变更并改写（同 ThemeBridge 的
 * DOM 观测范式；改写后不再含源串，不会自激）。
 *
 * 历史纠错：本文件此前按「sidebar.brand.mark / sidebar.brand.name」两个槽注册，
 * 那是 #407 初期对上游槽位的误判——本依赖树里既没有这两个槽（上游 sidebar 包
 * 不在依赖集中），类型联合也不认，注册进去运行期是静默空转、类型期直接绊停构建。
 * 真实槽名以 `dsh-client-ui-conversation` 的 slots.d.ts 为准（`conversation.hero.*`）。
 *
 * 注入方：src/client/index.ts（ctx.slots.inject + ctx.slots.register / ctx.effect）。
 */
import type { PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'

/** 品牌标记 props：槽主运行时份额（上游渲染点传 { size: 34, className }）+ 尺寸兜底。 */
export type ZorvaiBrandMarkProps = PropsRuntime<'conversation.hero.brand.mark'> & { size?: number }

/** ZorvAI 品牌标记：深空渐变圆角底 + 白 Z 字标 + 青点（端侧节点）。 */
export function ZorvaiBrandMark({ size = 24, className }: ZorvaiBrandMarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 108 108"
      className={className}
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id="zorvai-brand-grad" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#0B2A3A" />
          <stop offset="1" stopColor="#06121F" />
        </linearGradient>
      </defs>
      <path d="M24,0 H84 A24,24 0 0,1 108,24 V84 A24,24 0 0,1 84,108 H24 A24,24 0 0,1 0,84 V24 A24,24 0 0,1 24,0 Z" fill="url(#zorvai-brand-grad)" />
      <path d="M34,40 H74 L34,70 H74" fill="none" stroke="#FFFFFF" strokeWidth="11" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M80,38 m-6,0 a6,6 0 1,1 12,0 a6,6 0 1,1 -12,0" fill="#22D3EE" />
    </svg>
  )
}

/** document.title 里的上游产品名（ui-layout AppFrame 的构建期常量）。 */
const UPSTREAM_PRODUCT_TITLE = 'DeepSeek Harness'
/** 改写目标：壳侧 app_name / UserCopy.APP_NAME 同为「ZorvAI」。 */
const BRAND_TITLE = 'ZorvAI'

/**
 * 把 document.title 里的上游产品名改写为 ZorvAI（#407 去品牌化的 DOM 层一面）。
 *
 * 为什么是观测而不是一次性改写：上游 DocumentTitle 组件在会话切换、面板选择、
 * 宽度变化时都会重写 document.title，一次性改写会被随后覆盖。这里观测 <title>
 * 的文本/子节点变更，发现源串就地替换——改写后的文本不含源串，观察回调不会
 * 再次触发写入，故无自激循环。
 */
export class BrandTitle {
  private observer: MutationObserver | null = null
  private attached = false

  /** 开始观测并立即改写一次当前标题；重复调用无副作用。 */
  attach(): void {
    if (this.attached) return
    this.attached = true
    this.rewrite()
    try {
      this.observer = new MutationObserver(() => { this.rewrite() })
      const title = document.querySelector('title')
      if (title !== null) {
        this.observer.observe(title, { childList: true, characterData: true, subtree: true })
      } else {
        // <title> 可能还没被创建（首帧之前）：先观测 head 的子节点变动接它的出现。
        this.observer.observe(document.head, { childList: true })
      }
    } catch (error) {
      // 这只是标题文案层，绝不能把插件主体拖下水。
      console.warn('[zorvai-brand] title observer failed; the page title keeps the upstream name', error)
    }
  }

  /** 撤掉观测（标题文本本身不回改——它随时可能被上游再写，回改没有意义）。 */
  detach(): void {
    if (!this.attached) return
    this.attached = false
    this.observer?.disconnect()
    this.observer = null
  }

  /** 就地替换标题里的上游产品名；不含源串时什么都不做。 */
  private rewrite(): void {
    const current = document.title
    if (!current.includes(UPSTREAM_PRODUCT_TITLE)) return
    document.title = current.split(UPSTREAM_PRODUCT_TITLE).join(BRAND_TITLE)
  }
}

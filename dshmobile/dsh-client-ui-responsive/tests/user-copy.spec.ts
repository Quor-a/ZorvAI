// 0.14.1 批 3（P3-1 / P3-3 / P3-4）页面侧文案唯一真源的行为与反证用例。
//
// 这一组断言的目标不是「函数能跑」，而是**把两条规则钉死**：
//   1. 机器码不上屏——表内每个码，以及表外的未知码，翻译结果都不得包含该码本身；
//   2. 截断必须自证——超长文本一律带省略号（用户不会把截断片段当成完整内容）。
// 把任何一条改坏（比如让未知码「原样带出」、让截断去掉省略号）都会在这里判红。
import { describe, it, expect } from 'vitest'
import {
  UNKNOWN_CALL_REASON,
  describeCallReason,
  describeHttpFailure,
  describeImportance,
  describeNotifyWriteFailure,
  formatDuration,
  noticeDataAttrs,
  truncateWithEllipsis,
} from '../src/client/user-copy.ts'

/** 真源表覆盖的码集合（新增码必须同时补进这个清单——清单与表脱节即漏测）。 */
const CALL_REASON_CODES = [
  // open-path.ts 的页面侧自查码
  'unavailable', 'refused', 'empty-answer', 'bridge-error',
  // 壳侧：链路未装配
  'bridge not wired', 'no-shell-context', 'browser-host-not-wired',
  'carrier-not-started', 'a11y-unavailable', 'unknown-op',
  // 壳侧：外链与拉起
  'unknown-key', 'insecure-url', 'no-handler', 'not-installed',
] as const

describe('describeCallReason（P3-1：码不上屏）', () => {
  it('表内每个码都有「发生了什么 + 下一步」的人话，且正文不含该码', () => {
    for (const code of CALL_REASON_CODES) {
      const text = describeCallReason(code)
      expect(text.length, code).toBeGreaterThan(6)
      expect(text, '正文不得回显机器码：' + code).not.toContain(code)
    }
  })

  it('表外的码给兜底人话——原样带出机器码是缺陷形态，不是「不吞」', () => {
    const text = describeCallReason('something-brand-new')
    expect(text).not.toContain('something-brand-new')
    expect(text).toBe(UNKNOWN_CALL_REASON)
    // 空/缺省同样不静默
    expect(describeCallReason(undefined)).toBe(UNKNOWN_CALL_REASON)
    expect(describeCallReason('')).toBe(UNKNOWN_CALL_REASON)
  })

  it('带前缀的复合码按前缀归类（load-error:<webview 内部码>）', () => {
    const text = describeCallReason('load-error:-2')
    expect(text).toContain('内置浏览器')
    expect(text).not.toContain('load-error')
  })
})

describe('describeHttpFailure（P3-1：状态码只作分档依据）', () => {
  it('各档位都给人话，且正文不含状态码数字', () => {
    for (const status of [400, 401, 403, 404, 405, 418, 500, 503]) {
      const text = describeHttpFailure('读取来件占用', status)
      expect(text.length, String(status)).toBeGreaterThan(8)
      expect(text, '正文不得出现状态码：' + status).not.toContain(String(status))
      expect(text).toContain('读取来件占用')
    }
  })

  it('未授权/不存在/服务端出错三类语义不同——用户下一步不同，就不能说同一句话', () => {
    const unauthorized = describeHttpFailure('清理运行时缓存', 403)
    const missing = describeHttpFailure('清理运行时缓存', 404)
    const server = describeHttpFailure('清理运行时缓存', 500)
    expect(new Set([unauthorized, missing, server]).size).toBe(3)
    expect(unauthorized).toContain('未获授权')
    // 页面侧的 500 来自本机应用自身（引擎/宿主），不是远端服务——措辞与壳侧的 httpFailure 刻意不同。
    expect(server).toContain('应用内部')
  })
})

describe('describeNotifyWriteFailure（P3-1）', () => {
  it('壳侧读回失败的原因给人话，且不回显码与内部 key', () => {
    const text = describeNotifyWriteFailure('readback-mismatch')
    expect(text).toContain('没有接受')
    expect(text).not.toContain('readback-mismatch')
    expect(describeNotifyWriteFailure('unknown-key')).not.toContain('unknown-key')
    expect(describeNotifyWriteFailure(undefined)).not.toContain('unknown')
  })
})

describe('describeImportance（P3-1：档位不印数字）', () => {
  it('各档位给人话；非数字返回空串（整段省略而不是打印 ?）', () => {
    for (const value of [0, 1, 2, 3, 4, 5]) {
      const text = describeImportance(value)
      expect(text.length, String(value)).toBeGreaterThan(2)
      expect(text, '不得是裸数字：' + value).not.toBe(String(value))
    }
    expect(describeImportance(undefined)).toBe('')
    expect(describeImportance('4')).toBe('')
    expect(describeImportance(Number.NaN)).toBe('')
  })
})

describe('truncateWithEllipsis（P3-4：截断必须自证）', () => {
  it('未超长原样返回（不无端加省略号）', () => {
    expect(truncateWithEllipsis('rm -rf /tmp', 24)).toBe('rm -rf /tmp')
    expect(truncateWithEllipsis('0123456789', 10)).toBe('0123456789')
  })

  it('超长必附省略号，且总长不超过 max', () => {
    const out = truncateWithEllipsis('rm -rf /data/local/tmp/very-long-path', 24)
    expect(out.endsWith('…')).toBe(true)
    expect(out.length).toBe(24)
    expect(out).not.toBe('rm -rf /data/local/tmp/very-long-path')
  })

  it('被截断的命令不得与完整命令逐字相同（反证：去掉省略号即判红）', () => {
    const command = 'rm -rf /data/local/tmp'
    const out = truncateWithEllipsis(command, 14)
    expect(out).toBe('rm -rf /data/…')
    expect(out).not.toContain('local')
  })

  it('max<=0 返回空串（不给调用方一个「看起来有内容」的假值）', () => {
    expect(truncateWithEllipsis('abc', 0)).toBe('')
    expect(truncateWithEllipsis('abc', -3)).toBe('')
  })
})

describe('formatDuration（P3-3：统一「分秒」口径）', () => {
  it('不足 1 分钟用「X秒」，不用 8.4s 这类英文单位', () => {
    expect(formatDuration(8_400)).toBe('8.4秒')
    expect(formatDuration(8_000)).toBe('8秒')
    expect(formatDuration(45_000)).toBe('45秒')
  })

  it('1 分钟到 1 小时用「X分YY秒」', () => {
    expect(formatDuration(60_000)).toBe('1分00秒')
    expect(formatDuration(84_000)).toBe('1分24秒')
    expect(formatDuration(3_599_000)).toBe('59分59秒')
  })

  it('超过 1 小时用「X小时YY分」', () => {
    expect(formatDuration(3_600_000)).toBe('1小时00分')
    expect(formatDuration(7_500_000)).toBe('2小时05分')
  })

  it('未知时长返回空串——不打印「-」（用户分不清「未知」与「零」）', () => {
    expect(formatDuration(0)).toBe('')
    expect(formatDuration(-1)).toBe('')
    expect(formatDuration(Number.NaN)).toBe('')
  })
})

describe('noticeDataAttrs（P3-1：码只进 data-*）', () => {
  it('码与状态码落进 data-*，正文不含它们', () => {
    const attrs = noticeDataAttrs({ text: '清理未完成——请重试', code: 'readback-mismatch', http: 403 })
    expect(attrs['data-code']).toBe('readback-mismatch')
    expect(attrs['data-http']).toBe('403')
  })

  it('缺省值不产生属性（避免渲染出空 data-code 让诊断误以为有码）', () => {
    expect(noticeDataAttrs({ text: '好' })).toEqual({})
    expect(noticeDataAttrs({ text: '好', code: '' })).toEqual({})
  })
})

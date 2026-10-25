// @vitest-environment jsdom
// 通知落点（P0-1）：点通知必须落到对应会话，且**判据不能无验证地回成功**。
//
// 背景（设备/源码双重取证）：壳侧 `NotifyCenter.contentIntent` 一直在写 `dsh.notify.*` extras，
// 而全仓没有读取者、`MainActivity` 也没有 `onNewIntent` ⇒ 整族通知是单向公告板——点进去只是把
// 应用拉到前台，停在原页面。修法分两半：壳侧读 extras 并把 id 送进页面（`__dshOpenSession`），
// 页面侧（本模块）执行切换并**如实回报成败**（壳侧据此决定是否给"无法打开该会话"的提示）。
import { describe, expect, it, vi } from 'vitest'
import { openSessionForNotify, type SessionOpenFace } from '../src/client/mobile/notify-landing.ts'

/** 假会话面：只跟踪已加载的会话集合（open 会把 id 装进来，模拟真实服务的行为）。 */
function fakeFace(loaded: string[] = []): { face: SessionOpenFace; opened: string[] } {
  const set = new Set(loaded)
  const opened: string[] = []
  return {
    opened,
    face: {
      scope: (id: unknown) => (typeof id === 'string' && set.has(id) ? { id } : undefined),
      open: (id: unknown) => {
        if (typeof id === 'string') { opened.push(id); set.add(id) }
      },
    },
  }
}

describe('通知落点 openSessionForNotify', () => {
  it('已加载的会话：直接打开并如实回 true', () => {
    const { face, opened } = fakeFace(['session-a'])
    expect(openSessionForNotify(face, 'session-a')).toBe(true)
    expect(opened).toEqual(['session-a'])
  })

  it('未加载但存在的会话：open 之后必须**再确认一次**才回 true', () => {
    // 这条是判据的关键：如果实现只写 `face.open(id); return true`，
    // 「刚被打开」与「根本不存在」就都会回 true，壳侧的失败提示就成了摆设。
    const { face, opened } = fakeFace([])
    expect(openSessionForNotify(face, 'session-new')).toBe(true)
    expect(opened).toEqual(['session-new'])
  })

  it('会话不存在（open 也装不起来）：必须回 false', () => {
    const face: SessionOpenFace = { scope: () => undefined, open: () => {} }
    expect(openSessionForNotify(face, 'session-gone')).toBe(false)
  })

  it('空 id / 非字符串 / 服务不在场：一律 false，且不抛', () => {
    const { face, opened } = fakeFace(['session-a'])
    expect(openSessionForNotify(face, '')).toBe(false)
    expect(openSessionForNotify(face, undefined)).toBe(false)
    expect(openSessionForNotify(face, 42)).toBe(false)
    expect(openSessionForNotify(undefined, 'session-a')).toBe(false)
    expect(opened).toEqual([])
  })

  it('服务抛错：回 false，不把异常漏给壳侧（漏出去会被桥层吞成 undefined）', () => {
    const face: SessionOpenFace = {
      scope: () => { throw new Error('scope exploded') },
      open: vi.fn(),
    }
    expect(openSessionForNotify(face, 'session-a')).toBe(false)
  })
})

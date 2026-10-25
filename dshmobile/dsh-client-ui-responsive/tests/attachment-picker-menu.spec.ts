// @vitest-environment jsdom
// AttachmentPickerMenuEnhancer after D2/D4 (2026-09-25): upstream 0.1.7-rc.1 has no paperclip, so
// this plugin mounts its own control into 'conversation.input.left' and claims NO upstream button.
// The decisive regression guard is the first describe block: the upstream command "+" must keep its
// own onClick, because the previous revision's "last BUTTON before the hidden input" pairing claimed
// it and swallowed onToggleCommandMenu (command menu never opened; "+" appeared to be the uploader).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AttachmentPickerMenuEnhancer } from '../src/client/mobile/attachment-picker-menu.ts'

let enhancer: AttachmentPickerMenuEnhancer
let card: HTMLElement
let tools: HTMLElement
let anchor: HTMLElement
let plus: HTMLButtonElement
let input: HTMLInputElement

/** Deliver pending MutationObserver/microtask work. */
function flush(): Promise<void> {
  return new Promise(resolve => { setTimeout(resolve, 0) })
}

/** The trigger this enhancer mounts. */
function trigger(): HTMLButtonElement | null {
  return document.querySelector<HTMLButtonElement>('[data-dsh-attachment-picker-trigger]')
}

function triggerOrThrow(): HTMLButtonElement {
  const found = trigger()
  if (found === null) throw new Error('attachment trigger was not mounted')
  return found
}

function menu(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-dsh-attachment-picker-menu]')
}

/**
 * The 0.1.7-rc.1 composer: the command "+" (aria-haspopup=listbox, upstream onClick), the hidden
 * multiple-file input as its own row sibling, then .modes and the 'conversation.input.left' seat.
 */
function mountComposer(language = 'zh-CN'): void {
  document.documentElement.lang = language
  card = document.createElement('div')
  card.setAttribute('data-composer-card', '')
  tools = document.createElement('div')
  plus = document.createElement('button')
  plus.type = 'button'
  plus.setAttribute('aria-label', '添加文件或调用指令')
  plus.setAttribute('aria-haspopup', 'listbox')
  plus.setAttribute('aria-expanded', 'false')
  plus.textContent = 'plus'
  input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.hidden = true
  anchor = document.createElement('div')
  anchor.setAttribute('data-slot', 'conversation.input.left')
  anchor.style.display = 'contents'
  tools.append(plus, input, anchor)
  card.append(tools)
  document.body.append(card)
}

beforeEach(() => {
  mountComposer()
  enhancer = new AttachmentPickerMenuEnhancer()
  enhancer.attach()
})

afterEach(() => {
  enhancer.detach()
  document.body.innerHTML = ''
  document.documentElement.lang = ''
  vi.restoreAllMocks()
})

describe('upstream command "+" is never claimed (D2/D4)', () => {
  it('leaves the aria-haspopup=listbox command button to upstream', () => {
    const upstream = vi.fn()
    plus.addEventListener('click', upstream)
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    plus.dispatchEvent(event)
    // Upstream's own onClick ran, nothing cancelled it, and our menu stayed shut.
    expect(upstream).toHaveBeenCalledTimes(1)
    expect(event.defaultPrevented).toBe(false)
    expect(menu()).toBeNull()
    expect(plus.hasAttribute('data-dsh-attachment-picker-trigger')).toBe(false)
  })

  it('does not claim a template-literal / label that looks like a paperclip either', () => {
    const fake = document.createElement('button')
    fake.type = 'button'
    fake.setAttribute('aria-label', '添加附件')
    fake.textContent = 'paperclip'
    anchor.after(fake)
    const upstream = vi.fn()
    fake.addEventListener('click', upstream)
    fake.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(upstream).toHaveBeenCalledTimes(1)
    expect(menu()).toBeNull()
  })

  it('never claims a button that owns the hidden file input (the retired heuristic)', () => {
    // The pre-fix code resolved a paperclip by "the last BUTTON before the hidden input" and claimed
    // it. That pairing must no longer produce any claim, whatever the button looks like.
    const owner = document.createElement('button')
    owner.type = 'button'
    owner.textContent = 'owner'
    tools.insertBefore(owner, input)
    const upstream = vi.fn()
    owner.addEventListener('click', upstream)
    owner.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(upstream).toHaveBeenCalledTimes(1)
    expect(menu()).toBeNull()
  })
})

describe('own paperclip entry', () => {
  it('mounts one trigger into the conversation.input.left slot anchor', () => {
    const mounted = trigger()
    expect(mounted).not.toBeNull()
    expect(anchor.contains(mounted)).toBe(true)
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(1)
    expect(mounted?.getAttribute('aria-haspopup')).toBe('menu')
    expect(mounted?.getAttribute('aria-expanded')).toBe('false')
  })

  it('mounts a second trigger when the composer remounts, and never duplicates one', async () => {
    const firstAnchor = anchor
    const secondAnchor = document.createElement('div')
    secondAnchor.setAttribute('data-slot', 'conversation.input.left')
    firstAnchor.after(secondAnchor)
    await flush()
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(2)

    // A no-op mutation must not add a third.
    document.body.appendChild(document.createElement('span'))
    await flush()
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(2)

    // Dropping one anchor must not resurrect or double-mount the survivor.
    secondAnchor.remove()
    await flush()
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(1)
  })

  it('re-mounts into an anchor React replaced in place (session switch)', async () => {
    // The slot outlet re-creates its anchor when the composer remounts; the old anchor and the trigger
    // inside it both leave the DOM. A replacement in the same document position must get one trigger,
    // not zero (missing) and not two (duplicate).
    const replacement = document.createElement('div')
    replacement.setAttribute('data-slot', 'conversation.input.left')
    anchor.replaceWith(replacement)
    await flush()
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(1)
    expect(replacement.querySelector('[data-dsh-attachment-picker-trigger]')).not.toBeNull()
  })

  it('keeps every registered trigger distinct across three composer anchors', async () => {
    // Guards the tracked-anchor bookkeeping: an anchor-keyed miss would mount the same trigger twice.
    const extra = [document.createElement('div'), document.createElement('div')]
    for (const node of extra) {
      node.setAttribute('data-slot', 'conversation.input.left')
      document.body.append(node)
    }
    await flush()
    const triggers = [...document.querySelectorAll('[data-dsh-attachment-picker-trigger]')]
    expect(triggers).toHaveLength(3)
    expect(new Set(triggers).size).toBe(3)
    for (const node of extra) node.remove()
    await flush()
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(1)
  })

  it('adopts a pre-existing trigger left by an earlier instance instead of duplicating it', async () => {
    // attach -> detach -> attach where the earlier instance's cleanup was skipped: the anchor already
    // holds a trigger this instance never mounted, so tracked-anchor bookkeeping alone cannot see it.
    enhancer.detach()
    const leftover = document.createElement('button')
    leftover.type = 'button'
    leftover.setAttribute('data-dsh-attachment-picker-trigger', '')
    anchor.append(leftover)
    enhancer = new AttachmentPickerMenuEnhancer()
    enhancer.attach()
    await flush()
    expect(document.querySelectorAll('[data-dsh-attachment-picker-trigger]')).toHaveLength(1)
    expect(document.querySelector('[data-dsh-attachment-picker-trigger]')).toBe(leftover)
  })

  it('does not chase unrelated mutations before any anchor exists, but mounts on the first one', async () => {
    // Guards the cheap path: "no anchor yet" must not mean "re-query the document on every batch".
    enhancer.detach()
    document.body.innerHTML = ''
    mountComposer()
    anchor.remove()
    const queried: string[] = []
    const original = document.querySelectorAll.bind(document)
    const spy = vi.spyOn(document, 'querySelectorAll').mockImplementation(((selector: string) => {
      queried.push(selector)
      return original(selector)
    }) as typeof document.querySelectorAll)
    enhancer = new AttachmentPickerMenuEnhancer()
    enhancer.attach()
    const before = queried.filter(selector => selector === '[data-slot="conversation.input.left"]').length
    // Ten unrelated mutation batches: none may trigger a slot query.
    for (let index = 0; index < 10; index += 1) {
      document.body.appendChild(document.createElement('span'))
      await flush()
    }
    const during = queried.filter(selector => selector === '[data-slot="conversation.input.left"]').length
    expect(during).toBe(before)

    // The anchor arriving is the one mutation that must mount.
    tools.append(anchor)
    await flush()
    expect(trigger()).not.toBeNull()
    spy.mockRestore()
  })

  it('opens the two-choice source menu from our trigger only', () => {
    const mounted = triggerOrThrow()
    mounted.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    const opened = menu()
    expect(opened).not.toBeNull()
    expect(opened?.getAttribute('role')).toBe('menu')
    expect(opened?.querySelectorAll('[role="menuitem"]')).toHaveLength(2)
    expect(opened?.textContent).toContain('上传附件')
    expect(opened?.textContent).toContain('上传图片')
    expect(mounted.getAttribute('aria-expanded')).toBe('true')
  })

  it('toggles the menu closed on a second press of our trigger', () => {
    const mounted = triggerOrThrow()
    mounted.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(menu()).not.toBeNull()
    mounted.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(menu()).toBeNull()
    expect(mounted.getAttribute('aria-expanded')).toBe('false')
  })

  it('uses English copy when the document language is English', () => {
    enhancer.detach()
    document.body.innerHTML = ''
    mountComposer('en-US')
    enhancer = new AttachmentPickerMenuEnhancer()
    enhancer.attach()
    triggerOrThrow().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(menu()?.textContent).toContain('Upload attachment')
    expect(menu()?.textContent).toContain('Upload image')
  })
})

describe('source selection delegates to the upstream input', () => {
  function openMenu(): void {
    triggerOrThrow().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
  }

  it('routes a generic file through the original input and restores accept', () => {
    const click = vi.spyOn(input, 'click').mockImplementation(() => {})
    openMenu()
    document.querySelector<HTMLElement>('[data-dsh-attachment-picker-item="file"]')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(click).toHaveBeenCalledTimes(1)
    expect(input.getAttribute('accept')).toBe('*/*')
    input.dispatchEvent(new Event('change'))
    expect(input.hasAttribute('accept')).toBe(false)
    expect(menu()).toBeNull()
  })

  it('routes an image through the original input and restores a pre-existing accept filter', () => {
    input.setAttribute('accept', '.txt')
    const click = vi.spyOn(input, 'click').mockImplementation(() => {})
    openMenu()
    document.querySelector<HTMLElement>('[data-dsh-attachment-picker-item="image"]')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(click).toHaveBeenCalledTimes(1)
    expect(input.getAttribute('accept')).toBe('image/*')
    input.dispatchEvent(new Event('change'))
    expect(input.getAttribute('accept')).toBe('.txt')
  })

  it('does not restore accept during the synchronous WebView focus caused by input.click', () => {
    const click = vi.spyOn(input, 'click').mockImplementation(() => {
      window.dispatchEvent(new Event('focus'))
    })
    openMenu()
    document.querySelector<HTMLElement>('[data-dsh-attachment-picker-item="image"]')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(click).toHaveBeenCalledTimes(1)
    expect(input.getAttribute('accept')).toBe('image/*')
    input.dispatchEvent(new Event('change'))
    expect(input.hasAttribute('accept')).toBe(false)
  })

  it('closes without clicking when the composer input is gone', () => {
    openMenu()
    input.remove()
    const click = vi.fn()
    // No input in the card: the menu must close instead of throwing.
    document.querySelector<HTMLElement>('[data-dsh-attachment-picker-item="file"]')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(click).not.toHaveBeenCalled()
    expect(menu()).toBeNull()
  })
})

describe('dismissal', () => {
  it('closes on outside pointerdown and on Escape', () => {
    triggerOrThrow().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(menu()).toBeNull()

    const mounted = triggerOrThrow()
    mounted.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    expect(menu()).toBeNull()
    expect(mounted.getAttribute('aria-expanded')).toBe('false')
  })

  it('keeps the menu open when the pointerdown lands inside it', () => {
    triggerOrThrow().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    menu()!.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(menu()).not.toBeNull()
  })
})

describe('phone-runtime guard', () => {
  it('detach removes the trigger and the menu', () => {
    triggerOrThrow().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    enhancer.detach()
    expect(trigger()).toBeNull()
    expect(menu()).toBeNull()
  })

  it('does not throw when the slot anchor is absent at attach time, and mounts later', async () => {
    enhancer.detach()
    document.body.innerHTML = ''
    mountComposer()
    anchor.remove()
    enhancer = new AttachmentPickerMenuEnhancer()
    expect(() => { enhancer.attach() }).not.toThrow()
    expect(trigger()).toBeNull()
    tools.append(anchor)
    await flush()
    expect(trigger()).not.toBeNull()
  })
})

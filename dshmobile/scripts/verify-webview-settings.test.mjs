import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';

const require = createRequire(new URL('../dsh-client-ui-responsive/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const source = readFileSync(new URL('./verify-webview-015.mjs', import.meta.url), 'utf8');
const declaration = source.split('\n').find(line => line.startsWith('const RESET_BUTTON_PROBE ='));
const probe = vm.runInNewContext('const MOBILE_FORM_MAX_WIDTH = 767; ' + declaration + '; RESET_BUTTON_PROBE');

async function exercise({ entry = 'more', control = true, reset = true, disabled = false } = {}) {
  const dom = new JSDOM('<body></body>', { runScripts: 'outside-only' });
  const { window: w } = dom;
  Object.defineProperty(w, 'innerWidth', { value: 1067 });
  w.PointerEvent = w.MouseEvent;
  w.setTimeout = callback => { queueMicrotask(callback); return 0; };
  w.HTMLElement.prototype.getBoundingClientRect = function () {
    return { x: 10, y: 10, width: 120, height: 36 };
  };
  const button = (text, onClick, role) => {
    const el = w.document.createElement('button');
    el.textContent = text;
    if (role) el.setAttribute('role', role);
    el.onclick = onClick;
    return el;
  };
  let settingsOpened = 0;
  const openSettings = () => {
    settingsOpened++;
    const dialog = w.document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    if (control) dialog.append(button('手机控制', () => {
      const section = w.document.createElement('section');
      section.id = 'dsh-phone-control-title';
      if (reset) {
        const b = button('重置链接');
        b.disabled = disabled;
        section.append(b);
      }
      dialog.append(section);
    }));
    w.document.body.append(dialog);
  };
  if (entry === 'direct') {
    const settings = button('设置', openSettings);
    settings.setAttribute('aria-label', '设置');
    w.document.body.append(settings);
  } else if (entry === 'more') {
    const more = button('更多', () => w.document.body.append(button('设置', openSettings, 'menuitem')));
    more.setAttribute('aria-label', '账号菜单');
    w.document.body.append(more);
  }
  try { return { result: await w.eval(probe), settingsOpened }; }
  finally { w.close(); }
}

test('desktop account menu reaches the actual settings and phone control section', async () => {
  const { result, settingsOpened } = await exercise();
  assert.equal(settingsOpened, 1);
  assert.equal(result.section, true);
  assert.equal(result.present, true);
  assert.equal(result.disabled, false);
  assert.deepEqual(Array.from(result.steps), ['more', 'settings', 'tab']);
});
test('existing direct settings entry remains supported', async () => {
  const { result } = await exercise({ entry: 'direct' });
  assert.equal(result.present, true);
  assert.deepEqual(Array.from(result.steps), ['settings', 'tab']);
});
test('negative control: missing settings entry still fails', async () => {
  assert.equal((await exercise({ entry: 'absent' })).result, 'no-settings-entry');
});
test('negative control: missing phone control section still fails', async () => {
  assert.match((await exercise({ control: false })).result, /^settings-not-opened:/);
});
test('negative control: missing reset button still fails', async () => {
  assert.equal((await exercise({ reset: false })).result, 'button-absent');
});
test('disabled reset remains disabled in the returned device verdict', async () => {
  assert.equal((await exercise({ disabled: true })).result.disabled, true);
});

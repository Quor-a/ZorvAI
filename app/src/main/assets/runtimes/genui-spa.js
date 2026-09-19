/**
 * GenUI Spa —— 多层界面路由（零依赖，为 AI 生成的界面设计）
 *
 * 一个文档 = 一个完整站点。页面用 <section data-page="id"> 声明：
 *
 *   <!-- 根页（tab 平级）：互切时清空层级栈 -->
 *   <section data-page="home" data-root>…首页…</section>
 *   <section data-page="intro" data-root>…功能介绍…</section>
 *   <section data-page="download" data-root>…产品下载…</section>
 *   <!-- 子页（下钻）：从哪个根页进入就写 data-parent 指向它 -->
 *   <section data-page="download-detail" data-parent="download">…详情…</section>
 *
 * 导航三件套（无需手写任何事件）：
 *   <button data-go="intro">功能介绍</button>     ← 点击切换到该页
 *   <button data-go="download-detail">查看详情</button> ← 点击下钻子页
 *   <button data-back>返回</button>               ← 点击返回上一层
 *
 * JS API：Spa.go(id) / Spa.back() / Spa.current() / Spa.depth()
 * 深链：#/page-id —— 刷新或界面回放后自动停留在当前页
 * 事件：document 派发 spa:change（event.detail = {from, to, depth}），可监听做埋点/联动
 */
(function () {
  'use strict';

  var pages = {};      // id -> {el, root, parent}
  var stack = [];      // 层级栈：栈底恒为根页
  var ready = false;

  function collect() {
    pages = {};
    var nodes = document.querySelectorAll('[data-page]');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var id = el.getAttribute('data-page');
      pages[id] = {
        el: el,
        root: el.hasAttribute('data-root'),
        parent: el.getAttribute('data-parent') || null
      };
    }
  }

  function show(id) {
    for (var k in pages) {
      var on = (k === id);
      var el = pages[k].el;
      el.style.display = on ? '' : 'none';
      el.classList.toggle('spa-active', on);
    }
    window.scrollTo(0, 0);
    try { history.replaceState(null, '', '#' + encodeURIComponent(id)); } catch (e) {}
    document.dispatchEvent(new CustomEvent('spa:change', { detail: { to: id, depth: stack.length } }));
  }

  function go(id) {
    if (!ready || !pages[id]) return;
    if (stack[stack.length - 1] === id) return;
    if (pages[id].root) {
      stack = [id];                       // 根页互切 = tab 切换，清栈
    } else {
      // 子页：栈里已存在（回放/深链）则截断到该层，否则压栈
      var at = stack.indexOf(id);
      if (at >= 0) stack = stack.slice(0, at + 1);
      else stack.push(id);
    }
    show(id);
  }

  function back() {
    if (!ready) return;
    if (stack.length > 1) {
      stack.pop();
      show(stack[stack.length - 1]);
    } else {
      // 已在根页：回到第一个根页兜底
      for (var k in pages) if (pages[k].root) { if (stack[0] !== k) { stack = [k]; show(k); } break; }
    }
  }

  function current() { return stack[stack.length - 1] || null; }
  function depth() { return stack.length; }

  // 事件委托：data-go / data-back 全文档自动绑定（含动态生成的节点）
  document.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t !== document.body) {
      if (t.hasAttribute && t.hasAttribute('data-go')) {
        e.preventDefault();
        go(t.getAttribute('data-go'));
        return;
      }
      if (t.hasAttribute && (t.hasAttribute('data-back') || t.getAttribute('data-go') === '__back')) {
        e.preventDefault();
        back();
        return;
      }
      t = t.parentNode;
    }
  }, false);

  function boot() {
    if (ready) return;
    collect();
    // 初始页：hash 指定 > 第一个可见根页 > 第一个页面
    var start = null;
    var h = decodeURIComponent((location.hash || '').replace(/^#\/?/, ''));
    if (h && pages[h]) start = h;
    if (!start) for (var k in pages) if (pages[k].root) { start = k; break; }
    if (!start) for (var k2 in pages) { start = k2; break; }
    stack = [start];
    show(start);
    ready = true;
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();

  // 深链：hash 变化（如回放恢复）时跟随
  window.addEventListener('hashchange', function () {
    var h = decodeURIComponent((location.hash || '').replace(/^#\/?/, ''));
    if (ready && h && pages[h] && current() !== h) go(h);
  });

  window.Spa = { go: go, back: back, current: current, depth: depth, refresh: function(){ collect(); } };
})();

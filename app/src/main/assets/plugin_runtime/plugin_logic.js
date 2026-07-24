/* =========================================================================
 * 插件逻辑层（跑在 QuickJS 原生沙箱，不在 WebView 内）。
 *  - 通过 hostSetData(path, value) 把 setData 的 path-diff 回传渲染层 WebView。
 *  - 通过 hostCallApi(api, paramsJson) 调宿主能力 my.*（结果 JSON 同步返回）。
 *  - 页面方法挂到 globalThis.__page；渲染层事件经 QuickJsEngine.invokeMethod 调用，
 *    原生桥会把 value 作为唯一参数传给方法（tap 事件时 value = dataset）。
 * 等效 .mext 的 app.js；与 plugin_render.html 的 uiSpec 配套。
 * ========================================================================= */

/* 小程序式 Page 助手：提供 setData（自动算 path-diff 并 hostSetData 回传）。 */
function Page(cfg) {
  var pageData = JSON.parse(JSON.stringify(cfg.data || {}));
  function getByPath(o, p) { return p.split('.').reduce(function (a, k) { return a == null ? undefined : a[k]; }, o); }
  function setByPath(o, p, v) {
    var ks = p.split('.'); var c = o;
    for (var i = 0; i < ks.length - 1; i++) { if (c[ks[i]] == null) c[ks[i]] = {}; c = c[ks[i]]; }
    c[ks[ks.length - 1]] = v;
  }
  var ctx = { data: pageData };
  ctx.setData = function (patch) {
    for (var k in patch) {
      var nv = patch[k];
      var ov = getByPath(pageData, k);
      if (JSON.stringify(ov) !== JSON.stringify(nv)) {
        setByPath(pageData, k, nv);
        hostSetData('data.' + k, nv);
      }
    }
    ctx.data = pageData;
  };
  for (var m in cfg) { if (m !== 'data') ctx[m] = cfg[m].bind(ctx); }
  globalThis.__page = ctx;
  // 初始数据全量回传（逐字段 path-diff）
  (function walk(pre, val) {
    if (Array.isArray(val)) hostSetData(pre, val);
    else if (val && typeof val === 'object') { for (var k in val) walk(pre ? pre + '.' + k : k, val[k]); }
    else hostSetData(pre, val);
  })('data', pageData);
  return ctx;
}

/* 宿主能力桥（my.*）：实现与渲染层/原生一致的接口，但走 hostCallApi。 */
var my = {
  storage: {
    get: function (k) {
      try { return JSON.parse(hostCallApi('storage.get', JSON.stringify({ key: k }))).value; }
      catch (e) { return null; }
    },
    set: function (k, v) { hostCallApi('storage.set', JSON.stringify({ key: k, value: v })); }
  },
  toast: function (msg) { hostCallApi('ui.toast', JSON.stringify({ msg: msg })); }
};

/* ---------- 示例插件逻辑（与原 plugin_runtime.html 同构）---------- */
var many = []; for (var i = 0; i < 200; i++) many.push({ text: '待办项 #' + (i + 1), done: false });
Page({
  data: {
    modalOpen: false, input: '', todos: many,
    about: '这是一个<b>组件完备</b>的插件运行时示例：<br>· 200 条待办走<i>虚拟化列表</i><br>· Modal 弹层<br>· RichText 渲染带格式文本<br>· 添加待办会写入 my.storage 并弹 my.toast',
    notes: '<p>列表滚动只创建视口内节点，长列表不掉帧。</p><b>加粗</b>、<i>斜体</i>、<a href="https://example.com">链接</a> 受白名单约束。'
  },
  openModal: function () { this.setData({ modalOpen: true }); },
  closeModal: function () { this.setData({ modalOpen: false }); },
  onInput: function (v) { this.setData({ input: v }); },
  addTodo: function () {
    if (!this.data.input) return;
    this.setData({ todos: this.data.todos.concat([{ text: this.data.input, done: false }]), input: '' });
    my.storage.set('last_todo', this.data.input);
    my.toast('已添加：' + this.data.input);
  },
  toggle: function (d) {
    var i = d.index;
    var t = this.data.todos.slice();
    t[i] = Object.assign({}, t[i], { done: !t[i].done });
    this.setData({ todos: t });
  }
});

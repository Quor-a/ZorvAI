/**
 * 桥接运行时脚本（由MiniAppEngine注入到每个页面<head>）。
 * 提供两部分能力：
 *   1) 轻量运行时：Page() / Component() / mountComponent() / 数据绑定 / 事件绑定；
 *   2) JSBridge SDK：封装 native.invoke，暴露 storage / ui / device / network / router / kotlin 命名空间，
 *      并接管 Native->JS 的 response / event 回调（Promise 化）。
 *      kotlin 命名空间即"原生 Kotlin 语言"——小程序可调用真·Android/Kotlin 能力（剪贴板/分享/打开App/通知/TTS 等）。
 */
(function (global) {
  'use strict';

  /* ===================== 轻量运行时 ===================== */

  function isFn(v) { return typeof v === 'function'; }

  function renderKey(instance, key, root) {
    root = root || document;
    var nodes = root.querySelectorAll('[data-bind="' + key + '"]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].textContent = instance.data[key];
    }
  }

  function render(instance, patch, root) {
    root = root || document;
    var keys = patch ? Object.keys(patch) : Object.keys(instance.data);
    for (var i = 0; i < keys.length; i++) renderKey(instance, keys[i], root);
  }

  function bindEvents(instance, root) {
    root = root || document;
    var acts = root.querySelectorAll('[data-action]');
    for (var i = 0; i < acts.length; i++) {
      (function (el) {
        var method = el.getAttribute('data-action');
        var evt = el.getAttribute('data-event') || 'click';
        el.addEventListener(evt, function (e) {
          if (isFn(instance[method])) instance[method].call(instance, e);
        });
      })(acts[i]);
    }
    var models = root.querySelectorAll('[data-model]');
    for (var j = 0; j < models.length; j++) {
      (function (el) {
        var key = el.getAttribute('data-model');
        var evt = el.getAttribute('data-event') || 'input';
        el.addEventListener(evt, function () {
          var patch = {}; patch[key] = el.value; instance.setData(patch);
        });
      })(models[j]);
    }
  }

  function makeInstance(options, root) {
    var instance = {
      data: JSON.parse(JSON.stringify(options.data || {})),
      __options: options,
      __root: root || null,
      setData: function (patch) {
        var self = this;
        Object.assign(this.data, patch);
        render(this, patch, this.__root);
        if (options.observers) {
          var keys = patch ? Object.keys(patch) : Object.keys(this.data);
          keys.forEach(function (k) {
            if (options.observers[k]) {
              try { options.observers[k].call(self, patch ? patch[k] : self.data[k]); } catch (e) {}
            }
          });
        }
      }
    };
    Object.keys(options).forEach(function (k) {
      if (k === 'data') return;
      if (isFn(options[k])) instance[k] = options[k];
    });
    // 将 props 声明的默认值合并进 data（props 同名 key 不覆盖 data 已有值）
    if (options.props) {
      Object.keys(options.props).forEach(function (k) {
        if (Object.prototype.hasOwnProperty.call(instance.data, k)) return;
        var p = options.props[k];
        var def = (p && typeof p === 'object' && Object.prototype.hasOwnProperty.call(p, 'default'))
          ? p.default : undefined;
        instance.data[k] = def;
      });
    }
    // 展开 options.methods 把方法挂到实例
    if (options.methods) {
      Object.keys(options.methods).forEach(function (k) {
        if (isFn(options.methods[k])) instance[k] = options.methods[k];
      });
    }
    return instance;
  }

  global.Page = function (options) {
    var page = makeInstance(options, null);
    global.__currentPage = page;
    if (document.readyState !== 'loading') onPageReady(page, options);
    else document.addEventListener('DOMContentLoaded', function () { onPageReady(page, options); });
  };

  function onPageReady(page, options) {
    bindEvents(page, document);
    try { if (options.onLoad) options.onLoad.call(page, global.__pageQuery || {}); } catch (e) { console.error(e); }
    render(page, null, document);
    try { if (options.onShow) options.onShow.call(page); } catch (e) {}
    setTimeout(function () { try { if (options.onReady) options.onReady.call(page); } catch (e) {} }, 0);
  }

  global.Component = function (options) { return makeInstance(options, null); };

  global.mountComponent = function (instance, containerId) {
    var el = document.getElementById(containerId);
    instance.__root = el;
    bindEvents(instance, el);
    if (instance.__options.lifetimes && instance.__options.lifetimes.attached) {
      setTimeout(function () { try { instance.__options.lifetimes.attached.call(instance); } catch (e) {} }, 0);
    }
    render(instance, null, el);
    return instance;
  };

  global.render = render;

  // 触发当前页面 onHide
  global.__firePageHide = function () {
    var page = global.__currentPage;
    if (page && page.__options && isFn(page.__options.onHide)) {
      try { page.__options.onHide.call(page); } catch (e) { console.error(e); }
    }
  };

  /* ===================== JSBridge SDK ===================== */

  // 捕获原始的 Java 注入对象（addJavascriptInterface("native")）
  var _native = global.native;
  var callbacks = (global.__bridgeCallbacks = {});
  var seq = 0;

  function genId() { seq++; return 'req_' + seq + '_' + Date.now(); }

  function invoke(module, method, params, sync) {
    return new Promise(function (resolve, reject) {
      var id = genId();
      callbacks[id] = { resolve: resolve, reject: reject };
      var msg = {
        id: id,
        type: 'invoke',
        module: module,
        method: method,
        params: params || {},
        sync: !!sync,
        timestamp: Date.now()
      };
      _native.invoke(JSON.stringify(msg));
    });
  }

  // Native -> JS 响应回调：触发对应 Promise 的 resolve / reject
  global.__onBridgeResponse = function (resp) {
    var cb = callbacks[resp.id];
    if (!cb) return;
    delete callbacks[resp.id];
    if (resp.code === 0) cb.resolve(resp.data);
    else cb.reject(new Error(resp.message || 'bridge error'));
  };

  // Native -> JS 事件推送
  global.__bridgeEventListeners = {};
  global.__onBridgeEvent = function (evt) {
    var list = global.__bridgeEventListeners[evt.event];
    if (!list) return;
    list.forEach(function (fn) { try { fn(evt.data); } catch (e) {} });
  };
  global.onBridgeEvent = function (event, fn) {
    if (!global.__bridgeEventListeners[event]) global.__bridgeEventListeners[event] = [];
    global.__bridgeEventListeners[event].push(fn);
  };

  // 对外暴露的友好 SDK（覆盖 window.native）
  global.native = {
    invoke: function (json) { return _native.invoke(json); },
    storage: {
      setItem: function (k, v) { return invoke('storage', 'setItem', { key: k, value: v }); },
      getItem: function (k) { return invoke('storage', 'getItem', { key: k }); },
      removeItem: function (k) { return invoke('storage', 'removeItem', { key: k }); },
      clear: function () { return invoke('storage', 'clear', {}); }
    },
    ui: {
      toast: function (t, icon) { return invoke('ui', 'toast', { title: t, icon: icon || '' }); },
      dialog: function (o) { return invoke('ui', 'dialog', o || {}); },
      loading: function (o) { return invoke('ui', 'loading', o || {}); },
      setNavigationBarTitle: function (t) { return invoke('ui', 'setNavigationBarTitle', { title: t }); }
    },
    device: {
      getSystemInfo: function () { return invoke('device', 'getSystemInfo', {}); },
      vibrate: function (o) { return invoke('device', 'vibrate', o || {}); }
    },
    network: {
      request: function (o) { return invoke('network', 'request', o || {}); }
    },
    router: {
      navigateTo: function (url) { return invoke('router', 'navigateTo', { url: url }); },
      navigateBack: function () { return invoke('router', 'navigateBack', {}); }
    },
    // 原生 Kotlin 语言命名空间：融合原生能力到小程序
    kotlin: {
      getAppInfo: function () { return invoke('kotlin', 'getAppInfo', {}); },
      copyText: function (t) { return invoke('kotlin', 'copyText', { text: t }); },
      getClipboard: function () { return invoke('kotlin', 'getClipboard', {}); },
      shareText: function (t, title) { return invoke('kotlin', 'shareText', { text: t, title: title || '' }); },
      openUrl: function (url) { return invoke('kotlin', 'openUrl', { url: url }); },
      openApp: function (pkg) { return invoke('kotlin', 'openApp', { packageName: pkg }); },
      notify: function (title, body) { return invoke('kotlin', 'notify', { title: title, body: body }); },
      speak: function (text) { return invoke('kotlin', 'speak', { text: text }); }
    },
    // 便捷别名
    navigateTo: function (url) { return invoke('router', 'navigateTo', { url: url }); },
    navigateBack: function () { return invoke('router', 'navigateBack', {}); }
  };

  console.log('[MiniApp] bridge runtime ready');
})(window);
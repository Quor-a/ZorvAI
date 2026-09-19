/**
 * GenUI Backend —— AI 自写后端扩展框架（页内微服务骨架，零依赖）
 *
 * 把"后端逻辑"从界面代码里分离出来，AI 像写服务器一样写数据层：
 *
 *   // 1. 注册路由：界面代码只管调 Backend.call，不关心实现
 *   Backend.route('GET', '/api/weather', async (req) => {
 *     const pos = await Backend.geo();                    // 真实定位
 *     const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + pos.lat + '&longitude=' + pos.lng + '&current=temperature_2m&timezone=auto';
 *     return Backend.cache('weather', 10 * 60_000, async () => {   // 10 分钟缓存
 *       const r = await MoBridge.net.proxy(url);           // 真实 API（引擎已放行）
 *       return JSON.parse(r.body).current;
 *     });
 *   });
 *
 *   // 2. 定时任务（常驻，画布就绪即触发，重开补跑）——真正的后台扩展
 *   Backend.cron('sync', 15, async () => {
 *     const data = await Backend.call('GET', '/api/weather');
 *     await db.weather.put({ t: Date.now(), ...data });   // 配合 Dexie 落库
 *   });
 *
 *   // 3. 界面侧调用
 *   const weather = await Backend.call('GET', '/api/weather');
 *
 * 组成：路由器 / 轻量定时（页面级）/ 常驻定时（MoBridge.task，进程存活期间有效）/
 *      cache-aside / 离线队列（store 持久化，上线即冲）/ 重试 / 环形日志。
 */
(function () {
  'use strict';

  var routes = [];            // {method, path, handler}
  var logs = [];              // 环形日志（最多 200 条）
  var LOG_MAX = 200;

  function log(kind, msg) {
    logs.push({ t: Date.now(), kind: kind, msg: String(msg).slice(0, 300) });
    if (logs.length > LOG_MAX) logs.shift();
  }

  function matchRoute(method, path) {
    for (var i = routes.length - 1; i >= 0; i--) {
      var r = routes[i];
      if (r.method !== method) continue;
      if (r.path === path) return { r: r, params: {} };
      // 支持 /api/item/:id 形式
      var rp = r.path.split('/'), pp = path.split('/');
      if (rp.length === pp.length && rp.every(function (seg, k) {
        return seg.charAt(0) === ':' ? true : seg === pp[k];
      })) {
        var params = {};
        rp.forEach(function (seg, k) { if (seg.charAt(0) === ':') params[seg.slice(1)] = pp[k]; });
        return { r: r, params: params };
      }
    }
    return null;
  }

  var Backend = {

    /** 注册路由：Backend.route('GET','/api/x/:id', async (req)=>data)；req={params, query, body} */
    route: function (method, path, handler) {
      routes = routes.filter(function (r) { return !(r.method === method && r.path === path); });
      routes.push({ method: method, path: path, handler: handler });
      log('route', method + ' ' + path);
      return Backend;
    },

    /** 调用已注册路由（界面→后端的入口）。返回 handler 的返回值 */
    call: function (method, path, body) {
      method = (method || 'GET').toUpperCase();
      var m = matchRoute(method, path);
      if (!m) return Promise.reject(new Error('Backend 未注册路由: ' + method + ' ' + path));
      var qs = path.indexOf('?') >= 0 ? path.slice(path.indexOf('?') + 1) : '';
      var cleanPath = qs ? path.slice(0, path.indexOf('?')) : path;
      var query = {};
      qs.split('&').forEach(function (kv) {
        if (!kv) return;
        var p = kv.split('=');
        query[decodeURIComponent(p[0])] = decodeURIComponent(p[1] || '');
      });
      var t0 = Date.now();
      return Promise.resolve().then(function () {
        return m.r.handler({ params: m.params, query: query, body: body, path: cleanPath });
      }).then(function (data) {
        log('call', method + ' ' + cleanPath + ' ok ' + (Date.now() - t0) + 'ms');
        return data;
      }).catch(function (e) {
        log('error', method + ' ' + cleanPath + ' ' + (e && e.message || e));
        throw e;
      });
    },

    /** 页面级轻量定时：每 intervalMs 执行 fn（页面关闭即停）；常驻任务用 Backend.task.cron */
    every: function (id, intervalMs, fn) {
      if (Backend.every._timers[id]) clearInterval(Backend.every._timers[id]);
      Backend.every._timers[id] = setInterval(fn, Math.max(5000, intervalMs));
      return Backend;
    },
    _timers: {},

    /** 常驻定时（存端上，画布就绪即触发、重开补跑一次；间隔按分钟）：依赖 MoBridge.task */
    task: {
      cron: function (id, intervalMin, fn) {
        var code = '(' + fn.toString() + ')()';
        return MoBridge.task.schedule(id, intervalMin, code);
      },
      cancel: function (id) { return MoBridge.task.cancel(id); },
      list: function () { return MoBridge.task.list(); }
    },

    /** cache-aside：带 TTL 的取数模板。Backend.cache('key', 600000, fetcher) */
    _cache: {},
    cache: function (key, ttlMs, fetcher) {
      var hit = Backend._cache[key];
      if (hit && Date.now() - hit.at < ttlMs) return Promise.resolve(hit.data);
      return Promise.resolve().then(fetcher).then(function (data) {
        Backend._cache[key] = { at: Date.now(), data: data };
        return data;
      });
    },

    /** 离线队列：断网/失败的任务进队列，push 后自动择机重放（store 持久化） */
    _qKey: '__backend.queue',
    queue: {
      all: function () {
        return MoBridge.store.get(Backend._qKey).then(function (r) { return (r && r.value) || []; });
      },
      _save: function (arr) { return MoBridge.store.put(Backend._qKey, arr); },
      /** push(runner)：runner 为返回 Promise 的函数；失败自动留在队列稍后重试 */
      push: function (runner) {
        return Backend.queue.all().then(function (arr) {
          arr.push('(' + runner.toString() + ')');
          return Backend.queue._save(arr);
        }).then(Backend.queue.drain);
      },
      drain: function () {
        return Backend.queue.all().then(function (arr) {
          if (!arr.length) return [];
          var remain = [];
          var chain = Promise.resolve();
          var results = [];
          arr.forEach(function (code) {
            chain = chain.then(function () {
              return eval(code)().then(function (r) { results.push(r); })
                .catch(function () { remain.push(code); });
            });
          });
          return chain.then(function () {
            return Backend.queue._save(remain).then(function () { return results; });
          });
        });
      }
    },

    /** 重试模板：Backend.retry(fn, 3) */
    retry: function (fn, times) {
      var n = times || 3;
      function attempt(i) {
        return Promise.resolve().then(fn).catch(function (e) {
          if (i >= n) throw e;
          return new Promise(function (res) { setTimeout(res, 800 * i); }).then(function () { return attempt(i + 1); });
        });
      }
      return attempt(1);
    },

    /** 真实定位（引擎已放行，系统权限弹窗把守）→ Promise<{lat,lng}> */
    geo: function () {
      return new Promise(function (res, rej) {
        if (!navigator.geolocation) return rej(new Error('此环境不支持定位'));
        navigator.geolocation.getCurrentPosition(
          function (p) { res({ lat: p.coords.latitude, lng: p.coords.longitude }); },
          function (e) { rej(new Error('定位失败：' + e.message)); },
          { timeout: 10000 }
        );
      });
    },

    /** 环形日志（后端面板可直接展示）：Backend.logs() → [{t, kind, msg}] */
    logs: function () { return logs.slice(); },

    /** 联网状态：online → 监听 online/offline 事件驱动队列冲放 */
    whenOnline: function (fn) {
      window.addEventListener('online', function () { Promise.resolve().then(fn).then(Backend.queue.drain); });
      if (navigator.onLine) Promise.resolve().then(fn);
      return Backend;
    }
  };

  window.Backend = Backend;
})();

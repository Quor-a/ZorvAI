/**
 * GenUI Explore —— 探索引擎（页面侧）：新闻 / 社区内容 / GitHub。
 *
 * 数据源全部免 key、https，经 MoBridge.net.proxy 调用（绕 CORS、真实 UA、60 次/分）。
 * AI 用它画真实可用的探索界面：新闻聚合页、技术社区雷达、GitHub 趋势/检索页……
 *
 *   const news = await Explore.news('人工智能', 12);
 *   news.items.forEach(n => render(n.title, n.url, n.source, n.date, n.snippet));
 *
 *   const c = await Explore.community('kotlin compose', 8);  // hackernews/stackoverflow/reddit
 *   const g = await Explore.github('ai agent', 'repositories', 10); // 按 star 排序
 *
 * 所有方法带多源并发与容错：单源挂掉不空手，diagnostics 里能看到每源状态。
 */
(function () {
  'use strict';

  function enc(s) { return encodeURIComponent(s || ''); }

  async function proxy(url) {
    const r = await MoBridge.net.proxy(url);
    if (r.status !== 200) throw new Error('HTTP ' + r.status);
    return r.body;
  }

  function merge(items, max) {
    const seen = new Set(), out = [];
    for (const it of items) {
      const key = (it.url || '').replace(/^https?:\/\//, '').split(/[/?]/)[0] + '|' + it.title;
      if (!it.title || seen.has(key)) continue;
      seen.add(key); out.push(it);
      if (out.length >= max) break;
    }
    return out;
  }

  function parseRss(xml, source) {
    const doc = new DOMParser().parseFromString(xml, 'text/xml');
    const nodes = doc.querySelectorAll('item, entry');
    const out = [];
    nodes.forEach(n => {
      const get = t => { const e = n.querySelector(t); return e ? e.textContent.trim() : ''; };
      let link = get('link');
      if (!link) { const le = n.querySelector('link'); link = le ? (le.getAttribute('href') || '') : ''; }
      out.push({
        title: get('title'),
        url: link,
        source: source,
        date: (get('pubDate') || get('updated') || get('published')).slice(0, 16),
        snippet: get('description').replace(/<[^>]+>/g, '').slice(0, 180)
      });
    });
    return out;
  }

  const Explore = {

    /** 新闻：搜狗新闻 + 百度新闻（国内可达，HTML解析）+ Google News RSS（海外兜底），三源并发 */
    async news(query, max) {
      max = max || 12;
      const diagnostics = {}, items = [];
      const clean = s => (s || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ')
        .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').trim();
      const jobs = {
        // 搜狗新闻：结果块 h3.vr-tit > a；摘要 div.fz-mid/.space-txt/.text-layout
        'sogou-news': async () => {
          const html = await proxy('https://news.sogou.com/news?query=' + enc(query));
          const doc = new DOMParser().parseFromString(html, 'text/html');
          [...doc.querySelectorAll('h3.vr-tit')].slice(0, max).forEach(h3 => {
            const a = h3.querySelector('a'); if (!a) return;
            const block = h3.closest('div') || h3.parentElement;
            const snip = block ? clean((block.querySelector('.fz-mid, .space-txt, .text-layout') || {}).textContent) : '';
            const url = a.getAttribute('href') || '';
            if (url.startsWith('http')) items.push({ title: clean(a.textContent), url,
              source: 'sogou-news', date: '', snippet: snip.slice(0, 200) });
          });
        },
        // 百度新闻：h3[class*=news-title] > a；真实地址优先取同块内 [mu] 属性
        'baidu-news': async () => {
          const html = await proxy('https://www.baidu.com/s?tn=news&word=' + enc(query));
          const doc = new DOMParser().parseFromString(html, 'text/html');
          [...doc.querySelectorAll('h3[class*="news-title"]')].slice(0, max).forEach(h3 => {
            const a = h3.querySelector('a'); if (!a) return;
            const block = h3.closest('div') || h3.parentElement;
            const muEl = block ? block.querySelector('[mu]') : null;
            const url = (muEl && muEl.getAttribute('mu')) || a.getAttribute('href') || '';
            const snip = block ? clean((block.querySelector('.c-color-text') || {}).textContent) : '';
            if (url.startsWith('http')) items.push({ title: clean(a.textContent), url,
              source: 'baidu-news', date: '', snippet: snip.slice(0, 200) });
          });
        },
        // Google News RSS（海外网络兜底，国内不可达时由前两源顶着）
        'google-news': async () => {
          const xml = await proxy('https://news.google.com/rss/search?q=' + enc(query) + '&hl=zh-CN&gl=CN&ceid=CN:zh-Hans');
          items.push(...parseRss(xml, 'google-news'));
        }
      };
      await Promise.all(Object.entries(jobs).map(async ([name, job]) => {
        try { await job(); diagnostics[name] = 'ok(' + items.filter(i => i.source === name).length + ')'; }
        catch (e) { diagnostics[name] = e.message; }
      }));
      return { items: merge(items, max), diagnostics };
    },

    /** 社区内容：Hacker News + Stack Overflow + Reddit，三源并发 */
    async community(query, max) {
      max = max || 10;
      const urls = {
        hackernews: 'https://hn.algolia.com/api/v1/search?query=' + enc(query) + '&tags=story&hitsPerPage=' + max,
        stackoverflow: 'https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=' + enc(query) + '&site=stackoverflow&pagesize=' + max,
        reddit: 'https://www.reddit.com/search.json?q=' + enc(query) + '&limit=' + max
      };
      const diagnostics = {}, items = [];
      await Promise.all(Object.entries(urls).map(async ([name, url]) => {
        try {
          const data = JSON.parse(await proxy(url));
          if (name === 'hackernews') {
            (data.hits || []).forEach(h => items.push({
              title: h.title || h.story_title || '', url: h.url || ('https://news.ycombinator.com/item?id=' + h.objectID),
              community: 'hackernews', score: h.points || 0, author: h.author || '',
              date: (h.created_at || '').slice(0, 10), snippet: (h.story_text || '').replace(/<[^>]+>/g, '').slice(0, 180)
            }));
          } else if (name === 'stackoverflow') {
            (data.items || []).forEach(q => items.push({
              title: q.title || '', url: q.link || '', community: 'stackoverflow',
              score: q.score || 0, author: (q.owner && q.owner.display_name) || '',
              date: q.creation_date ? new Date(q.creation_date * 1000).toISOString().slice(0, 10) : '',
              snippet: (q.tags || []).map(t => '#' + t).join(' ')
            }));
          } else {
            (data.data && data.data.children || []).forEach(c => {
              const d = c.data || {};
              items.push({
                title: d.title || '', url: 'https://www.reddit.com' + (d.permalink || ''),
                community: 'reddit', score: d.score || 0, author: d.author || '',
                date: d.created_utc ? new Date(d.created_utc * 1000).toISOString().slice(0, 10) : '',
                snippet: (d.selftext || '').slice(0, 180)
              });
            });
          }
          diagnostics[name] = 'ok';
        } catch (e) { diagnostics[name] = e.message; }
      }));
      return { items: merge(items, max * 2), diagnostics };
    },

    /** GitHub：官方 Search API（免 key，按 star/监控者排序） */
    async github(query, type, max) {
      type = type || 'repositories'; max = max || 10;
      const url = type === 'users'
        ? 'https://api.github.com/search/users?q=' + enc(query) + '&per_page=' + max
        : 'https://api.github.com/search/repositories?q=' + enc(query) + '&sort=stars&order=desc&per_page=' + max;
      const data = JSON.parse(await proxy(url));
      const items = (data.items || []).map(x => type === 'users' ? {
        title: x.login || '', url: x.html_url || '', avatar: x.avatar_url || '',
        score: x.score || 0, snippet: x.type || 'user'
      } : {
        title: x.full_name || '', url: x.html_url || '',
        stars: x.stargazers_count || 0, language: x.language || '',
        date: (x.pushed_at || '').slice(0, 10), snippet: x.description || ''
      });
      return { total: data.total_count || items.length, items, diagnostics: { github: 'ok' } };
    }
  };

  window.Explore = Explore;
})();

/* ═══════════════════════════════════════════════════════════════════════
   Web UI 真渲染器 —— 把「原生渲染+源码」占位升级为真正渲染
   ═══════════════════════════════════════════════════════════════════════

   数据来源：宿主 `WebUiProjection` 用**同一批解析器**产出的渲染数据
   （CardFence.toCards + serializeCard / Aip.parse / A2uiInterpreter /
   parseCardSpec）。这里只负责「画」，不负责「猜」。

   渲染器清单：
     · renderMermaid   —— mermaid.min.js 离线渲染（同原生 mermaid_render.html）
     · renderAip         —— AIP 排版（heading/paragraph/list/table/code/quote/
                              callout/divider/image/chart/columns/steps/timeline/
                              mindmap/slide/html/fallback）
     · renderCard       —— 组件围栏卡片 JSON 数组（通用结构化 HTML 卡片）
     · renderDynamicUi   —— quro-ui 节点树（布局/文本/按钮/表单/图表…）
     · renderSelfCard    —— 自研卡片 CardSpec（data + layout + actions）
     · renderMiniApp     —— iframe srcdoc 承载 AI 自写 HTML 应用
   ═══════════════════════════════════════════════════════════════════════ */
(function(){
  "use strict";

  /* ── 基础工具 ─────────────────────────────────────────────────── */
  function esc(s){
    return String(s === undefined || s === null ? "" : s)
      .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
      .replace(/"/g,"&quot;").replace(/'/g,"&#39;");
  }
  function el(tag,cls,text){
    var n=document.createElement(tag);
    if(cls)n.className=cls;
    if(text!==undefined&&text!==null)n.textContent=text;
    return n;
  }
  function div(cls,text){ return el("div",cls,text); }
  function jsonParse(s){
    try{ return JSON.parse(s); }catch(e){ return null; }
  }
  function inlineMarkdown(s){
    var t=esc(s);
    t=t.replace(/`([^`\n]+)`/g,'<code>$1</code>');
    t=t.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,'<a href="$2" target="_blank" rel="noopener">$1</a>');
    t=t.replace(/\*\*([^*\n]+)\*\*/g,"<b>$1</b>");
    t=t.replace(/(^|[^*])\*([^*\n]+)\*/g,"$1<i>$2</i>");
    t=t.replace(/~~([^~\n]+)~~/g,"<s>$1</s>");
    return t;
  }
  function colorOf(c,fallback){
    if(!c)return fallback||"";
    var s=String(c);
    if(/^#[0-9a-fA-F]{3,8}$/.test(s))return s;
    var map={
      primary:"var(--primary)",onPrimary:"#fff",
      secondary:"var(--secondary)",onSecondary:"#fff",
      surface:"var(--surface)",onSurface:"var(--fg)",
      surfaceVariant:"var(--bg2)",onSurfaceVariant:"var(--fg-soft)",
      background:"var(--bg)",onBackground:"var(--fg)",
      outline:"var(--line)",success:"#1A7A38",warning:"#B26A00",
      danger:"var(--err)",info:"var(--primary)",
      Primary:"var(--primary)",OnPrimary:"#fff",
      Secondary:"var(--secondary)",OnSecondary:"#fff",
      Surface:"var(--surface)",OnSurface:"var(--fg)",
      SurfaceVariant:"var(--bg2)",OnSurfaceVariant:"var(--fg-soft)",
      Background:"var(--bg)",OnBackground:"var(--fg)",
      Outline:"var(--line)",Success:"#1A7A38",Warning:"#B26A00",
      Danger:"var(--err)",Info:"var(--primary)"
    };
    return map[s.toLowerCase()]||fallback||"";
  }

  /* 主题色常量（与原生 AipCanvas CHART_COLORS 一致） */
  var CHART_COLORS=["#2E6BE6","#E65100","#00897B","#8E24AA","#558B2F","#D81B60"];

  /* ── Mermaid ────────────────────────────────────────────────────── */
  var mermaidPromise=null;
  function loadMermaid(){
    if(window.mermaid)return Promise.resolve(window.mermaid);
    if(mermaidPromise)return mermaidPromise;
    mermaidPromise=new Promise(function(resolve,reject){
      var s=document.createElement("script");
      s.src="runtimes/mermaid.min.js";
      s.onload=function(){ resolve(window.mermaid); };
      s.onerror=function(){ reject(new Error("mermaid.min.js 加载失败")); };
      document.head.appendChild(s);
    });
    return mermaidPromise;
  }

  /**
   * Mermaid 真渲染：加载 mermaid.min.js 离线渲染，错误时显示诊断。
   * 与原生 `mermaid_render.html` 同源（同样 securityLevel=loose、useMaxWidth）。
   */
  function renderMermaid(container, data, dark){
    var src=(data&&data.source)||"";
    if(!src||!src.trim()){
      container.appendChild(div("mmd-empty","（空图表）"));
      return;
    }
    container.appendChild(div("mmd-loading","渲染中…"));
    loadMermaid().then(function(mm){
      container.innerHTML="";
      var cfg={
        startOnLoad:false,
        theme:(dark?"dark":"default"),
        securityLevel:"loose",
        logLevel:"error",
        flowchart:{htmlLabels:true,curve:"basis",useMaxWidth:true},
        sequence:{useMaxWidth:true,diagramMarginX:8,diagramMarginY:8},
        mindmap:{useMaxWidth:true},
        gantt:{useMaxWidth:true},
        class:{useMaxWidth:true},
        state:{useMaxWidth:true},
        pie:{useMaxWidth:true}
      };
      try{
        mm.initialize(cfg);
        var id="mmd_"+Date.now()+"_"+Math.floor(Math.random()*10000);
        mm.render(id,src).then(function(res){
          var svg=(res&&res.svg)?res.svg:(""+res);
          if(svg&&svg.indexOf("<svg")>=0){
            container.innerHTML=svg;
          }else{
            container.appendChild(div("mmd-err","渲染结果为空（SVG 未生成）"));
          }
        }).catch(function(e){
          container.appendChild(div("mmd-err","流程图渲染失败：\n"+((e&&e.message)||e)));
        });
      }catch(e){
        container.appendChild(div("mmd-err","流程图渲染失败：\n"+((e&&e.message)||e)));
      }
    }).catch(function(e){
      container.innerHTML="";
      container.appendChild(div("mmd-err",String(e&&e.message||e)));
    });
  }

  /* ── AIP 排版 ─────────────────────────────────────────────────────── */
  function aipTitle(d){
    var box=div("aip-head");
    if(d.title)box.appendChild(el("div","aip-title",d.title));
    if(d.subtitle)box.appendChild(div("aip-sub",d.subtitle));
    if(d.author)box.appendChild(div("aip-author",d.author));
    return box;
  }
  function aipParagraph(text){
    var p=el("p","aip-p"); p.innerHTML=inlineMarkdown(text||""); return p;
  }
  function aipListBlock(b){
    var ul=el(b.ordered?"ol":"ul","aip-list");
    (b.items||[]).forEach(function(it){
      var li=el("li"); li.innerHTML=inlineMarkdown(it); ul.appendChild(li);
    });
    return ul;
  }
  function aipTableBlock(b){
    var wrap=div("aip-table-wrap");
    var t=el("table","aip-table");
    var thead=el("thead"), tr=el("tr");
    (b.headers||[]).forEach(function(h){ var th=el("th"); th.innerHTML=inlineMarkdown(h); tr.appendChild(th); });
    thead.appendChild(tr); t.appendChild(thead);
    var tb=el("tbody");
    (b.rows||[]).forEach(function(r){
      var rtr=el("tr");
      (r||[]).forEach(function(c){ var td=el("td"); td.innerHTML=inlineMarkdown(c); rtr.appendChild(td); });
      tb.appendChild(rtr);
    });
    t.appendChild(tb); wrap.appendChild(t); return wrap;
  }
  function aipCodeBlock(b){
    var box=div("aip-code");
    if(b.lang)box.appendChild(div("aip-code-lang",b.lang));
    box.appendChild(el("pre",null,b.code||""));
    return box;
  }
  function aipQuoteBlock(b){
    var q=div("aip-quote");
    var p=el("p"); p.innerHTML=inlineMarkdown(b.text||""); q.appendChild(p);
    if(b.cite)q.appendChild(div("aip-quote-cite","—— "+b.cite));
    return q;
  }
  function aipCallout(b){
    var tone=String(b.tone||"info").toLowerCase();
    var box=div("aip-callout "+tone);
    if(b.title)box.appendChild(div("aip-callout-title",b.title));
    var p=el("div"); p.innerHTML=inlineMarkdown(b.text||""); box.appendChild(p);
    return box;
  }
  function aipImage(b){
    var box=div("aip-image");
    box.appendChild(div("aip-image-ref","🖼  "+b.ref));
    if(b.caption)box.appendChild(div("aip-image-cap",b.caption));
    return box;
  }
  function aipChartBlock(b){
    var box=div("aip-chart");
    if(b.title)box.appendChild(div("aip-chart-title",b.title));
    var canvas=el("canvas","aip-chart-canvas");
    box.appendChild(canvas);
    // 简单 Canvas 图表：bar / line / pie / radar
    drawAipChart(canvas,b);
    if((b.series||[]).length>1){
      var legend=div("aip-chart-legend");
      b.series.forEach(function(s,i){
        var item=div("aip-legend-item");
        var sw=el("span","aip-legend-swatch"); sw.style.background=CHART_COLORS[i%CHART_COLORS.length];
        item.appendChild(sw);
        item.appendChild(document.createTextNode(s.name||("系列 "+(i+1))));
        legend.appendChild(item);
      });
      box.appendChild(legend);
    }
    return box;
  }
  function drawAipChart(canvas,b){
    var dpr=window.devicePixelRatio||1;
    var w=canvas.clientWidth||canvas.parentNode.clientWidth||300;
    var h=180;
    canvas.width=w*dpr; canvas.height=h*dpr; canvas.style.width=w+"px"; canvas.style.height=h+"px";
    var ctx=canvas.getContext("2d"); ctx.scale(dpr,dpr);
    ctx.clearRect(0,0,w,h);
    var type=String(b.chartType||"bar").toLowerCase();
    var labels=b.labels||[];
    var series=b.series||[];
    var padL=30,padB=20,chartW=w-padL-4,chartH=h-padB-8;
    if(type==="pie"||type==="donut"){
      var s0=series[0];
      if(!s0||!s0.values||!s0.values.length)return;
      var total=s0.values.reduce(function(a,x){return a+Math.max(0,x);},0)||1;
      var cx=w/2,cy=h/2-10,r=Math.min(w,h)*0.32;
      var start=-Math.PI/2;
      s0.values.forEach(function(v,i){
        var sweep=(Math.max(0,v)/total)*Math.PI*2;
        ctx.beginPath();
        ctx.moveTo(cx,cy);
        ctx.arc(cx,cy,r,start,start+sweep);
        ctx.closePath();
        ctx.fillStyle=CHART_COLORS[i%CHART_COLORS.length];
        ctx.fill();
        start+=sweep;
      });
      return;
    }
    if(type==="radar"){
      var s=series[0];
      if(!s||!labels.length)return;
      var n=labels.length, cx=w/2, cy=h/2-10, r=Math.min(w,h)*0.32;
      var maxV=Math.max.apply(null,s.values.concat([1]));
      for(var f=0.33;f<=1.01;f+=0.33){
        ctx.beginPath();
        for(var i=0;i<n;i++){
          var ang=-Math.PI/2+(2*Math.PI*i/n);
          var x=cx+r*f*Math.cos(ang), y=cy+r*f*Math.sin(ang);
          if(i===0)ctx.moveTo(x,y); else ctx.lineTo(x,y);
        }
        ctx.closePath();
        ctx.strokeStyle="rgba(128,128,128,.3)"; ctx.lineWidth=1.5; ctx.stroke();
      }
      ctx.beginPath();
      for(var j=0;j<n;j++){
        var a2=-Math.PI/2+(2*Math.PI*j/n);
        var v=(s.values[j]||0)/maxV;
        var px=cx+r*v*Math.cos(a2), py=cy+r*v*Math.sin(a2);
        if(j===0)ctx.moveTo(px,py); else ctx.lineTo(px,py);
      }
      ctx.closePath();
      ctx.fillStyle="rgba(46,107,230,.25)"; ctx.fill();
      ctx.strokeStyle="#2E6BE6"; ctx.lineWidth=3; ctx.stroke();
      return;
    }
    // bar / line / area
    var n2=Math.max(labels.length,1);
    var maxV2=1;
    series.forEach(function(sr){ (sr.values||[]).forEach(function(v){ if(v>maxV2)maxV2=v; }); });
    maxV2=Math.max(maxV2,0.001);
    if(type==="line"||type==="area"){
      var nn=Math.max(labels.length,2);
      series.forEach(function(sr,si){
        var color=CHART_COLORS[si%CHART_COLORS.length];
        ctx.beginPath();
        (sr.values||[]).forEach(function(v,i){
          var x=padL+i*(chartW)/(nn-1);
          var y=chartH-(v/maxV2)*chartH*0.9;
          if(i===0)ctx.moveTo(x,y); else ctx.lineTo(x,y);
        });
        if(type==="area"){
          ctx.lineTo(padL+(Math.max((sr.values||[]).length-1,0))*(chartW)/(nn-1),chartH);
          ctx.lineTo(padL,chartH);
          ctx.closePath();
          ctx.fillStyle=color+"44"; ctx.fill();
        }
        ctx.strokeStyle=color; ctx.lineWidth=3; ctx.lineCap="round"; ctx.stroke();
        (sr.values||[]).forEach(function(v,i){
          var x=padL+i*(chartW)/(nn-1);
          var y=chartH-(v/maxV2)*chartH*0.9;
          ctx.beginPath(); ctx.arc(x,y,3,0,Math.PI*2); ctx.fillStyle=color; ctx.fill();
        });
      });
    }else{
      var groupW=chartW/n2;
      series.forEach(function(sr,si){
        var barW=(groupW*0.6)/Math.max(series.length,1);
        (sr.values||[]).forEach(function(v,i){
          var hh=(v/maxV2)*chartH*0.9;
          var x=padL+i*groupW+groupW*0.2+si*barW;
          ctx.fillStyle=CHART_COLORS[si%CHART_COLORS.length];
          roundRect(ctx,x,chartH-hh,barW-3,hh,3);
          ctx.fill();
        });
      });
    }
    ctx.strokeStyle="rgba(128,128,128,.4)"; ctx.lineWidth=1;
    ctx.beginPath(); ctx.moveTo(padL,chartH); ctx.lineTo(w-2,chartH); ctx.stroke();
    // X 轴标签
    ctx.fillStyle="rgba(128,128,128,.8)"; ctx.font="10px system-ui";
    labels.forEach(function(lb,i){
      var x=padL+i*chartW/Math.max(labels.length,1)+chartW/Math.max(labels.length,1)/2;
      ctx.textAlign="center";
      ctx.fillText(String(lb).slice(0,8),x,chartH+14);
    });
  }
  function roundRect(ctx,x,y,w,h,r){
    ctx.beginPath();
    ctx.moveTo(x+r,y);
    ctx.arcTo(x+r,y+h,x,y+h,r);
    ctx.arcTo(x,y+h,x,y,r);
    ctx.arcTo(x,y,x+r,y,r);
    ctx.arcTo(x+r,y,x+r,y+h,r);
    ctx.closePath();
  }
  function aipColumnsBlock(b){
    var row=div("aip-columns");
    var ratio=b.ratio||[];
    (b.children||[]).forEach(function(colArr,colIdx){
      var col=div("aip-col");
      var flex=ratio[colIdx]||1;
      col.style.flex=String(flex)+" 1 0";
      (colArr||[]).forEach(function(blockStr){
        var blk=typeof blockStr==="string"?jsonParse(blockStr):blockStr;
        if(blk)col.appendChild(aipBlockNode(blk));
      });
      row.appendChild(col);
    });
    return row;
  }
  function aipStepsBlock(b){
    var col=div("aip-steps");
    (b.items||[]).forEach(function(item,i){
      var row=div("aip-step");
      var num=el("span","aip-step-num",String(i+1));
      row.appendChild(num);
      var body=div("aip-step-body"); body.innerHTML=inlineMarkdown(item||""); row.appendChild(body);
      col.appendChild(row);
    });
    return col;
  }
  function aipTimelineBlock(b){
    var col=div("aip-timeline");
    (b.items||[]).forEach(function(item){
      var row=div("aip-tl-item");
      var dot=el("span","aip-tl-dot");
      row.appendChild(dot);
      var body=div("aip-tl-body");
      var head=div("aip-tl-head");
      if(item.time)head.appendChild(div("aip-tl-time",item.time));
      if(item.title)head.appendChild(div("aip-tl-title",item.title));
      body.appendChild(head);
      if(item.text){ var p=el("p"); p.innerHTML=inlineMarkdown(item.text); body.appendChild(p); }
      row.appendChild(body);
      col.appendChild(row);
    });
    return col;
  }
  function aipMindmapNode(node,isRoot){
    var col=div("aip-mm-node"+(isRoot?" root":""));
    var chip=div("aip-mm-chip",node.text||" ");
    col.appendChild(chip);
    if(node.children&&node.children.length){
      var kids=div("aip-mm-children");
      node.children.forEach(function(c){ kids.appendChild(aipMindmapNode(c,false)); });
      col.appendChild(kids);
    }
    return col;
  }
  function aipMindmapBlock(b){
    var box=div("aip-mindmap");
    box.appendChild(aipMindmapNode(b.root,true));
    return box;
  }
  function aipSlideBlock(b){
    var layout=String(b.layout||"titleBody");
    var slide=div("aip-slide");
    var inner=div("aip-slide-inner");
    function bullets(){
      (b.bullets||[]).forEach(function(bl){
        var p=el("p","aip-slide-bullet"); p.innerHTML=inlineMarkdown(bl); inner.appendChild(p);
      });
    }
    if(layout==="cover"){
      if(b.title)inner.appendChild(div("aip-slide-cover-title",b.title));
      if(b.subtitle)inner.appendChild(div("aip-slide-cover-sub",b.subtitle));
    }else if(layout==="section"){
      if(b.title)inner.appendChild(div("aip-slide-section-title",b.title));
      if(b.subtitle)inner.appendChild(div("aip-slide-section-sub",b.subtitle));
    }else if(layout==="stats"){
      if(b.title)inner.appendChild(div("aip-slide-stats-title",b.title));
      var statsRow=div("aip-slide-stats");
      (b.stats||[]).forEach(function(st){
        var item=div("aip-slide-stat");
        item.appendChild(div("aip-slide-stat-v",st.value));
        item.appendChild(div("aip-slide-stat-l",st.label));
        statsRow.appendChild(item);
      });
      inner.appendChild(statsRow);
    }else if(layout==="quote"){
      var q=div("aip-slide-quote","“"+(b.quote||b.title)+"”");
      inner.appendChild(q);
      if(b.quoteAuthor)inner.appendChild(div("aip-slide-quote-author","—— "+b.quoteAuthor));
    }else if(layout==="twoCol"){
      if(b.title)inner.appendChild(div("aip-slide-two-title",b.title));
      var cols=div("aip-slide-two");
      (b.columns||[]).forEach(function(c){
        var item=div("aip-slide-two-col");
        if(c.title)item.appendChild(div("aip-slide-two-head",c.title));
        item.appendChild(div("aip-slide-two-body",c.text));
        cols.appendChild(item);
      });
      inner.appendChild(cols);
    }else if(layout==="chart"){
      if(b.title)inner.appendChild(div("aip-slide-chart-title",b.title));
      if(b.chart)inner.appendChild(aipChartBlock(b.chart));
    }else if(layout==="table"){
      if(b.title)inner.appendChild(div("aip-slide-table-title",b.title));
      if(b.table)inner.appendChild(aipTableBlock(b.table));
    }else if(layout==="summary"){
      if(b.title)inner.appendChild(div("aip-slide-summary-title",b.title));
      (b.bullets||[]).forEach(function(bl){
        var p=el("p","aip-slide-summary"); p.innerHTML="✓ "+inlineMarkdown(bl); inner.appendChild(p);
      });
    }else{
      if(b.title)inner.appendChild(div("aip-slide-body-title",b.title));
      if(b.subtitle)inner.appendChild(div("aip-slide-body-sub",b.subtitle));
      bullets();
      if((!b.bullets||!b.bullets.length)&&(b.columns||[]).length){
        var bcols=div("aip-slide-body-cols");
        b.columns.forEach(function(c){
          var item=div("aip-slide-body-col");
          if(c.title)item.appendChild(div("aip-slide-body-col-head",c.title));
          item.appendChild(div("aip-slide-body-col-text",c.text));
          bcols.appendChild(item);
        });
        inner.appendChild(bcols);
      }
    }
    slide.appendChild(inner);
    return slide;
  }
  function aipSectionBlock(b){
    var box=div("aip-section");
    var bar=el("span","aip-section-bar");
    box.appendChild(bar);
    box.appendChild(div("aip-section-title",b.title));
    return box;
  }
  function aipHtmlBlock(b){
    var box=div("aip-html");
    var frame=el("iframe","aip-html-frame");
    frame.setAttribute("srcdoc",b.html||"");
    frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups");
    frame.style.height="360px";
    box.appendChild(frame);
    return box;
  }
  function aipFallback(b){
    return div("aip-fallback",b.text||"");
  }
  function aipBlockNode(b){
    if(!b||!b.type)return null;
    switch(b.type){
      case "heading":{
        var lv=Math.min(Math.max(b.level||1,1),4);
        var h=el("h"+(lv+1),"aip-heading");
        h.innerHTML=inlineMarkdown(b.text||"");
        return h;
      }
      case "paragraph": return aipParagraph(b.text);
      case "list": return aipListBlock(b);
      case "table": return aipTableBlock(b);
      case "code": return aipCodeBlock(b);
      case "quote": return aipQuoteBlock(b);
      case "callout": return aipCallout(b);
      case "divider": return el("hr","aip-divider");
      case "image": return aipImage(b);
      case "chart": return aipChartBlock(b);
      case "columns": return aipColumnsBlock(b);
      case "steps": return aipStepsBlock(b);
      case "timeline": return aipTimelineBlock(b);
      case "mindmap": return aipMindmapBlock(b);
      case "slide": return aipSlideBlock(b);
      case "section": return aipSectionBlock(b);
      case "html": return aipHtmlBlock(b);
      default: return aipFallback(b);
    }
  }
  function renderAip(container,data){
    if(!data||!data.ok){
      container.appendChild(div("aip-degraded","AIP 排版降级"));
      if(data&&data.raw){
        var pre=el("pre","aip-degraded-raw",data.raw);
        container.appendChild(pre);
      }
      return;
    }
    container.appendChild(aipTitle(data));
    (data.blocks||[]).forEach(function(b){
      var n=aipBlockNode(b);
      if(n)container.appendChild(n);
    });
  }


/* ═══════════════════════════════════════════════════════════════════
     第二部分：卡片 / 动态 UI / 自研卡片 / MiniApp 真渲染器
     数据来自 WebUiProjection（同一批解析器），这里只画、不猜。
     ═══════════════════════════════════════════════════════════════════ */

  /* ── 交互写回 ─────────────────────────────────────────────────── */
  var CMD_URL = "/api/card_action";
  function postCard(payload){
    try{
      fetch(CMD_URL,{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify(payload||{})
      }).catch(function(){});
    }catch(e){}
  }

  /* 轻量 canvas 绘图工具（bar/line/pie/ring/radar 共用） */
  function canvasBox(height){
    var box=div("qchart");
    var cv=el("canvas","qchart-canvas");
    cv.style.height=(height||160)+"px";
    box.appendChild(cv);
    return {box:box,cv:cv};
  }
  function setupCanvas(cv){
    var dpr=window.devicePixelRatio||1;
    var w=cv.clientWidth||cv.parentNode.clientWidth||260;
    var h=parseInt(cv.style.height,10)||160;
    cv.width=w*dpr; cv.height=h*dpr; cv.style.width=w+"px"; cv.style.height=h+"px";
    var ctx=cv.getContext("2d"); ctx.scale(dpr,dpr);
    ctx.clearRect(0,0,w,h);
    return {ctx:ctx,w:w,h:h};
  }
  function drawBars(cv,labels,series){
    var s=setupCanvas(cv),ctx=s.ctx,w=s.w,h=s.h;
    var padL=28,padB=18,chW=w-padL-4,chH=h-padB-8;
    var maxV=1;
    series.forEach(function(sr){(sr.values||[]).forEach(function(v){if(v>maxV)maxV=v;});});
    maxV=Math.max(maxV,0.001);
    var n=Math.max(labels.length,1), groupW=chW/n;
    series.forEach(function(sr,si){
      var barW=(groupW*0.6)/Math.max(series.length,1);
      (sr.values||[]).forEach(function(v,i){
        var hh=(v/maxV)*chH*0.9;
        var x=padL+i*groupW+groupW*0.2+si*barW;
        ctx.fillStyle=CHART_COLORS[si%CHART_COLORS.length];
        roundRect(ctx,x,chH-hh,barW-3,hh,3); ctx.fill();
      });
    });
    ctx.strokeStyle="rgba(128,128,128,.4)";ctx.lineWidth=1;
    ctx.beginPath();ctx.moveTo(padL,chH);ctx.lineTo(w-2,chH);ctx.stroke();
    ctx.fillStyle="rgba(128,128,128,.8)";ctx.font="10px system-ui";
    labels.forEach(function(lb,i){
      var x=padL+i*groupW+groupW/2;
      ctx.textAlign="center";
      ctx.fillText(String(lb).slice(0,6),x,chH+13);
    });
  }
  function drawLine(cv,labels,series,area){
    var s=setupCanvas(cv),ctx=s.ctx,w=s.w,h=s.h;
    var padL=28,padB=18,chW=w-padL-4,chH=h-padB-8;
    var maxV=1;
    series.forEach(function(sr){(sr.values||[]).forEach(function(v){if(v>maxV)maxV=v;});});
    maxV=Math.max(maxV,0.001);
    var nn=Math.max(labels.length,2);
    series.forEach(function(sr,si){
      var color=CHART_COLORS[si%CHART_COLORS.length];
      ctx.beginPath();
      (sr.values||[]).forEach(function(v,i){
        var x=padL+i*(chW)/(nn-1);
        var y=chH-(v/maxV)*chH*0.9;
        if(i===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);
      });
      if(area){
        ctx.lineTo(padL+(Math.max((sr.values||[]).length-1,0))*(chW)/(nn-1),chH);
        ctx.lineTo(padL,chH);ctx.closePath();
        ctx.fillStyle=color+"44";ctx.fill();
      }
      ctx.strokeStyle=color;ctx.lineWidth=3;ctx.lineCap="round";ctx.stroke();
      (sr.values||[]).forEach(function(v,i){
        var x=padL+i*(chW)/(nn-1);
        var y=chH-(v/maxV)*chH*0.9;
        ctx.beginPath();ctx.arc(x,y,3,0,Math.PI*2);ctx.fillStyle=color;ctx.fill();
      });
    });
    ctx.strokeStyle="rgba(128,128,128,.4)";ctx.lineWidth=1;
    ctx.beginPath();ctx.moveTo(padL,chH);ctx.lineTo(w-2,chH);ctx.stroke();
  }
  function drawPie(cv,segments,donut){
    var s=setupCanvas(cv),ctx=s.ctx,w=s.w,h=s.h;
    var total=segments.reduce(function(a,x){return a+Math.max(0,x.value||0);},0)||1;
    var cx=w/2,cy=h/2,r=Math.min(w,h)*0.34;
    var start=-Math.PI/2;
    segments.forEach(function(seg,i){
      var sweep=(Math.max(0,seg.value||0)/total)*Math.PI*2;
      ctx.beginPath();
      ctx.moveTo(cx,cy);
      ctx.arc(cx,cy,r,start,start+sweep);
      ctx.closePath();
      ctx.fillStyle=seg.color||CHART_COLORS[i%CHART_COLORS.length];
      ctx.fill();
      if(donut){
        ctx.fillStyle=(document.documentElement.classList.contains("dark")?"#1C1813":"#FFFFFF");
        ctx.beginPath(); ctx.arc(cx,cy,r*0.6,0,Math.PI*2); ctx.fill();
      }
      start+=sweep;
    });
  }
  function drawRadar(cv,axes){
    var s=setupCanvas(cv),ctx=s.ctx,w=s.w,h=s.h;
    var n=axes.length; if(!n)return;
    var cx=w/2,cy=h/2,r=Math.min(w,h)*0.34;
    var maxV=1;
    axes.forEach(function(a){if((a.value||0)>maxV)maxV=a.value;});
    for(var f=0.33;f<=1.01;f+=0.33){
      ctx.beginPath();
      for(var i=0;i<n;i++){
        var ang=-Math.PI/2+(2*Math.PI*i/n);
        var x=cx+r*f*Math.cos(ang), y=cy+r*f*Math.sin(ang);
        if(i===0)ctx.moveTo(x,y); else ctx.lineTo(x,y);
      }
      ctx.closePath();
      ctx.strokeStyle="rgba(128,128,128,.3)";ctx.lineWidth=1.5;ctx.stroke();
    }
    ctx.beginPath();
    for(var j=0;j<n;j++){
      var a2=-Math.PI/2+(2*Math.PI*j/n);
      var v=(axes[j].value||0)/maxV;
      var px=cx+r*v*Math.cos(a2), py=cy+r*v*Math.sin(a2);
      if(j===0)ctx.moveTo(px,py); else ctx.lineTo(px,py);
    }
    ctx.closePath();
    ctx.fillStyle="rgba(46,107,230,.25)";ctx.fill();
    ctx.strokeStyle="#2E6BE6";ctx.lineWidth=3;ctx.stroke();
    ctx.fillStyle="rgba(128,128,128,.8)";ctx.font="10px system-ui";
    axes.forEach(function(a,i){
      var ang=-Math.PI/2+(2*Math.PI*i/n);
      ctx.textAlign="center";
      ctx.fillText(String(a.name||"").slice(0,6),cx+(r+14)*Math.cos(ang),cy+(r+14)*Math.sin(ang)+3);
    });
  }
  function renderChartData(cv,card){
    var type=String(card.chartType||card.type||"bar").toLowerCase();
    if(type==="pie"||type==="donut"){
      drawPie(cv,card.segments||[],type==="donut");
      return;
    }
    if(type==="radar"){
      drawRadar(cv,card.axes||[]);
      return;
    }
    var labels=(card.series||[]).map(function(p){return p.label||p.name||"";});
    var series=[{name:"",values:(card.series||[]).map(function(p){return p.value||0;})}];
    if(type==="line"||type==="area")drawLine(cv,labels,series,type==="area");
    else drawBars(cv,labels,series);
  }

  /* SVG 迷你图 / 环形图 */
  function sparkline(cv,values,color){
    if(!values||!values.length)return;
    var ns="http://www.w3.org/2000/svg";
    var svg=document.createElementNS(ns,"svg");
    svg.setAttribute("class","qsvg");
    svg.setAttribute("viewBox","0 0 "+values.length+" 40");
    svg.setAttribute("preserveAspectRatio","none");
    var maxV=Math.max.apply(null,values.concat([1]));
    var pts=values.map(function(v,i){
      return (i)+","+(40-(v/maxV)*38);
    }).join(" ");
    var poly=document.createElementNS(ns,"polyline");
    poly.setAttribute("points",pts);
    poly.setAttribute("fill","none");
    poly.setAttribute("stroke",color||"#2E6BE6");
    poly.setAttribute("stroke-width","2");
    svg.appendChild(poly);
    cv.appendChild(svg);
  }
  function ringChart(cv,value,max,color,label){
    var pct=Math.max(0,Math.min(100,(value/(max||1))*100));
    var ns="http://www.w3.org/2000/svg";
    var svg=document.createElementNS(ns,"svg");
    svg.setAttribute("class","qring");
    svg.setAttribute("viewBox","0 0 80 80");
    var bg=document.createElementNS(ns,"circle");
    bg.setAttribute("cx","40");bg.setAttribute("cy","40");bg.setAttribute("r","32");
    bg.setAttribute("fill","none");bg.setAttribute("stroke","rgba(128,128,128,.2)");
    bg.setAttribute("stroke-width","8");
    svg.appendChild(bg);
    var arc=document.createElementNS(ns,"circle");
    arc.setAttribute("cx","40");arc.setAttribute("cy","40");arc.setAttribute("r","32");
    arc.setAttribute("fill","none");arc.setAttribute("stroke",color||"#2E6BE6");
    arc.setAttribute("stroke-width","8");
    arc.setAttribute("stroke-linecap","round");
    arc.setAttribute("stroke-dasharray",(2*Math.PI*32));
    arc.setAttribute("stroke-dashoffset",(2*Math.PI*32)*(1-pct/100));
    arc.setAttribute("transform","rotate(-90 40 40)");
    svg.appendChild(arc);
    cv.appendChild(svg);
    if(label!==undefined&&label!==null){
      var t=div("qring-label",String(label));
      cv.appendChild(t);
    }
  }

  /* ── 卡片通用构件 ─────────────────────────────────────────────── */
  function cardShell(card, theme, compact){
    var wrap=div("qcard"+(compact?" compact":""));
    if(theme){
      var themes={accent:"accent",warn:"warn",danger:"danger",plain:"plain"};
      wrap.setAttribute("data-theme",themes[String(theme).toLowerCase()]||"accent");
    }
    if(card&&card.title){
      var h=div("qcard-title",card.title);
      wrap.appendChild(h);
    }
    var body=div("qcard-body");
    wrap.appendChild(body);
    return {wrap:wrap,body:body};
  }
  function cmdButton(label,command,variant){
    var b=el("button","qbtn"+(variant&&variant!=="filled"?" ghost":""),label);
    b.type="button";
    b.addEventListener("click",function(){
      if(command)postCard({cmd:command});
    });
    return b;
  }
  function cmdChip(label,command){
    var b=el("button","qchip",label);
    b.type="button";
    b.addEventListener("click",function(){
      if(command)postCard({cmd:command});
    });
    return b;
  }
  function pad2(n){ n=Math.max(0,Math.floor(n)); return ("0"+n).slice(-2); }

  /* ── 基础卡片渲染器（serializeCard 输出字段）──────────────────── */
  function renderTodoCard(card){
    var box=div("qcard-list");
    (card.items||[]).forEach(function(it){
      var row=div("qcheck-row");
      var cb=el("input","qcheck");
      cb.type="checkbox";
      cb.checked=!!it.done;
      cb.addEventListener("change",function(){
        if(card.command)postCard({cmd:card.command,value:it.text,checked:cb.checked});
      });
      row.appendChild(cb);
      var label=el("span",null,it.text||"");
      if(it.done)label.className="done";
      row.appendChild(label);
      box.appendChild(row);
    });
    return box;
  }
  function renderButtonCard(card){
    return cmdButton(card.label||"确定",card.command,card.variant);
  }
  function renderToggleCard(card){
    var row=div("qcheck-row");
    var cb=el("input","qcheck");
    cb.type="checkbox";
    cb.checked=!!card.checked;
    cb.addEventListener("change",function(){
      if(card.command)postCard({cmd:card.command,checked:cb.checked});
    });
    row.appendChild(cb);
    var label=el("span",null,card.label||"");
    row.appendChild(label);
    return row;
  }
  function renderSliderCard(card){
    var box=div("qslider");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var row=div("qslider-row");
    var range=el("input","qrange");
    range.type="range";
    range.min=card.min||0;range.max=card.max||100;range.step=card.step||1;
    range.value=card.value||0;
    var val=el("span","qslider-val",(card.value||0)+(card.unit||""));
    row.appendChild(range);row.appendChild(val);
    range.addEventListener("input",function(){
      val.textContent=range.value+(card.unit||"");
    });
    range.addEventListener("change",function(){
      if(card.command)postCard({cmd:card.command,value:range.value});
    });
    box.appendChild(row);
    return box;
  }
  function renderCounterCard(card){
    var box=div("qcounter");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var row=div("qcounter-row");
    var minus=el("button","qbtn ghost","−");
    var val=el("span","qcounter-val",String(card.value||0));
    var plus=el("button","qbtn ghost","+");
    minus.type="button";plus.type="button";
    var cur=card.value||0;
    function post(v){
      if(card.command)postCard({cmd:card.command,value:String(v)});
    }
    minus.addEventListener("click",function(){
      cur=Math.max(card.min||0,(cur-(card.step||1)));
      val.textContent=cur;post(cur);
    });
    plus.addEventListener("click",function(){
      cur=Math.min(card.max||100,(cur+(card.step||1)));
      val.textContent=cur;post(cur);
    });
    row.appendChild(minus);row.appendChild(val);row.appendChild(plus);
    box.appendChild(row);
    return box;
  }
  function renderProgressCard(card){
    var box=div("qprogress");
    if(card.label)box.appendChild(div("qprogress-label",card.label));
    var bar=div("qprogress-bar");
    var fill=div("qprogress-fill");
    var pct=Math.max(0,Math.min(100,(card.value/(card.max||1))*100));
    fill.style.width=pct+"%";
    bar.appendChild(fill);
    box.appendChild(bar);
    if(card.suffix)box.appendChild(div("qprogress-suffix",String(card.value||0)+card.suffix));
    return box;
  }
  function renderStatCard(card){
    var box=div("qstat");
    if(card.label)box.appendChild(div("qstat-label",card.label));
    var vrow=div("qstat-value-row");
    var v=div("qstat-value",String(card.value===undefined||card.value===null?"":card.value));
    vrow.appendChild(v);
    if(card.unit)vrow.appendChild(div("qstat-unit",card.unit));
    box.appendChild(vrow);
    if(card.delta){
      var trend=String(card.trend||"flat").toLowerCase();
      var d=div("qstat-delta "+(trend==="up"?"up":(trend==="down"?"down":"flat")),(trend==="up"?"▲ ":(trend==="down"?"▼ ":"• "))+card.delta);
      box.appendChild(d);
    }
    return box;
  }
  function renderAlertCard(card){
    var sev=String(card.severity||"info").toLowerCase();
    var box=div("qalert "+sev);
    if(card.title)box.appendChild(div("qalert-title",card.title));
    box.appendChild(div("qalert-text",card.text||""));
    return box;
  }
  function renderTableCard(card){
    var wrap=div("qtable-wrap");
    var t=el("table","qtable");
    if((card.headers||[]).length){
      var thead=el("thead"),tr=el("tr");
      (card.headers||[]).forEach(function(h){var th=el("th",null,h);tr.appendChild(th);});
      thead.appendChild(tr);t.appendChild(thead);
    }
    var tb=el("tbody");
    (card.rows||[]).forEach(function(r){
      var rtr=el("tr");
      (r||[]).forEach(function(c){var td=el("td",null,c);rtr.appendChild(td);});
      tb.appendChild(rtr);
    });
    t.appendChild(tb);wrap.appendChild(t);return wrap;
  }
  function renderListCard(card){
    var box=div("qcard-list");
    (card.items||[]).forEach(function(it){
      var row=el("button","qlist-item");
      row.type="button";
      var main=div("qlist-main",it.text||"");
      if(it.sub)main.appendChild(div("qlist-sub",it.sub));
      row.appendChild(main);
      if(card.selectable&&card.command){
        row.addEventListener("click",function(){
          postCard({cmd:card.command,value:it.text,selected:!it.selected});
        });
      }
      box.appendChild(row);
    });
    return box;
  }
  function renderSegmentedCard(card){
    var box=div("qseg");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var row=div("qseg-row");
    (card.options||[]).forEach(function(opt,i){
      var b=el("button","qseg-item"+(i===card.selectedIndex?" on":""));
      b.type="button";
      b.textContent=opt;
      b.addEventListener("click",function(){
        if(card.command)postCard({cmd:card.command,value:opt,index:i});
      });
      row.appendChild(b);
    });
    box.appendChild(row);
    return box;
  }
  function renderPieCard(card){
    var box=div("qpie");
    var c=canvasBox(150);box.appendChild(c.box);
    drawPie(c.cv,card.segments||[],false);
    var legend=div("qpie-legend");
    (card.segments||[]).forEach(function(sg,i){
      var item=div("qpie-legend-item");
      var sw=el("span","qlegend-swatch");
      sw.style.background=sg.color||CHART_COLORS[i%CHART_COLORS.length];
      item.appendChild(sw);
      item.appendChild(div("qlegend-name",sg.name||""));
      item.appendChild(div("qlegend-val",String(sg.value||0)));
      legend.appendChild(item);
    });
    box.appendChild(legend);
    return box;
  }
  function renderChartCard(card){
    var box=div("qchart-card");
    var c=canvasBox(160);box.appendChild(c.box);
    renderChartData(c.cv,card);
    return box;
  }
  function renderRatingCard(card){
    var box=div("qrating");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var stars=div("qstars");
    var max=card.max||5,val=card.value||0;
    for(var i=1;i<=max;i++){
      (function(idx){
        var s=el("span","qstar"+(i<=val?" on":""),i<=val?"★":"☆");
        s.addEventListener("click",function(){
          if(card.command)postCard({cmd:card.command,value:idx});
        });
        stars.appendChild(s);
      })(i);
    }
    box.appendChild(stars);
    return box;
  }
  function renderCountdownCard(card){
    var box=div("qcountdown");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var t=div("qcountdown-val");
    box.appendChild(t);
    function tick(){
      var remain=Math.max(0,(card.targetEpochMs||0)-Date.now());
      var sec=Math.floor(remain/1000);
      var d=Math.floor(sec/86400),h=Math.floor(sec%86400/3600),m=Math.floor(sec%3600/60),s=sec%60;
      t.textContent=(d?d+"天 ":"")+pad2(h)+":"+pad2(m)+":"+pad2(s);
    }
    tick();setInterval(tick,1000);
    return box;
  }
  function renderTabsCard(card){
    var box=div("qtabs");
    var heads=div("qtabs-head");
    var bodies=div("qtabs-body");
    var headArr=heads.children;
    var panes=bodies.children;
    (card.tabs||[]).forEach(function(tab,i){
      var hb=el("button","qtabs-tab"+(i===card.selectedIndex?" on":""));
      hb.type="button";hb.textContent=tab.title||"";
      hb.addEventListener("click",function(){
        for(var j=0;j<headArr.length;j++)headArr[j].classList.toggle("on",j===i);
        for(var j=0;j<panes.length;j++)panes[j].hidden=j!==i;
        if(card.command)postCard({cmd:card.command,index:i,title:tab.title});
      });
      heads.appendChild(hb);
      var bd=div("qtabs-pane");
      bd.hidden=i!==(card.selectedIndex||0);
      if(tab.body)bd.appendChild(aipParagraph(tab.body));
      if(tab.content)bd.appendChild(renderCardNode(tab.content));
      bodies.appendChild(bd);
    });
    if(!(card.tabs||[]).length){box.appendChild(div("qcard-empty","（空）"));return box;}
    box.appendChild(heads);box.appendChild(bodies);
    return box;
  }
  function renderExpandableCard(card){
    var box=div("qexp");
    var h=el("button","qexp-head");
    h.type="button";
    h.appendChild(div("qexp-title",card.body||""));
    var arrow=el("span","qexp-arrow","▸");
    h.appendChild(arrow);
    var body=div("qexp-body");
    if(card.content)body.appendChild(renderCardNode(card.content));
    h.addEventListener("click",function(){
      var open=box.classList.toggle("open");
      arrow.textContent=open?"▾":"▸";
    });
    box.appendChild(h);box.appendChild(body);
    return box;
  }
  function renderFormCard(card){
    var box=div("qform");
    var fields={};
    (card.fields||[]).forEach(function(f){
      var row=div("qform-field");
      if(f.label)row.appendChild(div("qform-label",f.label));
      var inp=el("input","qform-input");
      inp.type=f.secret?"password":"text";
      inp.placeholder=f.placeholder||"";
      inp.value=f.value||"";
      fields[f.key]=inp;
      row.appendChild(inp);
      box.appendChild(row);
    });
    if(card.submitCommand){
      var btn=cmdButton("提交",null,"filled");
      btn.addEventListener("click",function(){
        var values={};
        Object.keys(fields).forEach(function(k){values[k]=fields[k].value;});
        postCard({cmd:card.submitCommand,values:values});
      });
      box.appendChild(btn);
    }
    return box;
  }
  function renderChipsCard(card){
    var box=div("qchips");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var row=div("qchips-row");
    var selected=(card.selected||[]).slice();
    (card.chips||[]).forEach(function(chip){
      var c=el("button","qchip"+(selected.indexOf(chip)>=0?" on":""));
      c.type="button";c.textContent=chip;
      c.addEventListener("click",function(){
        var idx=selected.indexOf(chip);
        if(card.multi){
          if(idx>=0)selected.splice(idx,1);else selected.push(chip);
        }else{
          selected=(idx>=0?[]:[chip]);
          var all=row.querySelectorAll(".qchip");
          for(var i=0;i<all.length;i++)all[i].classList.toggle("on",selected.indexOf(all[i].textContent)>=0);
        }
        if(card.command)postCard({cmd:card.command,value:chip,selected:selected});
      });
      row.appendChild(c);
    });
    box.appendChild(row);
    return box;
  }
  function renderStepsCard(card){
    var box=div("qsteps");
    var cur=card.current||0;
    (card.steps||[]).forEach(function(st,i){
      var item=div("qstep"+(i<cur?" done":(i===cur?" cur":"")));
      var dot=div("qstep-dot",i<cur?"✓":String(i+1));
      item.appendChild(dot);
      item.appendChild(div("qstep-title",st.title||""));
      if(st.status)item.appendChild(div("qstep-status",st.status));
      box.appendChild(item);
    });
    return box;
  }
  function renderGaugeCard(card){
    var box=div("qgauge");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var c=div("qgauge-ring");
    ringChart(c,card.value,card.max,CHART_COLORS[0],card.value+(card.unit||""));
    box.appendChild(c);
    return box;
  }
  function renderMediaCard(card){
    var box=div("qmedia");
    var type=String(card.mediaType||"").toLowerCase();
    var url=card.mediaUrl||card.uri||"";
    if(!url){box.appendChild(div("qmedia-empty","（无媒体）"));return box;}
    if(type.indexOf("image")>=0){
      var img=el("img","qmedia-img");
      img.src=url;
      img.onerror=function(){img.replaceWith(div("qmedia-empty","图片加载失败"));};
      box.appendChild(img);
    }else if(type.indexOf("audio")>=0){
      var au=el("audio","qmedia-audio");
      au.controls=true;au.src=url;box.appendChild(au);
    }else if(type.indexOf("video")>=0){
      var vi=el("video","qmedia-video");
      vi.controls=true;vi.src=url;box.appendChild(vi);
    }else{
      var a=el("a","qmedia-link",card.label||url);
      a.href=url; a.target="_blank"; a.rel="noopener";
      box.appendChild(a);
    }
    return box;
  }
  function renderInfoCard(card){
    var box=div("qinfo");
    var align=String(card.align||"start").toLowerCase();
    box.style.textAlign=align==="center"?"center":(align==="end"?"right":"left");
    box.appendChild(aipParagraph(card.body||""));
    return box;
  }
  function renderToolCallCard(card){
    var box=div("qtoolcall");
    var head=div("qtoolcall-head");
    head.appendChild(div("qtoolcall-name",card.tool||"tool"));
    var status=String(card.status||"");
    head.appendChild(div("qtoolcall-status "+(status==="done"||status==="success"?"ok":(status==="error"||status==="failed"?"err":"")),status||""));
    box.appendChild(head);
    if(card.progress!==undefined&&card.progress!==null){
      var bar=div("qprogress-bar");
      var fill=div("qprogress-fill");
      fill.style.width=Math.max(0,Math.min(100,card.progress))+"%";
      bar.appendChild(fill);
      box.appendChild(bar);
    }
    if(card.message)box.appendChild(div("qtoolcall-msg",card.message));
    return box;
  }
  function renderStreamCard(card){
    var box=div("qstream");
    (card.lines||[]).forEach(function(l){
      box.appendChild(el("div","qstream-line",l));
    });
    return box;
  }
  function renderQuickReplyCard(card){
    var box=div("qquick");
    (card.replies||[]).forEach(function(r){
      var c=cmdChip(r,null);
      c.addEventListener("click",function(){postCard({cmd:"reply:"+r});});
      box.appendChild(c);
    });
    return box;
  }
  function renderQuickActionCard(card){
    var box=div("qquick");
    (card.actions||[]).forEach(function(a){
      var c=cmdChip(a.label||"",a.command);
      if(a.icon)c.setAttribute("data-icon",a.icon);
      box.appendChild(c);
    });
    return box;
  }
  function renderTimelineCard(card){
    var box=div("qtimeline");
    (card.events||[]).forEach(function(ev){
      var item=div("qtl-item");
      var dot=el("span","qtl-dot");
      item.appendChild(dot);
      var body=div("qtl-body");
      var head=div("qtl-head");
      if(ev.time)head.appendChild(div("qtl-time",ev.time));
      if(ev.title)head.appendChild(div("qtl-title",ev.title));
      body.appendChild(head);
      if(ev.desc)body.appendChild(div("qtl-desc",ev.desc));
      if(ev.status)body.appendChild(div("qtl-status",ev.status));
      item.appendChild(body);
      box.appendChild(item);
    });
    return box;
  }
  function renderHeatmapCard(card){
    var box=div("qheat");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var grid=div("qheat-grid");
    var values=card.values||[];
    var maxV=Math.max.apply(null,values.concat([1]));
    values.forEach(function(v){
      var cell=div("qheat-cell");
      var a=Math.max(0.08,(v/maxV)*1);
      cell.style.background="rgba(46,107,230,"+a+")";
      cell.title=String(v);
      grid.appendChild(cell);
    });
    box.appendChild(grid);
    return box;
  }
  function renderCompareCard(card){
    var box=div("qcompare");
    [["left","left"],["right","right"]].forEach(function(conf){
      var side=card[conf[0]];
      var col=div("qcompare-col "+(side&&side.positive?"pos":(side&&!side.positive?"neg":"")));
      var h=div("qcompare-title",(side&&side.title)||"");
      col.appendChild(h);
      ((side&&side.points)||[]).forEach(function(pt){
        var item=div("qcompare-point");
        item.appendChild(el("span","qcompare-mark",side.positive?"+":"−"));
        item.appendChild(div("qcompare-text",pt));
        col.appendChild(item);
      });
      box.appendChild(col);
    });
    return box;
  }
  function renderTimerCard(card){
    var box=div("qcountdown");
    var t=div("qcountdown-val");
    box.appendChild(t);
    var remain=(card.seconds||0);
    function tick(){
      t.textContent=remain+"s";
      if(remain<=0){
        if(card.command)postCard({cmd:card.command,value:"done"});
        return;
      }
      remain--;
    }
    tick();
    setInterval(tick,1000);
    return box;
  }
  function renderCarouselCard(card){
    var box=div("qcarousel");
    var idx=0;
    var slides=card.slides||[];
    var viewport=div("qcarousel-viewport");
    var track=div("qcarousel-track");
    slides.forEach(function(sl){
      var item=div("qcarousel-slide");
      if(sl.color)item.style.background=sl.color;
      if(sl.title)item.appendChild(div("qcarousel-slide-title",sl.title));
      if(sl.body)item.appendChild(div("qcarousel-slide-body",sl.body));
      track.appendChild(item);
    });
    viewport.appendChild(track);
    box.appendChild(viewport);
    if(slides.length>1){
      var nav=div("qcarousel-nav");
      var prev=el("button","qbtn ghost","‹");
      var next=el("button","qbtn ghost","›");
      prev.type="button";next.type="button";
      function go(i){
        idx=(i+slides.length)%slides.length;
        track.style.transform="translateX(-"+(idx*100)+"%)";
      }
      prev.addEventListener("click",function(){go(idx-1);});
      next.addEventListener("click",function(){go(idx+1);});
      nav.appendChild(prev);nav.appendChild(next);
      box.appendChild(nav);
    }
    return box;
  }
  function renderKanbanCard(card){
    var box=div("qkanban");
    (card.columns||[]).forEach(function(col){
      var c=div("qkanban-col");
      c.appendChild(div("qkanban-col-name",col.name||""));
      var list=div("qkanban-list");
      (col.items||[]).forEach(function(it){
        var item=div("qkanban-item",it);
        list.appendChild(item);
      });
      c.appendChild(list);
      box.appendChild(c);
    });
    return box;
  }
  function renderYuanbaoCard(card){
    var box=div("qcard-list");
    (card.links||[]).forEach(function(l){
      var a=el("a","qlist-item");
      a.href=l.url||"#"; a.target="_blank"; a.rel="noopener";
      a.appendChild(div("qlist-main",l.title||""));
      box.appendChild(a);
    });
    return box;
  }
  function renderColorCard(card){
    var box=div("qcolors");
    (card.colors||[]).forEach(function(c){
      var sw=el("button","qcolor");
      sw.type="button";
      sw.style.background=c;
      sw.title=c;
      sw.addEventListener("click",function(){
        if(card.command)postCard({cmd:card.command,value:c});
        else if(navigator.clipboard)navigator.clipboard.writeText(c);
      });
      box.appendChild(sw);
    });
    return box;
  }
  function renderBreadcrumbCard(card){
    var box=div("qcrumbs");
    var crumbs=card.crumbs||[];
    crumbs.forEach(function(cr,i){
      var b=el("button","qcrumb");
      b.type="button";
      b.textContent=cr.label||"";
      b.addEventListener("click",function(){
        if(cr.command)postCard({cmd:cr.command,value:cr.label,index:i});
      });
      box.appendChild(b);
      if(i<crumbs.length-1)box.appendChild(el("span","qcrumb-sep","/"));
    });
    return box;
  }
  function renderTagCloudCard(card){
    var box=div("qtagcloud");
    var maxW=1;
    (card.tags||[]).forEach(function(t){if((t.weight||1)>maxW)maxW=t.weight;});
    (card.tags||[]).forEach(function(t){
      var b=el("button","qtag");
      b.type="button";
      b.textContent=t.label||"";
      b.style.fontSize=(11+((t.weight||1)/maxW)*8)+"px";
      b.addEventListener("click",function(){
        if(t.command)postCard({cmd:t.command,value:t.label});
      });
      box.appendChild(b);
    });
    return box;
  }
  function renderBadgeCard(card){
    var box=div("qbadges");
    (card.badges||[]).forEach(function(b){
      var chip=el("button","qbadge");
      chip.type="button";
      chip.textContent=b.label||"";
      if(b.color)chip.style.background=b.color;
      chip.addEventListener("click",function(){
        if(b.command)postCard({cmd:b.command,value:b.label});
      });
      box.appendChild(chip);
    });
    return box;
  }
  function renderAvatarGroupCard(card){
    var box=div("qavatars");
    (card.avatars||[]).forEach(function(a){
      var av=el("button","qavatar");
      av.type="button";
      av.title=a.name||"";
      if(a.url){
        var img=el("img");
        img.src=a.url;
        img.onerror=function(){img.replaceWith(div(null,(a.name||"?").charAt(0)));};
        av.appendChild(img);
      }else{
        av.textContent=(a.name||"?").charAt(0);
      }
      av.addEventListener("click",function(){
        if(a.command)postCard({cmd:a.command,value:a.name});
      });
      box.appendChild(av);
    });
    return box;
  }
  function renderMermaidCard(card){
    var box=div("qmmd");
    if(card.source)renderMermaid(box,{source:card.source},document.documentElement.classList.contains("dark"));
    else box.appendChild(div("mmd-empty","（空）"));
    return box;
  }
  function renderHtmlPreviewCard(card){
    var box=div("qhtml");
    var frame=el("iframe","qhtml-frame");
    frame.setAttribute("srcdoc",card.html||"");
    frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups");
    frame.style.height="360px";
    box.appendChild(frame);
    return box;
  }
  function renderMiniAppCard(card){
    var box=div("qhtml");
    var frame=el("iframe","qhtml-frame miniapp");
    frame.setAttribute("srcdoc",card.html||"");
    frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups");
    frame.style.height="480px";
    box.appendChild(frame);
    return box;
  }
  function renderCompositeCard(card){
    var box=div("qcomposite");
    if(card.description)box.appendChild(div("qcomposite-desc",card.description));
    var grid=div("qcomposite-grid");
    (card.children||[]).forEach(function(ch){
      var cell=div("qcomposite-cell");
      cell.appendChild(renderCardNode(ch));
      grid.appendChild(cell);
    });
    box.appendChild(grid);
    return box;
  }
  function renderActionCard(card){
    var box=div("qactions");
    (card.actions||[]).forEach(function(a){
      box.appendChild(cmdButton(a.label||"",a.command,"filled"));
    });
    return box;
  }/* ── 增强卡片渲染器（CardCodec 字段）──────────────────────────── */
  function renderKeyValueCard(card){
    var box=div("qkv");
    (card.items||[]).forEach(function(it){
      var row=div("qkv-row");
      if(it.icon)row.appendChild(div("qkv-icon",it.icon));
      var k=div("qkv-k",it.k||"");
      row.appendChild(k);
      var v=div("qkv-v",it.v||"");
      row.appendChild(v);
      if(it.command){
        row.style.cursor="pointer";
        row.addEventListener("click",function(){postCard({cmd:it.command,value:it.v});});
      }
      box.appendChild(row);
    });
    return box;
  }
  function renderRingCard(card){
    var box=div("qring-card");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var c=div("qgauge-ring");
    ringChart(c,card.value,card.max,card.color||CHART_COLORS[0],(card.caption||""));
    box.appendChild(c);
    return box;
  }
  function renderStackedBarCard(card){
    var box=div("qchart-card");
    var c=canvasBox(160);box.appendChild(c.box);
    var s=setupCanvas(c.cv),ctx=s.ctx,w=s.w,h=s.h;
    var padL=28,padB=18,chW=w-padL-4,chH=h-padB-8;
    var cats=card.categories||[];
    var series=card.series||[];
    var maxV=1;
    series.forEach(function(sr){(sr.values||[]).forEach(function(v){maxV=Math.max(maxV,v);});});
    var n=Math.max(cats.length,1), groupW=chW/n;
    series.forEach(function(sr,si){
      (sr.values||[]).forEach(function(v,i){
        var x=padL+i*groupW+groupW*0.2;
        var hh=(v/maxV)*chH*0.9;
        ctx.fillStyle=sr.color||CHART_COLORS[si%CHART_COLORS.length];
        ctx.fillRect(x,chH-hh,groupW*0.6,hh);
      });
    });
    ctx.strokeStyle="rgba(128,128,128,.4)";ctx.lineWidth=1;
    ctx.beginPath();ctx.moveTo(padL,chH);ctx.lineTo(w-2,chH);ctx.stroke();
    ctx.fillStyle="rgba(128,128,128,.8)";ctx.font="10px system-ui";
    cats.forEach(function(lb,i){
      ctx.textAlign="center";
      ctx.fillText(String(lb).slice(0,6),padL+i*groupW+groupW/2,chH+13);
    });
    return box;
  }
  function renderScatterCard(card){
    var box=div("qchart-card");
    var c=canvasBox(160);box.appendChild(c.box);
    var s=setupCanvas(c.cv),ctx=s.ctx,w=s.w,h=s.h;
    var padL=28,padB=18,chW=w-padL-4,chH=h-padB-8;
    var pts=card.points||[];
    var minX=Infinity,maxX=-Infinity,minY=Infinity,maxY=-Infinity;
    pts.forEach(function(p){
      minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);
      minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);
    });
    if(!isFinite(minX)){minX=0;maxX=1;minY=0;maxY=1;}
    var spanX=(maxX-minX)||1,spanY=(maxY-minY)||1;
    pts.forEach(function(p){
      var x=padL+((p.x-minX)/spanX)*chW;
      var y=chH-((p.y-minY)/spanY)*chH;
      ctx.beginPath();ctx.arc(x,y,4,0,Math.PI*2);
      ctx.fillStyle="#2E6BE6";ctx.fill();
      if(p.label){ctx.fillStyle="rgba(128,128,128,.8)";ctx.font="9px system-ui";ctx.fillText(String(p.label).slice(0,6),x+5,y-5);}
    });
    ctx.strokeStyle="rgba(128,128,128,.4)";ctx.lineWidth=1;
    ctx.beginPath();ctx.moveTo(padL,chH);ctx.lineTo(w-2,chH);ctx.stroke();
    return box;
  }
  function renderCandlestickCard(card){
    var box=div("qchart-card");
    var c=canvasBox(160);box.appendChild(c.box);
    var s=setupCanvas(c.cv),ctx=s.ctx,w=s.w,h=s.h;
    var padL=28,padB=18,chW=w-padL-4,chH=h-padB-8;
    var candles=card.candles||[];
    var maxH=1,minL=Infinity;
    candles.forEach(function(cd){maxH=Math.max(maxH,cd.h);minL=Math.min(minL,cd.l);});
    if(!isFinite(minL)){minL=0;}
    var span=(maxH-minL)||1,n=Math.max(candles.length,1),cw=chW/n;
    candles.forEach(function(cd,i){
      var x=padL+i*cw+cw*0.2;
      var yH=chH-((cd.h-minL)/span)*chH;
      var yL=chH-((cd.l-minL)/span)*chH;
      var yO=chH-((cd.o-minL)/span)*chH;
      var yC=chH-((cd.c-minL)/span)*chH;
      var up=cd.c>=cd.o;
      ctx.strokeStyle=up?"#D81B60":"#1A7A38";
      ctx.lineWidth=1;
      ctx.beginPath();ctx.moveTo(x+cw*0.3,yH);ctx.lineTo(x+cw*0.3,yL);ctx.stroke();
      ctx.fillStyle=up?"#D81B60":"#1A7A38";
      ctx.fillRect(x,yC,cw*0.6,Math.max(1,Math.abs(yO-yC)));
    });
    return box;
  }
  function renderBoxPlotCard(card){
    var box=div("qchart-card");
    var c=canvasBox(160);box.appendChild(c.box);
    var s=setupCanvas(c.cv),ctx=s.ctx,w=s.w,h=s.h;
    var padL=28,padB=18,chW=w-padL-4,chH=h-padB-8;
    var groups=card.groups||[];
    var maxV=1,minV=Infinity;
    groups.forEach(function(g){maxV=Math.max(maxV,g.max);minV=Math.min(minV,g.min);});
    if(!isFinite(minV))minV=0;
    var span=(maxV-minV)||1,n=Math.max(groups.length,1),gw=chW/n;
    groups.forEach(function(g,i){
      var x=padL+i*gw+gw*0.3;
      function y(v){return chH-((v-minV)/span)*chH;}
      ctx.strokeStyle="#2E6BE6";ctx.lineWidth=1.5;
      ctx.beginPath();ctx.moveTo(x+gw*0.2,y(g.max));ctx.lineTo(x+gw*0.2,y(g.min));ctx.stroke();
      ctx.beginPath();ctx.moveTo(x,y(g.q1));ctx.lineTo(x+gw*0.4,y(g.q1));ctx.stroke();
      ctx.beginPath();ctx.moveTo(x,y(g.median));ctx.lineTo(x+gw*0.4,y(g.median));ctx.stroke();
      ctx.beginPath();ctx.moveTo(x,y(g.q3));ctx.lineTo(x+gw*0.4,y(g.q3));ctx.stroke();
      ctx.fillStyle="rgba(46,107,230,.25)";
      ctx.fillRect(x,y(g.q1),gw*0.4,Math.max(1,y(g.q3)-y(g.q1)));
    });
    return box;
  }
  function renderSpeedometerCard(card){
    var box=div("qspeed");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var c=div("qgauge-ring");
    ringChart(c,card.value,card.max,CHART_COLORS[0],card.value+(card.unit||""));
    box.appendChild(c);
    return box;
  }
  function renderSparklineCard(card){
    var box=div("qspark");
    if(card.caption)box.appendChild(div("qspark-caption",card.caption));
    var c=div("qspark-svg");
    sparkline(c,card.values,card.color);
    box.appendChild(c);
    return box;
  }
  function renderSearchBoxCard(card){
    var box=div("qsearch");
    if(card.label)box.appendChild(div("qslider-label",card.label));
    var row=div("qsearch-row");
    var inp=el("input","qform-input");
    inp.type="text";
    inp.placeholder=card.placeholder||"";
    inp.value=card.value||"";
    var btn=cmdButton("搜索",null,"filled");
    btn.addEventListener("click",function(){
      if(card.command)postCard({cmd:card.command,value:inp.value});
    });
    inp.addEventListener("keydown",function(e){
      if(e.key==="Enter"){ if(card.command)postCard({cmd:card.command,value:inp.value}); }
    });
    row.appendChild(inp);row.appendChild(btn);
    box.appendChild(row);
    if(card.hint)box.appendChild(div("qsearch-hint",card.hint));
    return box;
  }
  function renderPollCard(card){
    var box=div("qpoll");
    box.appendChild(div("qpoll-q",card.question||""));
    var total=card.total||0;
    (card.options||[]).forEach(function(opt){
      var pct=total?Math.round((opt.count/total)*100):0;
      var row=div("qpoll-row");
      var bar=div("qpoll-bar");
      var fill=div("qpoll-fill");
      fill.style.width=pct+"%";
      if(opt.color)fill.style.background=opt.color;
      bar.appendChild(fill);
      var label=div("qpoll-label",opt.label||"");
      var val=div("qpoll-val",opt.count||0);
      row.appendChild(label);
      row.appendChild(bar);
      row.appendChild(val);
      row.addEventListener("click",function(){
        if(card.command)postCard({cmd:card.command,value:opt.label});
      });
      box.appendChild(row);
    });
    if(card.multi)box.appendChild(div("qpoll-multi","（多选）"));
    return box;
  }
  function renderCheckListCard(card){
    var box=div("qcard-list");
    (card.items||[]).forEach(function(it){
      var row=div("qcheck-row");
      var cb=el("input","qcheck");
      cb.type="checkbox";cb.checked=!!it.done;
      cb.addEventListener("change",function(){
        if(card.command)postCard({cmd:card.command,value:it.text,checked:cb.checked});
      });
      row.appendChild(cb);
      var label=el("span",null,it.text||"");
      if(it.done)label.className="done";
      row.appendChild(label);
      if(it.note)row.appendChild(div("qlist-sub",it.note));
      box.appendChild(row);
    });
    if(card.summary)box.appendChild(div("qcheck-summary",card.summary));
    return box;
  }
  function renderAccordionCard(card){
    var box=div("qacc");
    (card.items||[]).forEach(function(it,i){
      var item=div("qacc-item"+(i===card.expandedIndex?" open":""));
      var h=el("button","qacc-head");
      h.type="button";
      h.appendChild(div("qacc-title",it.title||""));
      var arrow=el("span","qexp-arrow","▸");
      h.appendChild(arrow);
      var body=div("qacc-body");
      body.appendChild(aipParagraph(it.body||""));
      h.addEventListener("click",function(){
        var open=item.classList.toggle("open");
        arrow.textContent=open?"▾":"▸";
      });
      item.appendChild(h);item.appendChild(body);
      box.appendChild(item);
    });
    return box;
  }
  function renderGroupedListCard(card){
    var box=div("qgrouped");
    (card.sections||[]).forEach(function(sec){
      var g=div("qgrouped-sec");
      g.appendChild(div("qgrouped-title",sec.title||""));
      (sec.items||[]).forEach(function(it){
        var row=div("qgrouped-item");
        if(it.icon)row.appendChild(div("qkv-icon",it.icon));
        row.appendChild(div("qgrouped-text",it.text||""));
        if(it.sub)row.appendChild(div("qlist-sub",it.sub));
        if(it.value)row.appendChild(div("qgrouped-value",it.value));
        g.appendChild(row);
      });
      box.appendChild(g);
    });
    return box;
  }
  function renderTreeCard(card){
    function node(n,depth){
      var row=div("qtree-node");
      var indent=el("span","qtree-indent");
      indent.style.width=(depth*14)+"px";
      row.appendChild(indent);
      if(n.icon)row.appendChild(div("qkv-icon",n.icon));
      var label=div("qtree-label",n.label||"");
      if(n.value)label.appendChild(div("qtree-value"," · "+n.value));
      row.appendChild(label);
      var box=div("qtree-children");
      row.appendChild(box);
      if(n.children&&n.children.length){
        row.classList.add("has");
        row.addEventListener("click",function(){
          var open=row.classList.toggle("open");
          box.style.display=open?"block":"none";
        });
        n.children.forEach(function(ch){box.appendChild(node(ch,depth+1));});
      }
      return row;
    }
    var box=div("qtree");
    (card.nodes||[]).forEach(function(n){box.appendChild(node(n,0));});
    return box;
  }
  function renderQuoteCard(card){
    var box=div("aip-quote");
    var p=el("p");p.innerHTML=inlineMarkdown(card.text||"");box.appendChild(p);
    if(card.author)box.appendChild(div("aip-quote-cite","—— "+card.author));
    if(card.source)box.appendChild(div("aip-quote-source",card.source));
    return box;
  }
  function renderDiffCard(card){
    var box=div("qdiff");
    if(card.file)box.appendChild(div("qdiff-file",card.file));
    (card.rows||[]).forEach(function(r){
      var row=div("qdiff-row "+String(r.kind||""));
      var mark=el("span","qdiff-mark",r.kind==="add"?"+":(r.kind==="del"?"−":" "));
      row.appendChild(mark);
      var text=div("qdiff-text",r.text||"");
      if(r.oldText)text.appendChild(div("qdiff-old",r.oldText));
      row.appendChild(text);
      box.appendChild(row);
    });
    if(card.additions||card.deletions){
      var foot=div("qdiff-foot");
      if(card.additions)foot.appendChild(div("qdiff-add","+"+card.additions));
      if(card.deletions)foot.appendChild(div("qdiff-del","−"+card.deletions));
      box.appendChild(foot);
    }
    return box;
  }
  function renderFlowCard(card){
    var box=div("qflow");
    var nodes=card.nodes||[];
    nodes.forEach(function(n,i){
      var item=el("button","qflow-node "+String(n.kind||""));
      item.type="button";
      item.appendChild(div("qflow-label",n.label||""));
      if(n.desc)item.appendChild(div("qflow-desc",n.desc));
      item.addEventListener("click",function(){
        if(n.command)postCard({cmd:n.command,value:n.label});
      });
      box.appendChild(item);
      if(i<nodes.length-1)box.appendChild(div("qflow-arrow","→"));
    });
    return box;
  }
  function renderHierarchyCard(card){
    var box=div("qhierarchy");
    (card.stages||[]).forEach(function(st){
      var stage=div("qh-stage");
      if(st.color)stage.style.borderColor=st.color;
      stage.appendChild(div("qh-stage-name",st.name||""));
      (st.items||[]).forEach(function(it){
        stage.appendChild(div("qh-stage-item",it));
      });
      box.appendChild(stage);
    });
    return box;
  }
  function renderContactCard(card){
    var box=div("qcontact");
    var head=div("qcontact-head");
    var av=div("qcontact-avatar",(card.name||"?").charAt(0));
    if(card.avatar){
      var img=el("img");
      img.src=card.avatar;
      img.onerror=function(){img.remove();};
      head.appendChild(img);
    }else head.appendChild(av);
    var info=div("qcontact-info");
    info.appendChild(div("qcontact-name",card.name||""));
    if(card.role)info.appendChild(div("qcontact-role",card.role));
    head.appendChild(info);
    if(card.status){
      var st=div("qcontact-status "+(card.status==="online"?"on":""));
      head.appendChild(st);
    }
    box.appendChild(head);
    var actions=div("qactions");
    (card.actions||[]).forEach(function(a){
      actions.appendChild(cmdButton(a.label||"",a.command,"ghost"));
    });
    if(actions.children.length)box.appendChild(actions);
    return box;
  }
  function renderProductCard(card){
    var box=div("qproduct");
    var imgBox=div("qproduct-img");
    if(card.image){
      var img=el("img");
      img.src=card.image;
      img.onerror=function(){img.remove();};
      imgBox.appendChild(img);
    }
    box.appendChild(imgBox);
    box.appendChild(div("qproduct-name",card.name||""));
    if(card.tag)box.appendChild(div("qproduct-tag",card.tag));
    var priceRow=div("qproduct-price-row");
    priceRow.appendChild(div("qproduct-price",card.price||""));
    if(card.originalPrice)priceRow.appendChild(div("qproduct-original",card.originalPrice));
    box.appendChild(priceRow);
    if(card.rating||card.sold){
      var meta=div("qproduct-meta");
      if(card.rating)meta.appendChild(div("qproduct-rating","★ "+card.rating));
      if(card.sold)meta.appendChild(div("qproduct-sold","已售 "+card.sold));
      box.appendChild(meta);
    }
    if(card.command)box.appendChild(cmdButton("购买",card.command,"filled"));
    return box;
  }
  function renderScheduleCard(card){
    var box=div("qschedule");
    box.appendChild(div("qschedule-event",card.event||""));
    var meta=div("qschedule-meta");
    if(card.date)meta.appendChild(div("qschedule-date","📅 "+card.date));
    if(card.time)meta.appendChild(div("qschedule-time","🕐 "+card.time));
    if(card.place)meta.appendChild(div("qschedule-place","📍 "+card.place));
    box.appendChild(meta);
    if(card.command)box.appendChild(cmdButton("确认",card.command,"ghost"));
    return box;
  }
  function renderFileCard(card){
    var box=div("qfile");
    var row=div("qfile-row");
    row.appendChild(div("qfile-name",card.name||""));
    if(card.size)row.appendChild(div("qfile-size",card.size));
    box.appendChild(row);
    if(card.path)box.appendChild(div("qfile-path",card.path));
    if(card.mime)box.appendChild(div("qfile-mime",card.mime));
    if(card.command)box.appendChild(cmdButton("打开",card.command,"ghost"));
    return box;
  }
  function renderAchievementCard(card){
    var box=div("qachievement");
    if(card.icon)box.appendChild(div("qachievement-icon",card.icon));
    box.appendChild(div("qachievement-name",card.name||""));
    box.appendChild(div("qachievement-desc",card.desc||""));
    if(card.progress!==undefined&&card.progress!==null){
      var bar=div("qprogress-bar");
      var fill=div("qprogress-fill");
      fill.style.width=Math.max(0,Math.min(100,card.progress))+"%";
      bar.appendChild(fill);
      box.appendChild(bar);
    }
    if(card.command)box.appendChild(cmdButton("领取",card.command,"ghost"));
    return box;
  }
  function renderWeatherCard(card){
    var box=div("qweather");
    var head=div("qweather-head");
    if(card.icon)head.appendChild(div("qweather-icon",card.icon));
    head.appendChild(div("qweather-temp",card.temp||""));
    head.appendChild(div("qweather-cond",card.condition||""));
    if(card.city)head.appendChild(div("qweather-city",card.city));
    box.appendChild(head);
    var meta=div("qweather-meta");
    if(card.humidity!==undefined)meta.appendChild(div("qweather-hum","💧 "+card.humidity+"%"));
    if(card.wind)meta.appendChild(div("qweather-wind","🌬 "+card.wind));
    box.appendChild(meta);
    if((card.hours||[]).length){
      var hours=div("qweather-hours");
      (card.hours||[]).forEach(function(hh){
        var h=div("qweather-hour");
        if(hh.i)h.appendChild(div("qweather-hour-icon",hh.i));
        if(hh.t)h.appendChild(div("qweather-hour-t",hh.v||""));
        if(hh.v)h.appendChild(div("qweather-hour-v",hh.v));
        hours.appendChild(h);
      });
      box.appendChild(hours);
    }
    return box;
  }
  function renderMapCard(card){
    var box=div("qmap");
    box.appendChild(div("qmap-placeholder","📍 "+card.lat+", "+card.lng+" (zoom "+card.zoom+")"));
    (card.markers||[]).forEach(function(m){
      box.appendChild(div("qmap-marker","🔴 "+m.label+" ("+m.lat+","+m.lng+")"));
    });
    return box;
  }
  function renderQrCodeCard(card){
    var box=div("qqr");
    box.appendChild(div("qqr-content",card.content||""));
    if(card.caption)box.appendChild(div("qqr-caption",card.caption));
    return box;
  }
  function renderGalleryCard(card){
    var box=div("qgallery");
    var cols=card.columns||3;
    box.style.gridTemplateColumns="repeat("+cols+",1fr)";
    (card.images||[]).forEach(function(im){
      var item=div("qgallery-item");
      var img=el("img");
      img.src=im.url||"";
      img.loading="lazy";
      img.onerror=function(){img.replaceWith(div("qgallery-caption",im.caption||""));};
      item.appendChild(img);
      if(im.caption)item.appendChild(div("qgallery-caption",im.caption));
      box.appendChild(item);
    });
    return box;
  }
  function renderTerminalCard(card){
    var box=div("qterminal");
    if(card.command)box.appendChild(div("qterminal-cmd","$ "+card.command));
    (card.lines||[]).forEach(function(l){
      box.appendChild(el("div","qterminal-line",l));
    });
    if(card.exitCode!==undefined)box.appendChild(div("qterminal-exit","exit code: "+card.exitCode));
    return box;
  }
  function renderLinkListCard(card){
    var box=div("qcard-list");
    (card.links||[]).forEach(function(l){
      var a=el("a","qlist-item");
      a.href=l.url||"#"; a.target="_blank"; a.rel="noopener";
      if(l.icon)a.appendChild(div("qkv-icon",l.icon));
      var main=div("qlist-main",l.label||"");
      if(l.desc)main.appendChild(div("qlist-sub",l.desc));
      a.appendChild(main);
      box.appendChild(a);
    });
    return box;
  }
  function renderPaginationCard(card){
    var box=div("qpagination");
    var total=Math.max(1,card.total||1);
    for(var i=1;i<=total;i++){
      (function(pg){
        var b=el("button","qpage"+(pg===card.page?" on":""));
        b.type="button";b.textContent=pg;
        b.addEventListener("click",function(){
          if(card.command)postCard({cmd:card.command,value:String(pg),page:pg});
        });
        box.appendChild(b);
      })(i);
    }
    return box;
  }
  function renderDividerCard(card){
    var box=div("qdivider"+(card.dashed?" dashed":""));
    if(card.text)box.appendChild(div("qdivider-text",card.text));
    return box;
  }
  function renderSpacerCard(card){
    var box=div("qspacer");
    box.style.height=(card.height||16)+"px";
    return box;
  }
  function renderCustomCard(card){
    var box=div("qcustom");
    var kind=String(card.kind||"");
    if(kind==="html"||kind==="web"){
      var frame=el("iframe","qhtml-frame");
      frame.setAttribute("srcdoc",typeof card.payload==="string"?card.payload:JSON.stringify(card.payload));
      frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups");
      frame.style.height="360px";
      box.appendChild(frame);
    }else if(typeof card.payload==="string"){
      box.appendChild(el("pre","qcustom-payload",card.payload));
    }else{
      box.appendChild(el("pre","qcustom-payload",JSON.stringify(card.payload,null,2)));
    }
    (card.children||[]).forEach(function(ch){box.appendChild(renderCardNode(ch));});
    return box;
  }
  function renderGanttCard(card){
    var box=div("qgantt");
    (card.tasks||[]).forEach(function(t){
      var row=div("qgantt-row");
      var name=div("qgantt-name",t.name||"");
      var barWrap=div("qgantt-bar-wrap");
      var bar=div("qgantt-bar");
      var left=(t.start||0)*2;
      var width=Math.max(4,(t.duration||1)*2);
      bar.style.left=left+"px";
      bar.style.width=width+"px";
      if(t.color)bar.style.background=t.color;
      if(t.progress!==undefined)bar.style.opacity=String(0.5+(t.progress/200));
      bar.title=(t.owner||"")+" "+(t.progress||0)+"%";
      barWrap.appendChild(bar);
      row.appendChild(name);row.appendChild(barWrap);
      box.appendChild(row);
    });
    return box;
  }
  function renderInvoiceCard(card){
    var box=div("qinvoice");
    box.appendChild(div("qinvoice-merchant",card.merchant||""));
    var t=el("table","qtable");
    var thead=el("thead"),tr=el("tr");
    ["项目","数量","单价","金额"].forEach(function(h){var th=el("th",null,h);tr.appendChild(th);});
    thead.appendChild(tr);t.appendChild(thead);
    var tb=el("tbody");
    (card.items||[]).forEach(function(it){
      var rtr=el("tr");
      rtr.appendChild(el("td",null,it.name||""));
      rtr.appendChild(el("td",null,String(it.qty||"")));
      rtr.appendChild(el("td",null,String(it.price||"")));
      rtr.appendChild(el("td",null,String(it.amount||"")));
      tb.appendChild(rtr);
    });
    t.appendChild(tb);box.appendChild(t);
    var total=div("qinvoice-total");
    total.appendChild(div("qinvoice-subtotal","小计 "+(card.subtotal||"")));
    if(card.discount)total.appendChild(div("qinvoice-discount","折扣 "+card.discount));
    total.appendChild(div("qinvoice-final","合计 "+(card.total||"")));
    box.appendChild(total);
    if(card.note)box.appendChild(div("qinvoice-note",card.note));
    if(card.command)box.appendChild(cmdButton("支付",card.command,"filled"));
    return box;
  }
  function renderCurrencyCard(card){
    var box=div("qcurrency");
    box.appendChild(div("qcurrency-base",(card.base||"")+" "+(card.value||"")));
    var t=el("table","qtable");
    var thead=el("thead"),tr=el("tr");
    ["代码","符号","汇率","涨跌"].forEach(function(h){var th=el("th",null,h);tr.appendChild(th);});
    thead.appendChild(tr);t.appendChild(thead);
    var tb=el("tbody");
    (card.rates||[]).forEach(function(r){
      var rtr=el("tr");
      rtr.appendChild(el("td",null,r.code||""));
      rtr.appendChild(el("td",null,r.symbol||""));
      rtr.appendChild(el("td",null,String(r.rate||"")));
      rtr.appendChild(el("td",null,String(r.change||"")));
      tb.appendChild(rtr);
    });
    t.appendChild(tb);box.appendChild(t);
    if(card.updated)box.appendChild(div("qcurrency-updated","更新于 "+card.updated));
    return box;
  }
  function renderClockCard(card){
    var box=div("qclock");
    if(card.current)box.appendChild(div("qclock-current",card.current));
    (card.zones||[]).forEach(function(z){
      var row=div("qclock-zone");
      row.appendChild(div("qclock-city",z.city||""));
      row.appendChild(div("qclock-offset",z.offset||""));
      if(z.diff)row.appendChild(div("qclock-diff",z.diff));
      box.appendChild(row);
    });
    return box;
  }
  function renderTrackerCard(card){
    var box=div("qtracker");
    box.appendChild(div("qtracker-name",card.name||""));
    var row=div("qtracker-row");
    (card.days||[]).forEach(function(d,i){
      var cell=div("qtracker-cell"+(i<card.streak?" on":""));
      cell.textContent=d||"";
      row.appendChild(cell);
    });
    box.appendChild(row);
    if(card.target)box.appendChild(div("qtracker-target","目标 "+card.target+(card.unit||"")));
    return box;
  }
  function renderScoreboardCard(card){
    var box=div("qscore");
    var row=div("qscore-row");
    var home=div("qscore-side");
    home.appendChild(div("qscore-name",card.home||""));
    home.appendChild(div("qscore-num",String(card.homeScore||0)));
    row.appendChild(home);
    var mid=div("qscore-mid");
    mid.appendChild(div("qscore-status",card.status||""));
    if(card.period||card.time)mid.appendChild(div("qscore-period",(card.period||"")+" "+(card.time||"")));
    row.appendChild(mid);
    var away=div("qscore-side");
    away.appendChild(div("qscore-name",card.away||""));
    away.appendChild(div("qscore-num",String(card.awayScore||0)));
    row.appendChild(away);
    box.appendChild(row);
    return box;
  }
  function renderRankingCard(card){
    var box=div("qranking");
    var topN=card.topN||(card.items||[]).length;
    (card.items||[]).slice(0,topN).forEach(function(it,i){
      var row=div("qrank-row");
      row.appendChild(div("qrank-idx",String(i+1)));
      var name=div("qrank-name",it.name||"");
      row.appendChild(name);
      if(it.trend){
        var trend=String(it.trend||"flat").toLowerCase();
        var d=div("qrank-delta "+(trend==="up"?"up":(trend==="down"?"down":"flat")),(trend==="up"?"▲ ":(trend==="down"?"▼ ":"• "))+(it.delta||""));
        row.appendChild(d);
      }
      row.appendChild(div("qrank-value",String(it.value||"")+(card.unit||"")));
      box.appendChild(row);
    });
    return box;
  }
  function renderVocabCard(card){
    var box=div("qvocab");
    box.appendChild(div("qvocab-word",card.word||""));
    if(card.phonetic)box.appendChild(div("qvocab-phonetic",card.phonetic));
    if(card.pos)box.appendChild(div("qvocab-pos",card.pos));
    if(card.meaning)box.appendChild(div("qvocab-meaning",card.meaning));
    (card.examples||[]).forEach(function(e){
      var ex=div("qvocab-example");
      ex.appendChild(div("qvocab-en",e.en||""));
      if(e.zh)ex.appendChild(div("qvocab-zh",e.zh));
      box.appendChild(ex);
    });
    if((card.tags||[]).length){
      var tags=div("qbadges");
      (card.tags||[]).forEach(function(t){tags.appendChild(el("span","qbadge",t));});
      box.appendChild(tags);
    }
    return box;
  }
  function renderFormulaCard(card){
    var box=div("qformula");
    box.appendChild(el("code","qformula-expr",card.expr||""));
    if(card.note)box.appendChild(div("qformula-note",card.note));
    (card.vars||[]).forEach(function(v){
      var row=div("qformula-var");
      row.appendChild(div("qformula-var-name",v.name||""));
      row.appendChild(div("qformula-var-desc",v.desc||""));
      box.appendChild(row);
    });
    return box;
  }
  function renderTranslateCard(card){
    var box=div("qtranslate");
    box.appendChild(div("qtranslate-src",card.src||""));
    if(card.alt&&card.alt.length)box.appendChild(div("qtranslate-alt",card.alt.join(" / ")));
    box.appendChild(div("qtranslate-arrow","↓"));
    box.appendChild(div("qtranslate-dst",card.dst||""));
    if(card.audio){
      var au=el("audio","qmedia-audio");
      au.controls=true;au.src=card.audio;
      box.appendChild(au);
    }
    return box;
  }
  function renderPaletteCard(card){
    var box=div("qpalette");
    if(card.name)box.appendChild(div("qslider-label",card.name));
    var row=div("qcolors");
    (card.colors||[]).forEach(function(c){
      var sw=el("button","qcolor");
      sw.type="button";
      sw.style.background=c.hex||"";
      sw.title=(c.name||"")+" "+(c.hex||"");
      sw.addEventListener("click",function(){
        if(navigator.clipboard)navigator.clipboard.writeText(c.hex||"");
      });
      row.appendChild(sw);
    });
    box.appendChild(row);
    if(card.copyable)box.appendChild(div("qpalette-hint","点击复制"));
    return box;
  }
  function renderStopwatchCard(card){
    var box=div("qcountdown");
    var t=div("qcountdown-val");
    box.appendChild(t);
    var sec=card.seconds||0;
    function tick(){
      t.textContent=pad2(Math.floor(sec/60))+":"+pad2(sec%60);
    }
    tick();
    setInterval(function(){
      sec++;
      tick();
      if(card.command&&sec%10===0)postCard({cmd:card.command,value:String(sec)});
    },1000);
    return box;
  }
  function renderBarcodeCard(card){
    var box=div("qbarcode");
    var code=String(card.code||"");
    box.appendChild(el("code","qbarcode-code",code));
    if(card.format)box.appendChild(div("qbarcode-format",card.format));
    if(card.caption)box.appendChild(div("qbarcode-caption",card.caption));
    return box;
  }
  function renderDecisionCard(card){
    var box=div("qdecision");
    box.appendChild(div("qpoll-q",card.question||""));
    if(card.context)box.appendChild(div("qdecision-context",card.context));
    (card.options||[]).forEach(function(op){
      var row=div("qdecision-row");
      var label=div("qdecision-label",op.label||"");
      if(op.detail)label.appendChild(div("qdecision-detail",op.detail));
      if(op.recommended)label.appendChild(div("qdecision-rec","推荐"));
      row.appendChild(label);
      row.addEventListener("click",function(){
        if(card.command)postCard({cmd:card.command,value:op.value||op.label});
      });
      box.appendChild(row);
    });
    if(card.allowCustom){
      var inp=el("input","qform-input");
      inp.placeholder=card.customHint||"自定义…";
      var btn=cmdButton("提交",null,"ghost");
      btn.addEventListener("click",function(){
        if(card.command&&inp.value)postCard({cmd:card.command,value:inp.value});
      });
      box.appendChild(inp);box.appendChild(btn);
    }
    return box;
  }
  function renderConfirmCard(card){
    var box=div("qconfirm");
    box.appendChild(div("qpoll-q",card.message||""));
    if(card.detail)box.appendChild(div("qconfirm-detail",card.detail));
    var actions=div("qactions");
    var cancel=cmdButton(card.cancelLabel||"取消",card.command,"ghost");
    var confirm=cmdButton(card.confirmLabel||"确定",card.command,card.danger?"filled":"filled");
    actions.appendChild(cancel);actions.appendChild(confirm);
    box.appendChild(actions);
    return box;
  }
  function renderSankeyCard(card){
    var box=div("qsankey");
    box.appendChild(div("qchart-title","桑基图"+(card.unit?"（"+card.unit+"）":"")));
    (card.nodes||[]).forEach(function(n){
      box.appendChild(div("qsankey-node",n.label||n.id||""));
    });
    (card.links||[]).forEach(function(l){
      box.appendChild(div("qsankey-link"," "+(l.from||"")+" → "+(l.to||"")+" : "+(l.value||"")));
    });
    return box;
  }
  function renderFunnelCard(card){
    var box=div("qfunnel");
    var steps=card.steps||[];
    steps.forEach(function(st,i){
      var row=div("qfunnel-step");
      if(st.color)row.style.background=st.color;
      row.appendChild(div("qfunnel-name",st.name||""));
      row.appendChild(div("qfunnel-value",String(st.value||"")+(card.unit||"")));
      if(card.showRate&&i>0){
        var prev=steps[i-1]?steps[i-1].value:0;
        var rate=prev?Math.round((st.value/prev)*100):0;
        row.appendChild(div("qfunnel-rate",rate+"%"));
      }
      if(st.hint)row.appendChild(div("qfunnel-hint",st.hint));
      box.appendChild(row);
    });
    return box;
  }
  function renderWaterfallCard(card){
    var box=div("qwaterfall");
    if(card.start!==undefined)box.appendChild(div("qwaterfall-start","起始 "+(card.start||"")+(card.unit||"")));
    (card.steps||[]).forEach(function(st){
      var row=div("qwaterfall-row "+(st.isTotal?"total":((st.delta||0)<0?"neg":"pos")));
      row.appendChild(div("qwaterfall-label",st.label||""));
      row.appendChild(div("qwaterfall-delta",((st.delta||0)<0?"":"+")+st.delta+(card.unit||"")));
      row.appendChild(div("qwaterfall-value",String(st.value||"")+(card.unit||"")));
      box.appendChild(row);
    });
    return box;
  }
  function renderQuadrantCard(card){
    var box=div("qquadrant");
    var head=div("qquadrant-head");
    if(card.yLabel)head.appendChild(div("qquadrant-axis-y",card.yLabel));
    if(card.xLabel)head.appendChild(div("qquadrant-axis-x",card.xLabel));
    box.appendChild(head);
    (card.items||[]).forEach(function(it){
      var row=div("qquadrant-item");
      row.appendChild(div("qquadrant-label",it.label||""));
      row.appendChild(div("qquadrant-pos","("+it.x+","+it.y+")"));
      if(it.tag)row.appendChild(div("qquadrant-tag",it.tag));
      box.appendChild(row);
    });
    return box;
  }
  function renderMatrixCard(card){
    var box=div("qmatrix");
    var head=div("qmatrix-head");
    if(card.leftLabel)head.appendChild(div("qmatrix-left",card.leftLabel));
    if(card.rightLabel)head.appendChild(div("qmatrix-right",card.rightLabel));
    box.appendChild(head);
    (card.rows||[]).forEach(function(r){
      var row=div("qmatrix-row");
      row.appendChild(div("qmatrix-row-label",r.label||""));
      var better=r.better?" ✓":"";
      row.appendChild(div("qmatrix-left-val",String(r.left||"")+better));
      row.appendChild(div("qmatrix-right-val",String(r.right||"")+better));
      box.appendChild(row);
    });
    return box;
  }
  function renderFeedCard(card){
    var box=div("qfeed");
    if(card.source)box.appendChild(div("qfeed-source",card.source));
    (card.items||[]).forEach(function(it){
      var item=div("qfeed-item "+(it.level||""));
      if(it.time)item.appendChild(div("qfeed-time",it.time));
      item.appendChild(div("qfeed-text",it.text||""));
      if(it.actor)item.appendChild(div("qfeed-actor",it.actor));
      box.appendChild(item);
    });
    return box;
  }
  function renderGraphCard(card){
    var box=div("qgraph");
    var row=div("qgraph-nodes");
    (card.nodes||[]).forEach(function(n){
      var node=div("qgraph-node "+(n.shape||""));
      node.appendChild(div("qgraph-label",n.label||""));
      row.appendChild(node);
    });
    box.appendChild(row);
    var edges=div("qgraph-edges");
    (card.edges||[]).forEach(function(e){
      edges.appendChild(div("qgraph-edge",(e.from||"")+" —"+(e.label?"["+e.label+"]":"")+"→ "+(e.to||"")));
    });
    box.appendChild(edges);
    return box;
  }
  function renderSectionCard(card){
    var box=div("qsection-card");
    (card.sections||[]).forEach(function(sec){
      var s=div("qsection");
      s.appendChild(div("qsection-title",sec.title||""));
      (sec.rows||[]).forEach(function(r){
        var row=div("qsection-row");
        row.appendChild(div("qsection-k",r.k||""));
        row.appendChild(div("qsection-v",r.v||""));
        s.appendChild(row);
      });
      box.appendChild(s);
    });
    return box;
  }/* ── 卡片渲染总入口（serializeCard 输出 cardType）────────────────── */
  function renderCardNode(card){
    if(!card||!card.cardType)return div("qcard-empty","（空卡片）");
    var type=String(card.cardType).toLowerCase();
    var body=div("qcard-inner");
    switch(type){
      case "todo": body.appendChild(renderTodoCard(card)); break;
      case "button": body.appendChild(renderButtonCard(card)); break;
      case "toggle": body.appendChild(renderToggleCard(card)); break;
      case "slider": body.appendChild(renderSliderCard(card)); break;
      case "counter": body.appendChild(renderCounterCard(card)); break;
      case "progress": body.appendChild(renderProgressCard(card)); break;
      case "stat": body.appendChild(renderStatCard(card)); break;
      case "alert": body.appendChild(renderAlertCard(card)); break;
      case "table": body.appendChild(renderTableCard(card)); break;
      case "list": body.appendChild(renderListCard(card)); break;
      case "segmented": body.appendChild(renderSegmentedCard(card)); break;
      case "pie": body.appendChild(renderPieCard(card)); break;
      case "chart": body.appendChild(renderChartCard(card)); break;
      case "rating": body.appendChild(renderRatingCard(card)); break;
      case "countdown": body.appendChild(renderCountdownCard(card)); break;
      case "tabs": body.appendChild(renderTabsCard(card)); break;
      case "expandable": body.appendChild(renderExpandableCard(card)); break;
      case "form": body.appendChild(renderFormCard(card)); break;
      case "chips": body.appendChild(renderChipsCard(card)); break;
      case "steps": body.appendChild(renderStepsCard(card)); break;
      case "gauge": body.appendChild(renderGaugeCard(card)); break;
      case "media": body.appendChild(renderMediaCard(card)); break;
      case "info": body.appendChild(renderInfoCard(card)); break;
      case "toolcall": body.appendChild(renderToolCallCard(card)); break;
      case "stream": body.appendChild(renderStreamCard(card)); break;
      case "mediaplay": body.appendChild(renderMediaCard(card)); break;
      case "quickreply": body.appendChild(renderQuickReplyCard(card)); break;
      case "quickaction": body.appendChild(renderQuickActionCard(card)); break;
      case "timeline": body.appendChild(renderTimelineCard(card)); break;
      case "heatmap": body.appendChild(renderHeatmapCard(card)); break;
      case "compare": body.appendChild(renderCompareCard(card)); break;
      case "radar": { var c2=canvasBox(160); drawRadar(c2.cv,card.axes||[]); body.appendChild(c2.box); break; }
      case "timer": body.appendChild(renderTimerCard(card)); break;
      case "carousel": body.appendChild(renderCarouselCard(card)); break;
      case "kanban": body.appendChild(renderKanbanCard(card)); break;
      case "yuanbao": body.appendChild(renderYuanbaoCard(card)); break;
      case "color": body.appendChild(renderColorCard(card)); break;
      case "breadcrumb": body.appendChild(renderBreadcrumbCard(card)); break;
      case "tagcloud": body.appendChild(renderTagCloudCard(card)); break;
      case "badge": body.appendChild(renderBadgeCard(card)); break;
      case "avatargroup": body.appendChild(renderAvatarGroupCard(card)); break;
      case "mermaid": body.appendChild(renderMermaidCard(card)); break;
      case "htmlpreview": body.appendChild(renderHtmlPreviewCard(card)); break;
      case "miniapp": body.appendChild(renderMiniAppCard(card)); break;
      case "composite": body.appendChild(renderCompositeCard(card)); break;
      case "actions": body.appendChild(renderActionCard(card)); break;
      case "keyvalue": body.appendChild(renderKeyValueCard(card)); break;
      case "ring": body.appendChild(renderRingCard(card)); break;
      case "stackedbar": body.appendChild(renderStackedBarCard(card)); break;
      case "scatter": body.appendChild(renderScatterCard(card)); break;
      case "candlestick": body.appendChild(renderCandlestickCard(card)); break;
      case "boxplot": body.appendChild(renderBoxPlotCard(card)); break;
      case "speedometer": body.appendChild(renderSpeedometerCard(card)); break;
      case "sparkline": body.appendChild(renderSparklineCard(card)); break;
      case "searchbox": body.appendChild(renderSearchBoxCard(card)); break;
      case "poll": body.appendChild(renderPollCard(card)); break;
      case "checklist": body.appendChild(renderCheckListCard(card)); break;
      case "accordion": body.appendChild(renderAccordionCard(card)); break;
      case "groupedlist": body.appendChild(renderGroupedListCard(card)); break;
      case "tree": body.appendChild(renderTreeCard(card)); break;
      case "quote": body.appendChild(renderQuoteCard(card)); break;
      case "diff": body.appendChild(renderDiffCard(card)); break;
      case "flow": body.appendChild(renderFlowCard(card)); break;
      case "hierarchy": body.appendChild(renderHierarchyCard(card)); break;
      case "contact": body.appendChild(renderContactCard(card)); break;
      case "product": body.appendChild(renderProductCard(card)); break;
      case "schedule": body.appendChild(renderScheduleCard(card)); break;
      case "filecard": body.appendChild(renderFileCard(card)); break;
      case "achievement": body.appendChild(renderAchievementCard(card)); break;
      case "weather": body.appendChild(renderWeatherCard(card)); break;
      case "map": body.appendChild(renderMapCard(card)); break;
      case "qrcode": body.appendChild(renderQrCodeCard(card)); break;
      case "gallery": body.appendChild(renderGalleryCard(card)); break;
      case "terminal": body.appendChild(renderTerminalCard(card)); break;
      case "linklist": body.appendChild(renderLinkListCard(card)); break;
      case "pagination": body.appendChild(renderPaginationCard(card)); break;
      case "divider": body.appendChild(renderDividerCard(card)); break;
      case "spacer": body.appendChild(renderSpacerCard(card)); break;
      case "custom": body.appendChild(renderCustomCard(card)); break;
      case "gantt": body.appendChild(renderGanttCard(card)); break;
      case "invoice": body.appendChild(renderInvoiceCard(card)); break;
      case "currency": body.appendChild(renderCurrencyCard(card)); break;
      case "clock": body.appendChild(renderClockCard(card)); break;
      case "tracker": body.appendChild(renderTrackerCard(card)); break;
      case "scoreboard": body.appendChild(renderScoreboardCard(card)); break;
      case "vocab": body.appendChild(renderVocabCard(card)); break;
      case "formula": body.appendChild(renderFormulaCard(card)); break;
      case "translate": body.appendChild(renderTranslateCard(card)); break;
      case "palette": body.appendChild(renderPaletteCard(card)); break;
      case "stopwatch": body.appendChild(renderStopwatchCard(card)); break;
      case "barcode": body.appendChild(renderBarcodeCard(card)); break;
      case "decision": body.appendChild(renderDecisionCard(card)); break;
      case "confirm": body.appendChild(renderConfirmCard(card)); break;
      case "sankey": body.appendChild(renderSankeyCard(card)); break;
      case "funnel": body.appendChild(renderFunnelCard(card)); break;
      case "waterfall": body.appendChild(renderWaterfallCard(card)); break;
      case "quadrant": body.appendChild(renderQuadrantCard(card)); break;
      case "matrix": body.appendChild(renderMatrixCard(card)); break;
      case "feed": body.appendChild(renderFeedCard(card)); break;
      case "graph": body.appendChild(renderGraphCard(card)); break;
      case "section": body.appendChild(renderSectionCard(card)); break;
      default: {
        body.appendChild(el("pre","qcard-raw",JSON.stringify(card,null,2)));
        break;
      }
    }
    return body;
  }

  /** 组件围栏渲染：data.ok=false 时显示结构化降级（不闪空卡）。 */
  function renderCard(container,data){
    if(!data||!data.ok){
      container.appendChild(div("aip-degraded","组件卡片暂不可用"));
      if(data&&data.fence){
        var pre=el("pre","aip-degraded-raw",data.fence);
        container.appendChild(pre);
      }
      return;
    }
    var shell=cardShell({title:data.title},data.theme,data.compact);
    (data.cards||[]).forEach(function(card){
      var cell=div("qcard");
      cell.appendChild(renderCardNode(card));
      shell.body.appendChild(cell);
    });
    container.appendChild(shell.wrap);
  }

  /* ── 动态 UI 渲染 ───────────────────────────────────────────────── */
  function uiText(v){
    return v===undefined||v===null?"":String(v);
  }
  function uiStyleOf(node){
    var out={};
    var s=node&&node.style;
    if(!s||s===null)return out;
    if(s.background){
      if(s.background.kind==="solid")out.background=s.background.color;
      else if(s.background.kind==="gradient"&&s.background.colors){
        var dir=s.background.direction==="radial"?"radial-gradient(":"linear-gradient("+(s.background.angle||"135deg")+",";
        out.background=dir+s.background.colors.join(",")+")";
      }
    }
    if(s.borderColor)out.borderColor=s.borderColor;
    if(s.borderWidth)out.borderWidth=s.borderWidth+"px";
    if(s.borderRadius)out.borderRadius=s.borderRadius+"px";
    if(s.padding){
      if(s.padding.all!==undefined&&s.padding.all!==null)out.padding=s.padding.all+"px";
      else{
        var p=s.padding;
        out.padding=((p.top||0))+"px "+((p.end||p.right||0))+"px "+((p.bottom||0))+"px "+((p.start||p.left||0))+"px";
      }
    }
    if(s.margin&&s.margin.all)out.margin=s.margin.all+"px";
    if(s.width&&s.width.kind==="fixed")out.width=s.width.dp+"px";
    if(s.height&&s.height.kind==="fixed")out.height=s.height.dp+"px";
    if(s.maxWidth)out.maxWidth=s.maxWidth+"px";
    if(s.maxHeight)out.maxHeight=s.maxHeight+"px";
    if(s.opacity)out.opacity=s.opacity;
    if(s.shadowElevation)out.boxShadow="0 "+(s.shadowElevation)+"px "+(s.shadowElevation*2)+"px rgba(0,0,0,.2)";
    if(s.visible===false)out.display="none";
    if(s.align==="center")out.justifyContent="center";
    if(s.align==="end")out.justifyContent="flex-end";
    return out;
  }
  function uiApplyStyle(nodeEl,node){
    var st=uiStyleOf(node);
    Object.keys(st).forEach(function(k){nodeEl.style[k]=st[k];});
  }
  /* 把服务端发来的「顶层」布局字段落到元素上。uiStyleOf 只认 node.style，而
     column/row/box/card 的 spacing/padding/cornerRadius/weight/horizontalAlign/
     verticalAlign 是**顶层**字段，漏应用 = 列无间距、卡片不圆角、对齐失效——
     「设计乱七八糟」的真因之一。 */
  function applyLayout(nodeEl,node){
    if(node.spacing)nodeEl.style.gap=node.spacing+"px";
    if(node.padding!==undefined&&node.padding!==null&&!nodeEl.style.padding)nodeEl.style.padding=node.padding+"px";
    if(node.cornerRadius!==undefined&&node.cornerRadius!==null&&!nodeEl.style.borderRadius)nodeEl.style.borderRadius=node.cornerRadius+"px";
    if(node.weight!==undefined&&node.weight!==null)nodeEl.style.flex=String(node.weight);
    var ha=node.horizontalAlign,va=node.verticalAlign;
    if(node.type==="row"){
      if(ha==="center")nodeEl.style.justifyContent="center";
      else if(ha==="end")nodeEl.style.justifyContent="flex-end";
      else if(ha==="start")nodeEl.style.justifyContent="flex-start";
      if(va==="center")nodeEl.style.alignItems="center";
      else if(va==="end")nodeEl.style.alignItems="flex-end";
    }else{
      if(ha==="center")nodeEl.style.alignItems="center";
      else if(ha==="end")nodeEl.style.alignItems="flex-end";
      else if(ha==="start")nodeEl.style.alignItems="flex-start";
      if(va==="center")nodeEl.style.justifyContent="center";
      else if(va==="end")nodeEl.style.justifyContent="flex-end";
    }
  }
  function uiActionClick(node){
    var a=node.action;
    if(!a)return;
    switch(a.type){
      case "callback":
        postCard({ui:{event:a.event||"submit",data:a.data||{},collectFrom:a.collectFrom||[]}});
        break;
      case "tool_call":
        postCard({ui:{tool:a.tool,arguments:a.arguments||{}}});
        break;
      case "skill":
        postCard({ui:{skill:a.skill,input:a.input||""}});
        break;
      case "open_url":
        postCard({ui:{openUrl:a.url}});
        break;
      case "copy":
        postCard({ui:{copy:a.text}});
        break;
      case "toggle":
        if(a.targetId){
          var t=document.getElementById(a.targetId);
          if(t)t.style.display=(t.style.display==="none")?"":"none";
        }
        break;
      case "open_app":
        postCard({ui:{openApp:a.packageName}});
        break;
      case "open_screen":
        postCard({ui:{openScreen:a.target}});
        break;
      case "render_html":
        showHtmlModal(a.html);
        break;
      case "render_vispro":
        postCard({ui:{renderVispro:a.source}});
        break;
      case "visual_popup":
        showVisualPopup(a.title, a.content);
        break;
      case "visual_ask":
        showVisualAsk(a.prompt, a.options||[], function(opt){
          var inp=document.getElementById("inp");
          if(inp){ inp.value=opt; if(typeof doSend==="function")doSend(); }
        });
        break;
      case "zx_web_card":
        postCard({ui:{zxWebCard:{component:a.component,title:a.title,dsl:a.dsl}}});
        break;
    }
  }

  /* 可视化弹窗 / 可视化询问 / HTML 预览：web 对等客户端直接渲染（不再仅转发手机） */
  function closeViz(){
    var ex=document.getElementById("vizOverlay");
    if(ex&&ex.parentNode)ex.parentNode.removeChild(ex);
  }
  function showVisualPopup(title, content){
    closeViz();
    var ov=document.createElement("div");
    ov.className="viz-overlay"; ov.id="vizOverlay";
    var box=document.createElement("div"); box.className="viz-box";
    var h=document.createElement("div"); h.className="viz-h";
    var t=document.createElement("span"); t.textContent=title||"提示"; h.appendChild(t);
    var x=document.createElement("button"); x.className="x"; x.type="button"; x.textContent="×"; x.setAttribute("aria-label","关闭");
    x.addEventListener("click",closeViz); h.appendChild(x); box.appendChild(h);
    var b=document.createElement("div"); b.className="viz-b";
    var p=document.createElement("div"); p.textContent=content||""; b.appendChild(p);
    box.appendChild(b); ov.appendChild(box);
    ov.addEventListener("click",function(e){ if(e.target===ov)closeViz(); });
    document.body.appendChild(ov);
  }
  function showVisualAsk(prompt, options, onPick){
    closeViz();
    var ov=document.createElement("div"); ov.className="viz-overlay"; ov.id="vizOverlay";
    var box=document.createElement("div"); box.className="viz-box";
    var h=document.createElement("div"); h.className="viz-h";
    var t=document.createElement("span"); t.textContent="请选择"; h.appendChild(t);
    var x=document.createElement("button"); x.className="x"; x.type="button"; x.textContent="×"; x.setAttribute("aria-label","关闭");
    x.addEventListener("click",closeViz); h.appendChild(x); box.appendChild(h);
    if(prompt){ var b=document.createElement("div"); b.className="viz-b"; var pp=document.createElement("div"); pp.textContent=prompt; b.appendChild(pp); box.appendChild(b); }
    var f=document.createElement("div"); f.className="viz-f";
    (options||[]).forEach(function(opt){
      if(!opt)return;
      var btn=document.createElement("button"); btn.type="button"; btn.textContent=opt;
      btn.addEventListener("click",function(){ closeViz(); if(onPick)onPick(opt); });
      f.appendChild(btn);
    });
    box.appendChild(f); ov.appendChild(box);
    ov.addEventListener("click",function(e){ if(e.target===ov)closeViz(); });
    document.body.appendChild(ov);
  }
  function showHtmlModal(html){
    closeViz();
    var ov=document.createElement("div"); ov.className="viz-overlay"; ov.id="vizOverlay";
    var box=document.createElement("div"); box.className="viz-box"; box.style.maxWidth="min(94vw,720px)";
    var h=document.createElement("div"); h.className="viz-h";
    var t=document.createElement("span"); t.textContent="HTML 预览"; h.appendChild(t);
    var x=document.createElement("button"); x.className="x"; x.type="button"; x.textContent="×"; x.setAttribute("aria-label","关闭");
    x.addEventListener("click",closeViz); h.appendChild(x); box.appendChild(h);
    var b=document.createElement("div"); b.className="viz-b"; b.style.padding="0";
    var frame=document.createElement("iframe"); frame.className="viz-iframe";
    frame.setAttribute("sandbox","allow-scripts allow-same-origin");
    frame.srcdoc=html||"";
    b.appendChild(frame); box.appendChild(b); ov.appendChild(box);
    ov.addEventListener("click",function(e){ if(e.target===ov)closeViz(); });
    document.body.appendChild(ov);
  }
  function uiTextInput(node,state){
    var box=div("dui-field");
    if(node.label)box.appendChild(div("dui-label",node.label));
    if(node.multiline){
      var ta=el("textarea","dui-input dui-area");
      ta.placeholder=node.placeholder||"";
      ta.value=node.value||"";
      ta.rows=node.lines||3;
      ta.addEventListener("input",function(){state.values[node.id||"__inp"]=ta.value;});
      box.appendChild(ta);
    }else{
      var inp=el("input","dui-input");
      inp.type=node.inputType==="password"?"password":"text";
      inp.placeholder=node.placeholder||"";
      inp.value=node.value||"";
      inp.addEventListener("input",function(){state.values[node.id||"__inp"]=inp.value;});
      box.appendChild(inp);
    }
    return box;
  }
  function uiNode(node,state,parentEl){
    if(!node||!node.type)return;
    var id=node.id;
    var nodeEl;
    switch(node.type){
      case "column": case "row": case "box":
        nodeEl=div("dui-"+node.type);
        uiApplyStyle(nodeEl,node);
        applyLayout(nodeEl,node);
        if(node.scrollable)nodeEl.classList.add("scroll");
        (node.children||[]).forEach(function(ch){uiNode(ch,state,nodeEl);});
        break;
      case "card": case "pane":
        nodeEl=div("dui-card");
        uiApplyStyle(nodeEl,node);
        applyLayout(nodeEl,node);
        if(node.title)nodeEl.appendChild(div("dui-card-title",node.title));
        if(node.onClick){
          nodeEl.style.cursor="pointer";
          nodeEl.addEventListener("click",function(){uiActionClick({action:node.onClick});});
        }
        (node.children||[]).forEach(function(ch){uiNode(ch,state,nodeEl);});
        break;
      case "text": {
        nodeEl=div("dui-text");
        uiApplyStyle(nodeEl,node);
        if(node.typography==="title")nodeEl.classList.add("title");
        else if(node.typography==="headline")nodeEl.classList.add("headline");
        else if(node.typography==="caption")nodeEl.classList.add("caption");
        if(node.bold)nodeEl.classList.add("bold");
        if(node.italic)nodeEl.classList.add("italic");
        if(node.color)nodeEl.style.color=colorOf(node.color,nodeEl.style.color);
        nodeEl.textContent=uiText(node.value);
        break;
      }
      case "markdown": {
        nodeEl=div("dui-markdown");
        uiApplyStyle(nodeEl,node);
        var md=el("div","dui-md");
        md.innerHTML=inlineMarkdown(node.value||"");
        nodeEl.appendChild(md);
        break;
      }
      case "image": {
        nodeEl=div("dui-image");
        uiApplyStyle(nodeEl,node);
        var img=el("img");
        img.src=node.url||"";
        img.alt=node.alt||"";
        if(node.aspectRatio)img.style.aspectRatio=node.aspectRatio;
        if(node.cornerRadius)img.style.borderRadius=node.cornerRadius+"px";
        /* 服务端 QuroImageNode.height 是 Int?（纯数字），也可能带 uiSize 对象；两者都兼容 */
        if(node.height){
          if(node.height.kind==="fixed")img.style.height=node.height.dp+"px";
          else if(typeof node.height==="number")img.style.height=node.height+"px";
        }
        img.onerror=function(){img.replaceWith(div("dui-image-empty","图片加载失败"));};
        nodeEl.appendChild(img);
        break;
      }
      case "icon": {
        nodeEl=div("dui-icon");
        uiApplyStyle(nodeEl,node);
        nodeEl.textContent=node.name||"";
        if(node.size)nodeEl.style.fontSize=node.size+"px";
        if(node.tint)nodeEl.style.color=colorOf(node.tint);
        break;
      }
      case "badge": {
        nodeEl=el("span","dui-badge");
        uiApplyStyle(nodeEl,node);
        nodeEl.textContent=node.text||"";
        if(node.color)nodeEl.style.color=colorOf(node.color);
        if(node.background)nodeEl.style.background=colorOf(node.background,"var(--bg2)");
        break;
      }
      case "progress": {
        nodeEl=div("dui-progress");
        uiApplyStyle(nodeEl,node);
        var bar=div("dui-progress-bar");
        var fill=div("dui-progress-fill");
        fill.style.width=Math.max(0,Math.min(100,node.progress||0))+"%";
        bar.appendChild(fill);
        nodeEl.appendChild(bar);
        if(node.label)nodeEl.appendChild(div("dui-progress-label",node.label));
        break;
      }
      case "divider": {
        nodeEl=el("hr","dui-divider");
        uiApplyStyle(nodeEl,node);
        if(node.thickness)nodeEl.style.borderTopWidth=node.thickness+"px";
        break;
      }
      case "spacer": {
        nodeEl=div("dui-spacer");
        if(node.height)nodeEl.style.height=node.height+"px";
        if(node.width)nodeEl.style.width=node.width+"px";
        break;
      }
      case "video": {
        nodeEl=div("dui-video");
        uiApplyStyle(nodeEl,node);
        var v=el("video","dui-media");
        v.controls=true;v.src=node.url||"";
        if(node.title)nodeEl.appendChild(div("dui-media-title",node.title));
        nodeEl.appendChild(v);
        break;
      }
      case "audio": {
        nodeEl=div("dui-audio");
        uiApplyStyle(nodeEl,node);
        var a=el("audio","dui-media");
        a.controls=true;a.src=node.url||"";
        if(node.title)nodeEl.appendChild(div("dui-media-title",node.title));
        nodeEl.appendChild(a);
        break;
      }
      case "browser": {
        nodeEl=div("dui-browser");
        uiApplyStyle(nodeEl,node);
        var f=el("iframe","dui-browser-frame");
        f.src=node.url||"";
        if(node.height)f.style.height=node.height+"px";
        nodeEl.appendChild(f);
        break;
      }
      case "html": {
        nodeEl=div("dui-html");
        uiApplyStyle(nodeEl,node);
        var frame=el("iframe","dui-html-frame");
        frame.setAttribute("srcdoc",node.html||"");
        frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups");
        if(node.height)frame.style.height=node.height+"px";
        nodeEl.appendChild(frame);
        break;
      }
      case "code": {
        nodeEl=div("dui-code");
        uiApplyStyle(nodeEl,node);
        if(node.title)nodeEl.appendChild(div("dui-code-title",node.title));
        var pre=el("pre");
        pre.textContent=node.code||"";
        nodeEl.appendChild(pre);
        if(node.runnable){
          var run=cmdButton("运行",null,"ghost");
          run.addEventListener("click",function(){
            postCard({ui:{tool:"run_code",arguments:{code:node.code||"",lang:node.lang||""}}});
          });
          nodeEl.appendChild(run);
        }
        break;
      }
      case "button": {
        nodeEl=el("button","dui-button"+(node.variant==="outlined"||node.variant==="text"?" ghost":""));
        uiApplyStyle(nodeEl,node);
        nodeEl.type="button";
        if(node.icon)nodeEl.appendChild(el("span","dui-btn-icon",node.icon));
        nodeEl.appendChild(document.createTextNode(node.label||""));
        if(node.enabled===false)nodeEl.disabled=true;
        nodeEl.addEventListener("click",function(){uiActionClick(node);});
        break;
      }
      case "text_input": {
        nodeEl=uiTextInput(node,state);
        uiApplyStyle(nodeEl,node);
        break;
      }
      case "checkbox": {
        nodeEl=div("dui-check");
        uiApplyStyle(nodeEl,node);
        var cb=el("input","dui-checkbox");
        cb.type="checkbox";cb.checked=!!node.checked;
        cb.addEventListener("change",function(){state.values[node.id||"__cb"]=String(cb.checked);});
        nodeEl.appendChild(cb);
        nodeEl.appendChild(div("dui-label",node.label||""));
        break;
      }
      case "switch": {
        nodeEl=el("label","dui-switch");
        uiApplyStyle(nodeEl,node);
        var sw=el("input","dui-switch-input");
        sw.type="checkbox";sw.checked=!!node.checked;
        sw.addEventListener("change",function(){state.values[node.id||"__sw"]=String(sw.checked);});
        nodeEl.appendChild(sw);
        var track=div("dui-switch-track");
        var thumb=div("dui-switch-thumb");
        track.appendChild(thumb);
        nodeEl.appendChild(track);
        nodeEl.appendChild(div("dui-label",node.label||""));
        break;
      }
      case "select": {
        nodeEl=div("dui-field");
        uiApplyStyle(nodeEl,node);
        if(node.label)nodeEl.appendChild(div("dui-label",node.label));
        var sel=el("select","dui-input");
        (node.options||[]).forEach(function(opt){
          var o=el("option",null,opt);
          o.value=opt;
          if(opt===node.selected)o.selected=true;
          sel.appendChild(o);
        });
        sel.addEventListener("change",function(){state.values[node.id||"__sel"]=sel.value;});
        nodeEl.appendChild(sel);
        break;
      }
      case "slider": {
        nodeEl=div("dui-field");
        uiApplyStyle(nodeEl,node);
        if(node.label)nodeEl.appendChild(div("dui-label",node.label));
        var range=el("input","dui-range");
        range.type="range";
        range.min=node.min||0;range.max=node.max||100;range.step=node.step||1;
        range.value=node.value||0;
        range.addEventListener("change",function(){state.values[node.id||"__sl"]=range.value;});
        nodeEl.appendChild(range);
        break;
      }
      case "list": {
        nodeEl=div("dui-list");
        uiApplyStyle(nodeEl,node);
        if(node.maxHeight)nodeEl.style.maxHeight=node.maxHeight+"px";
        (node.items||[]).forEach(function(it){
          var item=div("dui-list-item",it);
          if(node.itemTemplate){
            var tmpl=node.itemTemplate;
            (tmpl.children||[]).forEach(function(ch){uiNode(ch,state,item);});
          }
          if(node.onClick){
            item.addEventListener("click",function(){uiActionClick({action:node.onClick});});
          }
          nodeEl.appendChild(item);
        });
        break;
      }
      case "tabs": {
        nodeEl=div("dui-tabs");
        uiApplyStyle(nodeEl,node);
        var heads=div("dui-tabs-head");
        var bodies=div("dui-tabs-body");
        (node.tabs||[]).forEach(function(tab,i){
          var hb=el("button","dui-tab");
          hb.type="button";hb.textContent=tab.title||"";
          hb.addEventListener("click",function(){
            var hs=heads.children;
            for(var j=0;j<hs.length;j++)hs[j].classList.toggle("on",j===i);
            var bs=bodies.children;
            for(var j=0;j<bs.length;j++)bs[j].hidden=j!==i;
          });
          heads.appendChild(hb);
          var pane=div("dui-tab-pane");
          if(tab.node)uiNode(tab.node,state,pane);
          bodies.appendChild(pane);
        });
        nodeEl.appendChild(heads);nodeEl.appendChild(bodies);
        break;
      }
      case "chips": {
        nodeEl=div("dui-chips");
        uiApplyStyle(nodeEl,node);
        var sel=(node.selected||[]).slice();
        (node.items||[]).forEach(function(chip){
          var c=el("button","dui-chip"+(sel.indexOf(chip)>=0?" on":""));
          c.type="button";c.textContent=chip;
          c.addEventListener("click",function(){
            var idx=sel.indexOf(chip);
            if(idx>=0)sel.splice(idx,1);else sel.push(chip);
            c.classList.toggle("on");
            if(node.onSelect)uiActionClick({action:node.onSelect});
          });
          nodeEl.appendChild(c);
        });
        break;
      }
      case "mermaid": {
        nodeEl=div("dui-mermaid");
        uiApplyStyle(nodeEl,node);
        renderMermaid(nodeEl,{source:node.source||""},document.documentElement.classList.contains("dark"));
        break;
      }
      case "stat": {
        nodeEl=div("qstat");
        uiApplyStyle(nodeEl,node);
        if(node.label)nodeEl.appendChild(div("qstat-label",node.label));
        nodeEl.appendChild(div("qstat-value",uiText(node.value)+(node.unit||"")));
        if(node.delta)nodeEl.appendChild(div("qstat-delta "+(String(node.trend||"flat").toLowerCase()==="up"?"up":"down"),node.delta));
        break;
      }
      case "table": {
        nodeEl=div("qtable-wrap");
        uiApplyStyle(nodeEl,node);
        var t=el("table","qtable");
        var thead=el("thead"),tr=el("tr");
        (node.headers||[]).forEach(function(h){var th=el("th",null,h);tr.appendChild(th);});
        thead.appendChild(tr);t.appendChild(thead);
        var tb=el("tbody");
        (node.rows||[]).forEach(function(r){
          var rtr=el("tr");
          (r||[]).forEach(function(c){var td=el("td",null,c);rtr.appendChild(td);});
          tb.appendChild(rtr);
        });
        t.appendChild(tb);nodeEl.appendChild(t);
        if(node.caption)nodeEl.appendChild(div("dui-caption",node.caption));
        break;
      }
      case "alert": {
        nodeEl=div("qalert "+(String(node.severity||"info").toLowerCase()));
        uiApplyStyle(nodeEl,node);
        if(node.title)nodeEl.appendChild(div("qalert-title",node.title));
        nodeEl.appendChild(div("qalert-text",node.text||""));
        break;
      }
      case "rating": {
        nodeEl=div("qrating");
        uiApplyStyle(nodeEl,node);
        if(node.label)nodeEl.appendChild(div("qslider-label",node.label));
        var stars=div("qstars");
        var max=node.max||5,val=node.value||0;
        for(var i=1;i<=max;i++){
          (function(idx){
            var s=el("span","qstar"+(i<=val?" on":""),i<=val?"★":"☆");
            s.addEventListener("click",function(){
              if(node.onChange)uiActionClick({action:node.onChange});
            });
            stars.appendChild(s);
          })(i);
        }
        nodeEl.appendChild(stars);
        break;
      }
      case "gauge": {
        nodeEl=div("qgauge");
        uiApplyStyle(nodeEl,node);
        var c=div("qgauge-ring");
        ringChart(c,node.progress||0,100,colorOf(node.color,"#2E6BE6"),(node.label||""));
        nodeEl.appendChild(c);
        break;
      }
      case "countdown": {
        nodeEl=div("qcountdown");
        uiApplyStyle(nodeEl,node);
        var t=div("qcountdown-val");
        nodeEl.appendChild(t);
        function tick(){
          var remain=Math.max(0,(node.targetEpochMs||0)-Date.now());
          var sec=Math.floor(remain/1000);
          t.textContent=pad2(Math.floor(sec/60))+":"+pad2(sec%60);
        }
        tick();setInterval(tick,1000);
        break;
      }
      case "steps": {
        nodeEl=div("qsteps");
        uiApplyStyle(nodeEl,node);
        (node.steps||[]).forEach(function(st,i){
          var item=div("qstep"+(i<node.current?" done":(i===node.current?" cur":"")));
          item.appendChild(div("qstep-dot",i<node.current?"✓":String(i+1)));
          item.appendChild(div("qstep-title",st||""));
          nodeEl.appendChild(item);
        });
        break;
      }
      case "timeline": {
        nodeEl=div("qtimeline");
        uiApplyStyle(nodeEl,node);
        (node.events||[]).forEach(function(ev){
          var item=div("qtl-item");
          item.appendChild(el("span","qtl-dot"));
          item.appendChild(div("qtl-body",ev||""));
          nodeEl.appendChild(item);
        });
        break;
      }
      case "todo": {
        nodeEl=div("qcard-list");
        uiApplyStyle(nodeEl,node);
        (node.items||[]).forEach(function(it){
          var row=div("qcheck-row");
          row.appendChild(el("span",null,"• "+it));
          nodeEl.appendChild(row);
        });
        break;
      }
      case "expandable": {
        nodeEl=div("qexp");
        uiApplyStyle(nodeEl,node);
        var h=el("button","qexp-head");
        h.type="button";
        h.appendChild(div("qexp-title",node.title||""));
        var arrow=el("span","qexp-arrow","▸");
        h.appendChild(arrow);
        var body=div("qexp-body");
        if(node.body)body.appendChild(aipParagraph(node.body));
        if(node.node)uiNode(node.node,state,body);
        h.addEventListener("click",function(){
          var open=nodeEl.classList.toggle("open");
          arrow.textContent=open?"▾":"▸";
        });
        nodeEl.appendChild(h);nodeEl.appendChild(body);
        break;
      }
      case "pie": {
        nodeEl=div("qpie");
        uiApplyStyle(nodeEl,node);
        var c=canvasBox(150);nodeEl.appendChild(c.box);
        var segs=(node.segments||[]).map(function(s){return {name:s,value:1};});
        drawPie(c.cv,segs,false);
        break;
      }
      case "compare": {
        nodeEl=div("qcompare");
        uiApplyStyle(nodeEl,node);
        (node.items||[]).forEach(function(it){
          nodeEl.appendChild(div("qcompare-point",it));
        });
        break;
      }
      case "radar": {
        nodeEl=div("qchart-card");
        uiApplyStyle(nodeEl,node);
        var c=canvasBox(160);nodeEl.appendChild(c.box);
        var axes=(node.axes||[]).map(function(a){return {name:a,value:1};});
        drawRadar(c.cv,axes);
        break;
      }
      case "heatmap": {
        nodeEl=div("qheat");
        uiApplyStyle(nodeEl,node);
        var grid=div("qheat-grid");
        (node.cells||[]).forEach(function(v){
          var cell=div("qheat-cell");
          cell.style.background="rgba(46,107,230,.4)";
          grid.appendChild(cell);
        });
        nodeEl.appendChild(grid);
        break;
      }
      case "kanban": {
        nodeEl=div("qkanban");
        uiApplyStyle(nodeEl,node);
        (node.columns||[]).forEach(function(col){
          var c=div("qkanban-col");
          c.appendChild(div("qkanban-col-name",col));
          nodeEl.appendChild(c);
        });
        break;
      }
      case "carousel": {
        nodeEl=div("qcarousel");
        uiApplyStyle(nodeEl,node);
        var viewport=div("qcarousel-viewport");
        var track=div("qcarousel-track");
        (node.slides||[]).forEach(function(sl){
          var item=div("qcarousel-slide");
          if(sl.color)item.style.background=sl.color;
          if(sl.title)item.appendChild(div("qcarousel-slide-title",sl.title));
          if(sl.body)item.appendChild(div("qcarousel-slide-body",sl.body));
          if(sl.node)uiNode(sl.node,state,item);
          track.appendChild(item);
        });
        viewport.appendChild(track);
        nodeEl.appendChild(viewport);
        break;
      }
      case "timer": {
        nodeEl=div("qcountdown");
        uiApplyStyle(nodeEl,node);
        nodeEl.appendChild(div("qcountdown-val",(node.label||"")+" "+(node.seconds||0)+"s"));
        break;
      }
      case "tagcloud": {
        nodeEl=div("qtagcloud");
        uiApplyStyle(nodeEl,node);
        (node.tags||[]).forEach(function(t){
          nodeEl.appendChild(el("span","qtag",t));
        });
        break;
      }
      case "avatargroup": {
        nodeEl=div("qavatars");
        uiApplyStyle(nodeEl,node);
        (node.avatars||[]).forEach(function(a){
          nodeEl.appendChild(div("qavatar",(a||"?").charAt(0)));
        });
        break;
      }
      case "counter": {
        nodeEl=renderCounterCard({label:node.label||"",value:node.value||0,min:node.min||0,max:node.max||100,step:node.step||1,unit:node.unit||"",command:null});
        uiApplyStyle(nodeEl,node);
        break;
      }
      case "breadcrumb": {
        nodeEl=div("qcrumbs");
        uiApplyStyle(nodeEl,node);
        (node.crumbs||[]).forEach(function(cr){
          nodeEl.appendChild(el("span","qcrumb",cr));
        });
        break;
      }
      case "color": {
        nodeEl=div("qcolors");
        uiApplyStyle(nodeEl,node);
        /* 服务端序列化 QuroColorNode 用单值字段 color+name；兼容旧契约的 colors 数组 */
        var c=node.color||(node.colors&&node.colors[0])||"";
        if(c){
          var sw=div("qcolor");
          sw.style.background=c;
          nodeEl.appendChild(sw);
        }
        if(node.name)nodeEl.appendChild(div("qcolor-name",node.name));
        break;
      }
      case "media": {
        nodeEl=renderMediaCard({mediaType:node.mediaType||"",mediaUrl:node.url||"",title:node.title||""});
        uiApplyStyle(nodeEl,node);
        break;
      }
      case "form": {
        nodeEl=div("qform");
        uiApplyStyle(nodeEl,node);
        if(node.title)nodeEl.appendChild(div("qform-title",node.title));
        var fields={};
        (node.fields||[]).forEach(function(f){
          var row=div("qform-field");
          row.appendChild(div("qform-label",f));
          var inp=el("input","qform-input");
          fields[f]=inp;
          row.appendChild(inp);
          nodeEl.appendChild(row);
        });
        var btn=el("button","dui-button");
        btn.type="button";btn.textContent="提交";
        btn.addEventListener("click",function(){
          var values={};
          Object.keys(fields).forEach(function(k){values[k]=fields[k].value;});
          postCard({ui:{event:"submit",data:values}});
        });
        nodeEl.appendChild(btn);
        break;
      }
      case "unknown": {
        nodeEl=div("dui-unknown");
        uiApplyStyle(nodeEl,node);
        var fields=node.fields||{};
        var txt=fields.value||fields.text||node.unknownType||"";
        nodeEl.appendChild(div("dui-unknown-text",uiText(txt)));
        if(node.action){
          var b=el("button","dui-button");
          b.type="button";b.textContent="确定";
          b.addEventListener("click",function(){uiActionClick(node);});
          nodeEl.appendChild(b);
        }
        break;
      }
      case "gameboard": {
        nodeEl=div("dui-gameboard");
        uiApplyStyle(nodeEl,node);
        var rows=node.rows||1,cols=node.cols||1;
        var grid=div("dui-gameboard-grid");
        grid.style.gridTemplateColumns="repeat("+cols+",40px)";
        for(var r=0;r<rows;r++){
          for(var c=0;c<cols;c++){
            var idx=r*cols+c;
            (function(rr,cc){
              var cell=el("button","dui-gb-cell");
              cell.type="button";
              cell.textContent=(node.values&&node.values[idx])||"";
              if(node.cellColors&&node.cellColors[idx])cell.style.background=node.cellColors[idx];
              if(node.clickable!==false){
                cell.addEventListener("click",function(){
                  postCard({ui:{event:"game_move",data:{row:String(rr),col:String(cc),value:cell.textContent}}});
                });
              }
              grid.appendChild(cell);
            })(r,c);
          }
        }
        nodeEl.appendChild(grid);
        break;
      }
      case "genui": {
        nodeEl=div("dui-genui");
        uiApplyStyle(nodeEl,node);
        var frame=el("iframe","dui-genui-frame");
        frame.setAttribute("srcdoc",node.content||"");
        frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups");
        frame.style.height="420px";
        nodeEl.appendChild(frame);
        break;
      }
      default: {
        nodeEl=div("dui-unknown");
        uiApplyStyle(nodeEl,node);
        nodeEl.appendChild(el("pre","qcard-raw",JSON.stringify(node,null,2)));
        break;
      }
    }
    if(nodeEl&&id)nodeEl.id=id;
    if(nodeEl&&parentEl)parentEl.appendChild(nodeEl);
  }

  /** 动态 UI 渲染入口：data.ok=false 显示降级。 */
  function renderDynamicUi(container,data){
    if(!data||!data.ok){
      container.appendChild(div("aip-degraded","动态 UI 解析失败"));
      if(data&&data.reason)container.appendChild(div("aip-degraded-reason",data.reason));
      if(data&&data.raw)container.appendChild(el("pre","aip-degraded-raw",data.raw));
      return;
    }
    var state={values:{}};
    var root=div("dui-root");
    uiNode(data.root,state,root);
    container.appendChild(root);
  }/* ── 自研卡片 CardSpec ───────────────────────────────────────────── */
  function styleTokenToCss(s){
    var out={};
    if(!s)return out;
    if(s.bg)out.background=colorOf(s.bg,"var(--bg2)");
    if(s.fg)out.color=colorOf(s.fg);
    if(s.accent)out.borderColor=colorOf(s.accent);
    if(s.cornerDp)out.borderRadius=s.cornerDp+"px";
    if(s.paddingDp)out.padding=s.paddingDp+"px";
    if(s.fontSizeSp)out.fontSize=s.fontSizeSp+"px";
    if(s.fontWeight)out.fontWeight=s.fontWeight;
    return out;
  }
  function applyCss(nodeEl,css){
    Object.keys(css).forEach(function(k){nodeEl.style[k]=css[k];});
  }
  function renderLayoutNode(n,state,parentEl){
    if(!n||!n.type)return;
    var nodeEl;
    var css=styleTokenToCss(n.style);
    switch(n.type){
      case "column": case "row": case "box":
        nodeEl=div("dui-"+n.type);
        applyCss(nodeEl,css);
        if(n.weight)nodeEl.style.flex=String(n.weight);
        if(n.widthDp)nodeEl.style.width=n.widthDp+"px";
        if(n.heightDp)nodeEl.style.height=n.heightDp+"px";
        (n.children||[]).forEach(function(ch){renderLayoutNode(ch,state,nodeEl);});
        break;
      case "text": {
        nodeEl=div("dui-text");
        applyCss(nodeEl,css);
        nodeEl.textContent=uiText(n.props&&(n.props.text!==undefined?n.props.text:n.props.value));
        break;
      }
      case "image": {
        nodeEl=div("dui-image");
        applyCss(nodeEl,css);
        var img=el("img");
        img.src=(n.props&&n.props.url)||"";
        img.onerror=function(){img.replaceWith(div("dui-image-empty","图片加载失败"));};
        nodeEl.appendChild(img);
        break;
      }
      case "button": {
        nodeEl=el("button","dui-button");
        applyCss(nodeEl,css);
        nodeEl.type="button";
        nodeEl.textContent=(n.props&&n.props.label)||"";
        nodeEl.addEventListener("click",function(){
          var action=(n.props&&n.props.action)||null;
          if(action)selfCardAction(action,state);
        });
        break;
      }
      case "spacer":
        nodeEl=div("dui-spacer");
        if(n.heightDp)nodeEl.style.height=n.heightDp+"px";
        break;
      default: {
        nodeEl=div("dui-unknown");
        applyCss(nodeEl,css);
        nodeEl.appendChild(el("pre","qcard-raw",JSON.stringify(n,null,2)));
        break;
      }
    }
    if(nodeEl&&parentEl)parentEl.appendChild(nodeEl);
  }
  function selfCardAction(action,state){
    if(!action)return;
    switch(String(action.type||"").toLowerCase()){
      case "reply":
        postCard({cmd:"reply:"+(action.name||"")});
        break;
      case "ai":
        postCard({cmd:"ai:"+(action.name||"")});
        break;
      case "ui":
        postCard({cmd:"ui_"+(action.name||"")});
        break;
      case "run":
        postCard({cmd:"run:"+(action.name||"")});
        break;
      case "open":
        postCard({cmd:"open:"+(action.url||action.name||"")});
        break;
      case "copy":
        postCard({cmd:"copy:"+(action.name||"")});
        break;
      case "screen":
        postCard({cmd:"screen:"+(action.name||"")});
        break;
      case "tool_call":
        postCard({ui:{tool:action.name||"",arguments:action.payload||{}}});
        break;
      case "skill":
        postCard({ui:{skill:action.name||"",input:""}});
        break;
      default:
        if(action.payload&&action.payload.cmd)postCard({cmd:action.payload.cmd});
        else if(action.name)postCard({cmd:"reply:"+action.name});
    }
  }
  function renderSelfData(d,state){
    var box=div("qcard-inner");
    if(!d)return box;
    switch(String(d.kind||"").toLowerCase()){
      case "chart": {
        var c=canvasBox(160);
        box.appendChild(c.box);
        var labels=(d.series||[]).map(function(s){return s.name||"";});
        var series=[{name:"",values:(d.series||[]).map(function(s){return (s.points&&s.points[0])||0;})}];
        drawBars(c.cv,labels,series);
        break;
      }
      case "media": {
        if(d.code){
          var pre=el("pre","qcard-raw");
          pre.textContent=d.code;
          box.appendChild(pre);
        }
        (d.images||[]).forEach(function(img){
          var im=el("img","qmedia-img");
          im.src=img;
          im.onerror=function(){im.remove();};
          box.appendChild(im);
        });
        (d.items||[]).forEach(function(it){
          var row=div("qcheck-row");
          var cb=el("input","qcheck");
          cb.type="checkbox";cb.checked=!!it.done;
          row.appendChild(cb);
          var span=el("span",null,it.text||"");
          if(it.done)span.classList.add("done");
          row.appendChild(span);
          box.appendChild(row);
        });
        break;
      }
      case "form": {
        (d.fields||[]).forEach(function(f){
          var row=div("qform-field");
          if(f.label)row.appendChild(div("qform-label",f.label));
          var inp=el("input","qform-input");
          inp.placeholder=f.placeholder||"";
          inp.value=f.value||"";
          row.appendChild(inp);
          box.appendChild(row);
        });
        (d.buttons||[]).forEach(function(b){
          var btn=el("button","qbtn");
          btn.type="button";
          btn.textContent=b.label||"";
          btn.addEventListener("click",function(){
            if(b.action)selfCardAction(b.action,state);
          });
          box.appendChild(btn);
        });
        break;
      }
      case "status": {
        var box2=div("qalert "+(String(d.statusType||"info").toLowerCase()));
        box2.appendChild(div("qalert-text",d.text||""));
        if(d.progress!==undefined&&d.progress!==null){
          var bar=div("qprogress-bar");
          var fill=div("qprogress-fill");
          fill.style.width=Math.max(0,Math.min(100,d.progress))+"%";
          bar.appendChild(fill);
          box2.appendChild(bar);
        }
        box.appendChild(box2);
        break;
      }
      default:
        box.appendChild(el("pre","qcard-raw",JSON.stringify(d,null,2)));
    }
    return box;
  }
  /** 自研卡片渲染入口：data.ok=false 显示降级。 */
  function renderSelfCard(container,data){
    if(!data||!data.ok){
      container.appendChild(div("aip-degraded","自研卡片解析失败"));
      if(data&&data.raw)container.appendChild(el("pre","aip-degraded-raw",data.raw));
      return;
    }
    var state={values:{}};
    var wrap=div("qcard");
    if(data.id)wrap.setAttribute("data-card-id",data.id);
    if(data.layout){
      var layoutRoot=div("dui-root");
      renderLayoutNode(data.layout,state,layoutRoot);
      wrap.appendChild(layoutRoot);
    }
    if(data.data){
      var dataBox=div("qcard-data");
      dataBox.appendChild(renderSelfData(data.data,state));
      wrap.appendChild(dataBox);
    }
    if(data.actions&&data.actions.length){
      var actions=div("qactions");
      data.actions.forEach(function(a){
        var b=el("button","qbtn");
        b.type="button";
        b.textContent=(a.name||a.label||"动作");
        b.addEventListener("click",function(){selfCardAction(a,state);});
        actions.appendChild(b);
      });
      wrap.appendChild(actions);
    }
    container.appendChild(wrap);
  }

  /* ── MiniApp 渲染 ───────────────────────────────────────────────── */
  function renderMiniApp(container,data){
    if(!data||!data.html){
      container.appendChild(div("aip-degraded","MiniApp 内容为空"));
      return;
    }
    var box=div("qhtml");
    var frame=el("iframe","qhtml-frame miniapp");
    frame.setAttribute("srcdoc",data.html);
    frame.setAttribute("sandbox","allow-scripts allow-same-origin allow-forms allow-popups allow-modals");
    frame.style.height="520px";
    box.appendChild(frame);
    container.appendChild(box);
  }

  /* ── 导出（全部真渲染器就绪）───────────────────────────────── */
  window.WebRenderers={
    renderMermaid:renderMermaid,
    renderAip:renderAip,
    renderCard:renderCard,
    renderDynamicUi:renderDynamicUi,
    renderSelfCard:renderSelfCard,
    renderMiniApp:renderMiniApp,
    postCard:postCard,
    renderCardNode:renderCardNode
  };
})();
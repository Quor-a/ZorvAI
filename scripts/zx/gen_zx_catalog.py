#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate app/src/main/assets/zx_catalog.json from zx_contracts.json.

Strategy
--------
477 zx_ components, each with a unique fn. Classification must be:
  * semantic (so the model can find "the component it wants" fast via RAG)
  * robust (token-based matching, no false substring hits like `bar` in `bargain`)

We classify by an ORDERED category rule list. Each category carries a set of
tokens (whole-token or prefix) plus a few phrase substrings. The FIRST matching
category wins. Per-component we also emit rich retrieval metadata:
  category, title, description, keywords, triggers, concepts
so the RAG domain can match on meaning even when the category is coarse.
"""
import json
import re
import os
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONTRACTS = os.path.join(ROOT, "app", "src", "main", "assets", "zx_contracts.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "zx_catalog.json")

# ---------------------------------------------------------------------------
# Category definitions. Order = priority (earlier wins).
# tokens   : exact whole-token or prefix (token| = prefix) matches
# phrases  : lowercase substrings searched anywhere in the haystack
# ---------------------------------------------------------------------------
CATS = [
    ("chart_basic", "统计图表", dict(
        tokens=["line","bar","column","area","histogram","boxplot","violin","ridgeline",
                "beeswarm","ecdf","lorenz","density","steparea","step","slope","dumbbell",
                "dotstrip","waffle","waterfall","pareto","funnel","pyramid","tornado","roc",
                "survival","survcmp","scatter","bubble","spline","candle","candlestick",
                "contour","bezier","arc","spiral","rainbow","bottleneck","ladder","pictogram","wordcloud"],
        phrases=["bar chart","line chart","scatter plot"]),
    ),
    ("chart_gauge", "占比与仪表", dict(
        tokens=["pie","donut","ring","gauge","dial","meter","odometer","bullet","speed",
                "sparkline","sparkgrid","radial","speedometer","radialbar","bargauge","ratio",
                "percent","winrate"],
        phrases=[]),
    ),
    ("chart_radar", "雷达与极坐标", dict(
        tokens=["radar","polar","wxradar","spider","rose"],
        phrases=[]),
    ),
    ("chart_graph", "关系网络图", dict(
        tokens=["chord","sankey","sankey2","network","topology","depgraph","dag","tree",
                "treeview","orgchart","org","mindmap","force","icicle","sunburst","treemap",
                "matrix","matrix2","matrix3","matrixheat","dmatrix","mosaic","heatmap","calheat",
                "choro","choropleth","venn","flow","flowedit","pipe","lineage","graph","decompose",
                "split","merge","chain","trace","splitflap","parallel"],
        phrases=["relationship","network graph","dependency"]),
    ),
    ("chart_geo", "地理地图", dict(
        tokens=["geo","datamap","mapnav","isometric","hexgrid","route","transit","map","pin",
                "hotspot","remap","mapping","geoflow"],
        phrases=["geographic","map of"]),
    ),
    ("table", "表格数据", dict(
        tokens=["table","datalist","spreadsheet","datagrid","grid","gridform","scoreboard",
                "keyvalue","statelist","sortable","sheet"],
        phrases=[]),
    ),
    ("list", "列表与信息流", dict(
        tokens=["list","tasklist","dualist","checklist","feed","agenda","queue","activity",
                "avatargroup","chatlog","conversation","notice","news","thread"],
        phrases=[]),
    ),
    ("timeline", "时间线与进度", dict(
        tokens=["timeline","timeline2","gantt","kanban","stepper","steps","swimlane","lane",
                "countdown","sequence","sequential","stepflow","trail","milestone","schedule",
                "weekgrid","calendar","appointment","booking","rollout","rollup","handoff",
                "projhandover","runbook","playbook","postmortem","sla","slo","uptime","checkpoint",
                "checkflow","escalation","escalate","incident","stoprule","retry","reversal",
                "reversesolve","seq","seqtest","timerange","stagger","punch"],
        phrases=[]),
    ),
    ("form", "表单与输入", dict(
        tokens=["form","autocomplete","inputmask","timepicker","colorpick","searchbar",
                "filterchips","chips","segmented","radiogroup","slidergroup","switch","toggle",
                "spin","scrubber","range","resizable","dropzone","datepicker","wizard","setup",
                "settings","command","cmdpal","toolbar","toolchain","toolcall","toolargs","slot",
                "slotconflict","photospec","input","select","checkbox","query","dialpad","color",
                "sign","editor","control"],
        phrases=["form input","user input"]),
    ),
    ("compare", "对比与决策", dict(
        tokens=["compare","diff","diffview","jsondiff","seqdiff","conflict","confront","decision",
                "decisionlog","choice","rank","ranking","scorecard","tradeoff","threeway",
                "reversolve","whatif","scenario","realoption","payoff","regret","bandit","auction",
                "bidcurve","surcmp","changerisk","riskreturn","payback","costbenefit","abtest",
                "experiment","cohort","backtest","sensitivity","simulate","systemdyn","priorit",
                "priority","critical","impact","optline","optpath","breakeven"],
        phrases=["a/b test","comparison"]),
    ),
    ("card", "卡片与容器", dict(
        tokens=["card","cardflip","filecard","callout","banner","quote","rich","badges","tags",
                "bento","gallery","carousel","marquee","sticker","album","shell","bracket",
                "panel","container","divider","legend","hint","sense","utility","mirror",
                "skeleton","empty","fallback","accordion","tabs","tabbar","breadcrumb",
                "pagination","pager","toc","tour","drawer","modal","dialog","menu","tooltip",
                "popover","overlay","seat","filemgr","glyph"],
        phrases=[]),
    ),
    ("text", "文本代码日志", dict(
        tokens=["text","markdown","code","codetheme","console","terminal","tty","logview",
                "cmdhist","traceback","callstack","docview","glossary","headline","schoolnote",
                "annotate","formula","readme","knowledge","wiki","tokens","prompt","ast"],
        phrases=["source code","log output"]),
    ),
    ("metric", "指标与数字", dict(
        tokens=["stat","flipnum","number","counter","stars","rating","score","nps","lift",
                "metrics","scale","odds","capacity","kpi","metric","sizing","tolerance",
                "retention","aqi","weather","workload","power","pulse","habit"],
        phrases=[]),
    ),
    ("media", "媒体与图像", dict(
        tokens=["audio","video","music","voice","photo","image","media","playback",
                "waveedit","crop","qr","barcode","camera","player","stream","live","brush","seek",
                "spectrum","swatch"],
        phrases=[]),
    ),
    ("nav", "导航与布局", dict(
        tokens=["tabbar","tabs","breadcrumb","pagination","pager","toolbar","menu",
                "toc","tour","route","accordion","stack","drawer","modal","dialog","setup",
                "wizard","shell","layout","span","align","master","swipe","minmap","control"],
        phrases=[]),
    ),
    ("finance", "金融财务", dict(
        tokens=["ledger","bookkeep","balance","debt","liability","invoice","receipt","reimburse",
                "finalpay","payroll","wallet","coupon","bargain","price","portfolio","riskreturn",
                "costattrib","costtrack","billing","splitbill","utilitymeter","expense","budget",
                "dividend","tax","loan","mortgage","insurance","claim","claimprog","warranty",
                "warrtrack","rent","rental","rentreturn","lease","trade","stock","fund","quote",
                "auction","bidcurve","profit","revenue","sales","subscription","bank","cash",
                "capital","hedge","expiry","pricing","margin","contingent","overrun","pfront",
                "transfer","taxxborder","visa","unitecon","parcel"],
        phrases=["financial","accounting"]),
    ),
    ("devops", "工程运维", dict(
        tokens=["deploy","runbook","incident","escalation","healthcheck","uptime","sla","slo",
                "loadtest","stress","deviation","anomaly","audit","auditrail","accessreview",
                "compliance","credential","perm","rollback","revision","commit","branch",
                "checkflow","stoprule","retry","pipeline","monitor","alert","provision","shell",
                "inspect","inspection","repair","recycle","renovation","maintenance","release",
                "build","ci","abmon","annualaudit","audittrail","baseline","batch","boot","canary",
                "calib","multiissue","plant","probe","pscan","psm","rca","revoke","remote",
                "statemachine","test","alloc","workload","devops"],
        phrases=["monitoring","incident response"]),
    ),
    ("aiml", "数据科学与AI", dict(
        tokens=["model","forecast","regression","bayes","markov","montecarlo","causal","cfactual",
                "cluster","pca","mds","embedding","llm","prompt","agent","toolcall","toolargs",
                "toolchain","train","backtest","sensitivity","whatif","simulate","systemdyn",
                "neural","inference","eval","benchmark","abtest","experiment","cohort","bayesian",
                "drift","explain","explainability","shap","feature","dataset","dataclass","datamap",
                "probability","distribution","sampling","reinforce","rl","gan","autoencoder",
                "classifier","regressor","adversarial","cforest","forest","corr","expresult",
                "regress","rdd","vardec","blast"],
        phrases=["machine learning","forecast"]),
    ),
    ("health", "医疗健康", dict(
        tokens=["clinic","medicine","medremote","firstaid","fitness","sleep","iv","ivreview",
                "vet","wardhand","patient","diagnostic","clinical","therapy","nutrition","symptom",
                "vaccine","triage","injury","health","disease","prescription","ward","nurse",
                "hospital","medical","care","carerota","dutyrota","fosterdaily","pet","pulse",
                "habit","recipe","study","examprep","inspecthand","damage","escort","evidence"],
        phrases=["medical","patient care"]),
    ),
    ("property", "房产物业", dict(
        tokens=["housedeal","housekeep","lease","rental","rentreturn","renovation","parking",
                "floor","moveout","sublet","relocation","floorplan","property","estate","landlord",
                "vehiclecare","cardeal","carservice","vehicle","garage","facility","commute",
                "seat","tripkit","damage"],
        phrases=["real estate","property"]),
    ),
    ("pm", "项目管理协作", dict(
        tokens=["ticket","tasklist","handoff","projhandover","playbook","knowledge","wiki",
                "postmortem","checklist","agile","sprint","backlog","board","retro","approval",
                "review","feedback","poll","survey","question","vote","meeting","agenda","contact",
                "team","roster","directory","interview","offboard","lostdoc","refile","schoolup"],
        phrases=["project management","team collaboration"]),
    ),
    ("commerce", "电商购物", dict(
        tokens=["shop","order","cart","product","coupon","gift","subscription","shipment",
                "delivery","refund","review","catalog","inventory","stocktake","mall","store",
                "sku","warehouse","reorder","purchase","sale","ship","parcel"],
        phrases=["e-commerce","shopping"]),
    ),
    ("social", "社交通讯", dict(
        tokens=["chat","conversation","comment","mention","feed","message","notification",
                "qq","wxradar","wechat","reply","reaction","emoji","friend","group","community",
                "social","channel","live","repost","react"],
        phrases=["messaging","social"]),
    ),
    ("game", "游戏娱乐", dict(
        tokens=["game","tictactoe","chess","puzzle","dice","lottery","quiz","joystick","slot",
                "scoreboard","leaderboard","bingo","cardgame","quest","reward","2048"],
        phrases=["game","entertainment"]),
    ),
    ("general", "通用展示组件", dict(
        tokens=["empty","skeleton","fallback","placeholder","loading","divider","legend","hint",
                "sense","utility","mirror","sample","demo","debug","span","align","marker",
                "badge","chip","tag","icon","spinner","loader","bland","did","export","missing",
                "gauntlet"],
        phrases=[]),
    ),
]

# Token dictionary: English token -> Chinese gloss (used for title / keywords / description)
ZH = {
    "compare":"对比","diff":"差异","timeline":"时间线","gantt":"甘特图","kanban":"看板",
    "table":"表格","list":"列表","form":"表单","chart":"图表","pie":"饼图","ring":"环形图",
    "gauge":"仪表盘","dial":"表盘","radar":"雷达图","line":"折线图","bar":"柱状图","area":"面积图",
    "scatter":"散点图","heatmap":"热力图","matrix":"矩阵","tree":"树","network":"网络图",
    "orgchart":"组织架构图","map":"地图","calendar":"日历","card":"卡片","banner":"横幅",
    "quote":"引用","rich":"富文本","tags":"标签","badges":"徽章","gallery":"画廊","carousel":"轮播",
    "video":"视频","audio":"音频","voice":"语音","image":"图片","photo":"照片","qr":"二维码",
    "barcode":"条形码","form":"表单","input":"输入","select":"选择","slider":"滑块","switch":"开关",
    "toggle":"切换","search":"搜索","filter":"筛选","star":"星级","rating":"评分","score":"得分",
    "stat":"统计","metric":"指标","number":"数字","counter":"计数器","progress":"进度",
    "step":"步骤","flow":"流程","pipe":"管道","chain":"链","graph":"图","node":"节点",
    "finance":"财务","ledger":"账本","balance":"余额","budget":"预算","invoice":"发票",
    "receipt":"收据","wallet":"钱包","price":"价格","portfolio":"投资组合","risk":"风险",
    "health":"健康","medical":"医疗","patient":"患者","medicine":"药品","sleep":"睡眠",
    "fitness":"健身","property":"房产","rental":"租赁","lease":"租约","house":"房屋",
    "ticket":"工单","task":"任务","schedule":"排程","meeting":"会议","approval":"审批",
    "review":"评审","survey":"问卷","poll":"投票","feedback":"反馈","chat":"聊天",
    "comment":"评论","contact":"联系人","news":"新闻","notice":"通知","log":"日志",
    "console":"控制台","terminal":"终端","code":"代码","markdown":"标记语言","doc":"文档",
    "model":"模型","forecast":"预测","train":"训练","eval":"评估","cluster":"聚类",
    "regression":"回归","bayes":"贝叶斯","markov":"马尔可夫","simulate":"模拟","infer":"推理",
    "deploy":"部署","incident":"事件","monitor":"监控","alert":"告警","sla":"服务等级",
    "compliance":"合规","audit":"审计","security":"安全","access":"访问","credential":"凭证",
    "game":"游戏","quiz":"测验","reward":"奖励","shop":"商店","order":"订单","cart":"购物车",
    "product":"商品","gift":"礼物","subscription":"订阅","media":"媒体","player":"播放器",
    "weather":"天气","aqi":"空气质量","calendar":"日历","clock":"时钟","timer":"计时器",
    "countdown":"倒计时","route":"路线","transit":"公交","map":"地图","geo":"地理",
}

# Domain concept tags by category
CONCEPTS = {
    "chart_basic":"可视化,图表,统计",
    "chart_gauge":"可视化,仪表,占比",
    "chart_radar":"可视化,雷达,多维",
    "chart_graph":"可视化,关系,网络",
    "chart_geo":"可视化,地图,地理",
    "table":"数据,表格",
    "list":"数据,列表",
    "timeline":"时间,进度,计划",
    "form":"交互,输入,表单",
    "compare":"分析,对比,决策",
    "card":"展示,卡片,容器",
    "text":"文本,代码,日志",
    "metric":"指标,KPI,数字",
    "media":"媒体,图像,音视频",
    "nav":"导航,布局,结构",
    "finance":"金融,财务,会计",
    "devops":"运维,工程,监控",
    "aiml":"AI,数据科学,模型",
    "health":"医疗,健康,护理",
    "property":"房产,物业,租赁",
    "pm":"项目管理,协作,团队",
    "commerce":"电商,购物,订单",
    "social":"社交,通讯,消息",
    "game":"游戏,娱乐,互动",
    "general":"通用,占位,基础",
}


def split_tokens(name_rest):
    """Split 'fooBar2baz' -> ['foo','bar','2','baz']."""
    parts = re.findall(r"[A-Za-z]+|\d+", name_rest)
    return [p.lower() for p in parts]


def build_haystack(name_rest, entry):
    toks = split_tokens(name_rest)
    bits = [name_rest.lower(), entry.get("fn", "").lower(),
            " ".join(entry.get("fields", [])).lower(),
            " ".join(entry.get("arrays", [])).lower()]
    sample = entry.get("sample")
    if isinstance(sample, dict):
        flat = []
        def walk(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    flat.append(str(k)); walk(v)
            elif isinstance(o, list):
                for x in o:
                    walk(x)
            else:
                flat.append(str(o))
        walk(sample)
        bits.append(" ".join(flat).lower())
    return " ".join(bits), toks


# Explicit per-name overrides for components the rule engine leaves in `general`.
# Keyed by full component name -> category id.
OVERRIDES = {
    "zx_2048": "game", "zx_abmon": "devops", "zx_alloc": "devops",
    "zx_annualaudit": "devops", "zx_aqi": "metric", "zx_ast": "text",
    "zx_audittrail": "devops", "zx_baseline": "compare", "zx_batch": "devops",
    "zx_bland": "general", "zx_blast": "aiml", "zx_boot": "devops",
    "zx_brush": "media", "zx_calib": "aiml", "zx_canary": "devops",
    "zx_cforest": "aiml", "zx_chart": "chart_basic", "zx_color": "form",
    "zx_commute": "property", "zx_contingent": "finance", "zx_control": "nav",
    "zx_corr": "aiml", "zx_damage": "property", "zx_dialpad": "form",
    "zx_did": "general", "zx_editor": "text", "zx_escort": "general",
    "zx_evidence": "general", "zx_examprep": "health", "zx_explode": "chart_basic",
    "zx_export": "general", "zx_expresult": "aiml", "zx_filemgr": "card",
    "zx_forest": "aiml", "zx_gauntlet": "general", "zx_geoflow": "chart_geo",
    "zx_habit": "health", "zx_horizon": "chart_basic", "zx_hotspot": "chart_geo",
    "zx_inspecthand": "health", "zx_interview": "pm", "zx_lostdoc": "pm",
    "zx_mapping": "chart_geo", "zx_minmap": "nav", "zx_missing": "general",
    "zx_multiissue": "devops", "zx_offboard": "pm", "zx_overrun": "finance",
    "zx_parcel": "commerce", "zx_pet": "health", "zx_pfront": "finance",
    "zx_pin": "chart_geo", "zx_plant": "devops", "zx_power": "devops",
    "zx_probe": "devops", "zx_pscan": "devops", "zx_psm": "devops",
    "zx_pulse": "health", "zx_punch": "pm", "zx_rca": "devops",
    "zx_rdd": "aiml", "zx_react": "social", "zx_recipe": "health",
    "zx_refile": "pm", "zx_regress": "aiml", "zx_remap": "chart_geo",
    "zx_remote": "devops", "zx_repost": "social", "zx_retention": "metric",
    "zx_revoke": "devops", "zx_schoolup": "general", "zx_seat": "property",
    "zx_seek": "media", "zx_seq": "timeline", "zx_seqtest": "aiml",
    "zx_ship": "commerce", "zx_sign": "form", "zx_sizing": "metric",
    "zx_splitflap": "card", "zx_stagger": "timeline", "zx_statemachine": "devops",
    "zx_study": "health", "zx_swipe": "nav", "zx_taxxborder": "finance",
    "zx_test": "devops", "zx_timerange": "timeline", "zx_tolerance": "metric",
    "zx_transfer": "finance", "zx_tripkit": "property", "zx_unitecon": "finance",
    "zx_vardec": "aiml", "zx_violincmp": "chart_basic", "zx_visa": "finance",
    "zx_weather": "metric", "zx_workload": "devops",
}


def classify(name, name_rest, entry):
    if name in OVERRIDES:
        return OVERRIDES[name]
    hay, toks = build_haystack(name_rest, entry)
    for cat_id, _label, rules in CATS:
        tokens = rules.get("tokens", [])
        phrases = rules.get("phrases", [])
        # whole / prefix token match
        matched = False
        for t in tokens:
            if t.endswith("|"):  # prefix
                pre = t[:-1]
                if any(tok.startswith(pre) for tok in toks):
                    matched = True; break
            else:
                if t in toks:
                    matched = True; break
        if not matched:
            for ph in phrases:
                if ph in hay:
                    matched = True; break
        if matched:
            return cat_id
    return "general"


def title_of(name_rest):
    toks = split_tokens(name_rest)
    words = [t.capitalize() for t in toks if not t.isdigit()]
    base = " ".join(words) if words else name_rest
    return base


def zh_gloss(name_rest):
    toks = split_tokens(name_rest)
    gl = []
    for t in toks:
        if t in ZH and ZH[t] not in gl:
            gl.append(ZH[t])
    return gl


def main():
    with open(CONTRACTS, "r", encoding="utf-8") as f:
        data = json.load(f)
    contracts = data["contracts"]
    roster = data["components"]

    catalog = {"categories": {}, "components": {}}
    dist = Counter()
    unclassified = []
    samples_missing = []

    for name in roster:
        entry = contracts.get(name, {})
        name_rest = name[3:] if name.startswith("zx_") else name
        cat = classify(name, name_rest, entry)
        dist[cat] += 1
        if cat == "general":
            # mark for review but keep
            unclassified.append(name)

        title = title_of(name_rest)
        zh = zh_gloss(name_rest)
        concepts = CONCEPTS.get(cat, "通用")
        concepts_list = [c for c in concepts.split(",") if c]

        # keywords: tokens + fn + fields + arrays + zh + sample flattened
        kw = set()
        for t in split_tokens(name_rest):
            if not t.isdigit():
                kw.add(t)
        kw.add(entry.get("fn", ""))
        for fld in entry.get("fields", []):
            kw.add(fld)
        for ar in entry.get("arrays", []):
            kw.add(ar)
        for z in zh:
            kw.add(z)
        sample = entry.get("sample")
        if isinstance(sample, dict):
            def walk(o):
                if isinstance(o, dict):
                    for k, v in o.items():
                        kw.add(k); walk(v)
                elif isinstance(o, list):
                    for x in o: walk(x)
                else:
                    s = str(o)
                    if 0 < len(s) <= 12:
                        kw.add(s)
            walk(sample)
        kw.discard("")
        kw.discard(name)  # avoid noise

        # triggers: intent phrases
        triggers = [
            f"展示{title}",
            f"需要{title}组件",
            f"绘制{title}",
        ]
        if zh:
            triggers.append(f"展示{'/'.join(zh)}")

        # description
        sample_hint = ""
        if isinstance(sample, dict) and "type" in sample:
            sample_hint = f" 示例 type={sample.get('type')}。"
        desc = (f"zx 组件「{name}」（{title}）：用于呈现{title}相关数据。"
                f"关键字段 {entry.get('fields', [])}、数组 {entry.get('arrays', [])}。"
                + sample_hint)

        catalog["components"][name] = {
            "category": cat,
            "title": title,
            "zh": zh,
            "description": desc,
            "keywords": sorted(kw),
            "triggers": triggers,
            "concepts": concepts_list,
            "fn": entry.get("fn", ""),
            "fields": entry.get("fields", []),
            "arrays": entry.get("arrays", []),
            "has_sample": isinstance(sample, dict),
        }

    # categories summary
    for cat_id, label, _ in CATS:
        members = [n for n in roster if catalog["components"].get(n, {}).get("category") == cat_id]
        catalog["categories"][cat_id] = {
            "label": label,
            "count": len(members),
            "members": members,
        }

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=1)

    print("TOTAL:", len(roster))
    print("DISTRIBUTION:")
    for cat_id, label, _ in CATS:
        print(f"  {cat_id:14s} {label:8s} {dist.get(cat_id,0)}")
    print("\nUNCLASSIFIED (general) count:", len(unclassified))
    print("  ", ", ".join(unclassified))
    print("\nMISSING sample:", sum(1 for n in roster if not isinstance(contracts.get(n,{}).get("sample"), dict)))


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""原子追加 R7-19（RAG 渐进式工具披露改造）到 2026-10-06.md。

写文件铁律：必须先补齐末尾换行，再用 .tmp + os.replace 原子替换；
绝不能直接 io.open(p,"w") —— 那会先把文件截断为 0，抛异常即清空全部历史。
"""
import os

PATH = "D:/WyDownloads/2026-08-28-00-11-37/.workbuddy/memory/2026-10-06.md"
TMP = PATH + ".tmp"

SECTION = """
## R7-19 · 渐进式工具披露改为 RAG 式按需检索（PROGRESSIVE 默认开）

> 用户原话：「ROGRESSIVE = false 默认关闭全量下发改成RAG，怎么完整获取要写好，
> 正确的获取想要的，不只是这个全面排查需要RAG的全部添加RAG」。

### 全面排查结论（先排查再改，别凭直觉动开关）

写探针 `RagBaselineProbe` 实测 265 个工具：

| 检查项 | 结果 |
|---|---|
| `QuroToolRouter.categorize` 落 null 的工具 | **0 / 265**（codecanvas 补完后分类已无遗漏） |
| `matchToolsByIntent` 中文口语召回 | **2 / 14 = 14%** ← 真凶 |
| 榜首 | 恒为噪声 `cms_toolbox` |

**根因**：`matchToolsByIntent` 只做**整句包含**
（`intent.contains(useCase) || useCase.contains(intent)`），
而没有手写条目的工具其 useCases 就是 `autoInfo` 填的**工具自己的 description**。
于是「设个闹钟」永远匹配不上「设置/添加闹钟（支持重复）」这种整句。
那不是检索，是字符串全等。**「AI 查不到」不是模型不查，是检索层坏了。**

### 检索层重写（`ToolCapabilityDirectory.matchToolsByIntent` + 新建 `ToolTextMatcher.kt`）

- **分词** `ToolTextMatcher.tokenize`：中文按 **bigram**（长度 1 保留单字）
  + 英文按 `[_-]+` 切 + 保留原 compact 整段（2..12 字）。
- **同义词归一** `normalize`：`synonymPairs = synonyms.sortedByDescending { it.first.length }`
  —— **必须长词优先**，否则「搜一下」会先被短词「搜」吃掉改坏。
  覆盖语音（念/朗读/tts/speak）、出图（画/绘制/生图/海报）、检索（搜一下/查一下/新闻/资讯→搜索）、
  文件（删掉/移除→删除）、打包、提醒（闹钟→提醒）、屏幕、运行等 40+ 条。
- **多字段加权评分** `scoreOf`：name 3.0 / description 2.0 / useCases 1.2 /
  examples 0.8 / tips 0.5 / category 1.0；整串包含额外 +4.0。
- **token 信息量加权** `tokenWeight`：停用词 0.15 / 单字 0.4 / 纯数字 0.3 /
  长度>=3 为 1.0 / 其余 0.6。**没有这一步，「画一张海报」里 `card_patch`
  会靠「一/张」这种通用 bigram 排到第一。**
- **停用词表** `STOP_WORDS`：一下/这个/帮我/一张/一段/文字/文本/内容…
  （不加的话「给我念一段文字」会被 translate / audio_recognition 挤掉）。
- **名称实义词加成**：queryCore（weight>=0.6 的 token）命中 name 时 `1.6 * 命中率`。
- **零命中兜底**：返回 priority 排序前 `FALLBACK_CANDIDATES = 12` 个候选，**绝不返回空**
  ——空列表会被渲染成「未找到匹配的工具」，正是用户报的「查不到」。
  但**全空白输入（无意图）返回空是对的**，由 `matchIntent` 的 isBlank 分支给指引文案。
- 排序：score desc → priority desc → name asc（**结果稳定可测**，否则写不了断言）。

**召回：2/14（14%）→ 14/14（100%）**。

### 🔴 catalogSpec 体积：改了等于白改的决定性发现

第一版改造做完一测体积发现问题：

```
全量 tools 数=265 字符=145472
RAG  tools 数=147 字符=140130     ← 只省 3.7%
catalogSpec 描述长度=66838
```

**根因**：`catalogSpec()` 把 `buildCompactIndex()`（全部 265 个工具 name+description）
塞进了 tool_router 的 description = 66,838 字符，占整个 tools 字段的 46%。
**把全量清单从 `tools[]` 挪到某一个 description 里，并不让它变短。**

**修法**：新增 `buildCatalogBrief()`——只给「分类 + 工具名逗号列表」，
具体有哪些工具交给 `match_intent` / `list_tools` / `get_directory_summary` 按需取回。
这样每轮固定开销是常数级，与工具总数无关。

```
catalog 描述  66,838 → 4,758 字符
RAG 总量     140,130 → 78,050（vs 全量 145,472，省 46%）
加载 10 工具后 84,453
```

### 🔴 PROGRESSIVE 默认 false → true（勿推翻）

旧注释理由是「false，因为小模型不查就直接说不会」。**方向对，但前提不成立**：
当时检索层本身是坏的（召回 2/14），模型主动查也查不到，
于是「查不到」被误判成「模型不查」。检索层重写后召回 100%，前提才成立。

若将来又观察到「明明装了却说自己不会」，**先查检索召回质量与 ALWAYS_ON 覆盖，
别直接关这个开关**——那会退回每轮全量下发，正是本轮要消除的开销。

### loaded 集合落盘（消除「这次会了、下次又不会了」）

`loaded` 原先只在内存，router 实例挂在 `QuroAssistant.toolRouters` 上，
进程被杀 / App 被回收后 map 清空 → 用户感知「同样的问题这次会了、下次又不会了」。

- 加**构造参数** `appContextRef: Context?`（不用可变字段，避免并发覆盖；纯 JVM 单测传 null）。
- `persist()` / `restore()` 走 SharedPreferences（`quro_tool_router` / `loaded_tool_names`）。
- `getSchema` 成功加载后立即 `persist()`；`reset()` 清空并 persist。
- **`setSpecs` 必须 `loaded.retainAll(specByName.keys)`**：插件卸载/技能删除后
  否则 catalogSpec 仍展示过期工具名，模型照着调必然报未知工具。
- 不做会话级隔离：已加载的是**工具使用知识**（参数长什么样）不是对话状态。

### ALWAYS_ON / 提示词

- `ALWAYS_ON` 加出图三件套 `codecanvas_probe` / `codecanvas_script` / `codecanvas_markup`
  （`code_card` / `llm_code` 走路由，不占常驻）。共 154 个。
- `catalogSpec` 写「强制工作流」：① `match_intent` 用**用户原话** → ② `get_schema`
  → ③ `list_categories`/`list_tools` → ④ 已知名字直接 `get_schema`；
  并加一句「只要你认为自己没有某个能力，**必须先查一遍**再说」。
- `QuroChatViewModel` 系统提示词「本版重点能力」段补 codecanvas。

### 固化：三组正式断言测试（已删掉只 println 的两个 Probe）

**Probe 只 println、还要加 `-i` 才看得到输出，挡不住任何回退**，故全部换成断言：

| 测试 | 钉死什么 |
|---|---|
| `ToolRagRecallTest` | 14 条中文口语**全部召回**；关键查询排进前 3；零命中有兜底非空；工具全名能召回自己；两次检索顺序一致 |
| `ToolRagSizeTest` | RAG < 全量 70%（实测 54%）；**`tool_router` 描述 < 10,000 字符**（防有人把 buildCompactIndex 塞回去）；加载 10 工具后不失控；`get_schema` 真让工具进下发集；`reset` 真清空；`PROGRESSIVE` 默认 true |
| `ToolRagCoverageTest` | **没有工具在 categorize 里落 null**；目录清单含全部工具名；目录描述含强制工作流关键词；`setSpecs` 收缩后剔除过期名字；未知工具/未知分类必须给指引 |

踩坑：`QuroToolSpec` 在 `com.ai.assistance.quro.core` 不在 `.tools`，漏 import 会连累
`sumOf` 一片 Overload resolution ambiguity。`sumOf { chars(it) }` 返回 **Int** 不是 Long，
`(full * 0.70).toLong()` 会类型不匹配。
"""

with open(PATH, "rb") as f:
    raw = f.read()

assert b"R7-19" not in raw, "R7-19 已存在，不要重复追加"
assert b"R7-18" in raw, "前文（应含 R7-18）被破坏，中止"

body = raw.decode("utf-8")
if not body.endswith("\n"):
    body += "\n"

with open(TMP, "w", encoding="utf-8", newline="\n") as f:
    f.write(body + SECTION)
os.replace(TMP, PATH)

check = open(PATH, "rb").read()
assert check.endswith(b"\n")
assert b"R7-18" in check and b"R7-19" in check
assert check.startswith(raw[:200])
print("OK size", len(raw), "->", len(check))

package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏互动 UI 工具（动态 UI + 回调 = AI 现场写规则、和用户实时对局）。
 *
 * 设计要点：
 *  - 游戏 = 可交互的动态 UI（```quro-ui 代码块）+ callback 事件回传 + AI 在对话里维持游戏状态。
 *  - 用户每步操作（点格子 / 按钮 / 输入框）都会以「用户消息」的形式回发给你，你据此推进规则、
 *    重新渲染棋盘/比分，形成对局闭环——你就是裁判 + 对手 + 解说。
 *  - [game_board] 是新加的专用组件（N×M 可点击网格），其余复用现有节点（text/button/badge/stat/…）。
 *  - 这套机制在「主对话」和「GenUI Agent」里通用（共用渲染器与工具集），且游戏 UI 内还能用
 *    tool_call / callback 调用 Agent 能力（run_code 跑逻辑、web 取题、skill 触发等）。
 */
class GameUiTool : QuroTool {
    override val name = "game_ui"
    override val description =
        qstr(R.string.qk_03498) +
            "或让用户描述一个新游戏、你现场定规则带他玩。先调本工具拿到「游戏搭建 + 对局规范」，然后" +
            "在回复里输出 ```quro-ui 渲染棋盘/控件，用 callback 回收用户操作，你维持状态继续对局。" +
            "适用于：用户想玩互动小游戏、做游戏化互动、用对话组件做可玩 demo。"

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("section", JSONObject().apply {
                put("type", "string")
                put(
                    "description",
                    "章节：overview(总览与玩法循环) / board(game_board 组件规范) / rules(如何用回调维持对局) / games(现成游戏模板) / all(全部)。留空返回 overview。"
                )
                put("enum", JSONArray().apply {
                    put("overview"); put("board"); put("rules"); put("games"); put("all")
                })
            })
        })
        put("required", JSONArray())
    }.toString()

    override fun run(context: Context, arguments: String): String {
        val section = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("section", "").trim().lowercase()
        return when (section) {
            "board" -> BOARD
            "rules" -> RULES
            "games" -> GAMES
            "all" -> "$OVERVIEW\n\n$BOARD\n\n$RULES\n\n$GAMES"
            else -> OVERVIEW
        }
    }

    private companion object {
        val OVERVIEW = """
===== 游戏互动 UI（和 AI 玩的动态对话组件游戏）=====

你（AI）就是「裁判 + 对手 + 解说」。一个游戏 = 一块可交互的动态 UI + 用户操作回传 + 你在对话中维持状态。

■ 玩法闭环（三步循环）
1. 开局：用 ```quro-ui 渲染游戏面板（标题、棋盘/控件、比分、操作按钮）。
2. 收步：用户点按钮 / 输入框 / 棋盘格子 → 触发 callback 事件，把操作（含坐标/取值）作为「用户消息」回发给你。
3. 续局：你按规则判定、更新状态，重新渲染同一块 UI（改 cells / 文本 / 比分），把新面板发回去。
   重复 2-3 直到分出胜负或用户退出。

■ 你需要的节点
- game_board：N×M 可点击网格（棋盘 / 卡面），点格自动回发 row/col/index/value。
- text / badge / stat：显示提示、回合、比分、计数。
- button：操作按钮（要牌 / 停牌 / 重开 / 选择）。
- text_input：让用户输入（猜数字 / 自定义答案）。
- 动作统一用 {"type":"callback","event":"事件名","collect_from":["控件id"]}；棋盘格子由 game_board 自动带 data。

■ 状态怎么存
不要依赖 UI 记状态——UI 只是快照。把完整棋盘、比分、随机数种子、已翻牌等存在你的「推理上下文」里：
每次回发你都重新生成最新 panel。把状态写成一行便于你记忆，例如 ttt=「X.O|X..|..O」 bj=「me:17 you:9 deck:[...]」。

■ 让游戏成立的关键
- 每个 callback 的 event 要唯一可区分（如 ttt_move / ttt_reset / bj_hit）。
- 非法/重复操作（点到已占格、超 21）你要忽略或提示，不要崩溃。
- 一句开局白说明规则，让用户知道怎么玩。

■ GenUI Agent / Agent 能力
游戏在 GenUI Agent 中同样可玩（共用渲染与工具集）。游戏 UI 内还能用 tool_call 调用 Agent 能力：
例如 run_code 跑牌堆/随机数逻辑、web 拉题库、skill 触发技能——让「AI 现场写规则」更强大。
调 game_ui 拿完整组件与模板，再开玩。

    ★ 必读：游戏「点了没反应」的 4 个原因（写游戏前先看）
    - 原因1：围栏不是 ```quro-ui → 改成 quro-ui 才渲染。
    - 原因2：你收到【事件名】后只回了文字、没重新输出 ```quro-ui 面板 → 必须每轮重建整块 UI（更新 cells / 比分 / 提示）。
    - 原因3：game_board 的 cellAction 没写 callback，或 clickable 不是 true → 点格没反应。
    - 原因4：收集输入时 text_input 没给 id → collect_from 收不到。
    记住：你就是状态机。UI 不记忆上一局，每次回发你都重新生成最新面板，游戏就「活」了。
    完整教程见 ui_dsl_spec 的【必读：让组件真的能用】段。

""".trimIndent()

        val BOARD = """
【game_board 组件规范】（专为「和 AI 玩的游戏」设计的可点击网格）
{
  "type": "game_board",
  "id": "棋盘唯一id(必填，用作 callback event 兜底)",
  "rows": 3, "cols": 3,
  "cells": ["X","","O","","","","","",""],   // 每格显示文本，行优先，长度应 = rows×cols，不足补空
  "values": ["X","","O", ...],               // 可选：每格回发值，默认 = cells
  "cellColors": ["#ffd54f","","#90caf9", ...],// 可选：每格背景色(HEX/命名色，非法回落默认)
  "cellAction": {"type":"callback","event":"棋盘事件名"}, // 点击触发；系统自动注入 data={row,col,index,value}
  "clickable": true,                         // 是否可交互(false=纯展示棋盘)
  "cellSize": 56                              // 可选：格子边长 dp，默认 56
}
点击某格 → 触发 cellAction，并回发 data：{row, col, index, value}，例如点第 1 行第 2 列(值X)：
【棋盘事件名】
row: 0
col: 1
index: 1
value: X

注意：clickable 是整盘开关。要做「已占格不可点」由你的规则判定忽略即可；也可用 cellColors 标记占位。
game_board 之外，任何节点（card/column/row/button/text/badge/stat/…）都能照常混用。
""".trimIndent()

        val RULES = """
【AI 现场写规则 + 对局循环（你就是裁判）】
1) 开局：输出 ```quro-ui 面板，并在同一条消息里用自然语言讲清规则（一句话即可）。
2) 收步：用户操作回发成「用户消息」，格式 【event】\\nkey: value …（棋盘格自带 row/col/index/value）。
3) 判定：在你的推理里维护完整状态（棋盘串 / 比分 / 牌堆 / 随机种子），按规则算合法性与胜负。
4) 续局：重新输出 ```quro-ui（更新 cells / 文本 / 比分 / 提示），并给出一句解说。
5) 终止：达成胜负或用户说停 → 输出结算面板 + 一句结束语；需要「重开」就给一个 reset 按钮。

要点：
- 非法步（点到占格、超 21、重复翻同张）忽略或提示，不要当成有效步推进。
- 多轮状态靠你「每轮重建整块 UI」保持一致，别指望控件记忆上一局。
- 可用 callback 的 data 直接拿值；用 collect_from 收集 text_input 的输入。
- 想让逻辑更严谨：在棋盘/按钮里用 tool_call 调 run_code 跑判定（如牌堆抽牌、随机数、胜负判定）。

示例（井字棋中你的回合，收到 【ttt_move】 row:1 col:1 value:）：
「你下在 (1,1)。我应 (0,0)：」
```quro-ui
{"type":"card","title":"❎ 井字棋","children":[
  {"type":"text","value":"轮到你：点空格落子（你是 X）。","style":"body"},
  {"type":"game_board","id":"ttt","rows":3,"cols":3,
   "cells":["O","","","","X","","","",""],
   "cellColors":["#bbdefb","","","","#c8e6c9","","","",""],
   "cellAction":{"type":"callback","event":"ttt_move"}},
  {"type":"button","label":"重开","variant":"text","action":{"type":"callback","event":"ttt_reset"}}
]}
```
""".trimIndent()

        val GAMES = """
【现成游戏模板（直接照抄改文字即可开局；收到操作后用 rules 段的方式判定续局；组件不够时再用「现场发明新游戏」）】

■ 1. 猜数字（1-100，高低提示）
```quro-ui
{"type":"card","title":"🔢 猜数字","children":[
  {"type":"text","value":"我想了一个 1-100 的整数，你来猜。","style":"body"},
  {"type":"row","children":[
    {"type":"text_input","id":"guess","label":"你的猜测","input_type":"number","placeholder":"1-100"},
    {"type":"button","label":"猜！","variant":"filled","action":{"type":"callback","event":"guess","collect_from":["guess"]}}
  ]},
  {"type":"badge","text":"已猜 0 次","background":"primary"},
  {"type":"button","label":"重开","variant":"text","action":{"type":"callback","event":"guess_reset"}}
]}
```
收到 【guess】 guess: 50 → 比大小回「大了/小了/中了」，更新 badge 次数；猜中用结算面板。guess_reset 重新随机。

■ 2. 井字棋人机（3×3，你=X，AI=O）
```quro-ui
{"type":"card","title":"❎ 井字棋","children":[
  {"type":"text","value":"轮到你：点空格落子（你是 X）。","style":"body"},
  {"type":"game_board","id":"ttt","rows":3,"cols":3,
   "cells":["","","","","","","","",""],
   "cellAction":{"type":"callback","event":"ttt_move"}},
  {"type":"button","label":"重开","variant":"text","action":{"type":"callback","event":"ttt_reset"}}
]}
```
收到 【ttt_move】 row/col → 你落 X，AI 落 O（优先堵/赢），重渲 board；三连判胜负；ttt_reset 清空。

■ 3. 石头剪刀布（三局两胜）
```quro-ui
{"type":"card","title":"✊ 石头剪刀布","children":[
  {"type":"text","value":"选一个，我和你比。三局两胜。","style":"body"},
  {"type":"row","children":[
    {"type":"button","label":"✊ 石头","variant":"outlined","action":{"type":"callback","event":"rps","data":{"choice":"rock"}}},
    {"type":"button","label":"✋ 布","variant":"outlined","action":{"type":"callback","event":"rps","data":{"choice":"paper"}}},
    {"type":"button","label":"✌ 剪刀","variant":"outlined","action":{"type":"callback","event":"rps","data":{"choice":"scissors"}}}
  ]},
  {"type":"badge","text":"你 0 : 0 我","background":"secondary"}
]}
```
收到 【rps】 choice: rock → 随机出拳判定胜负，更新比分 badge；先到 2 胜结束。

■ 4. 21点（简化：你当闲家，AI 当庄家）
```quro-ui
{"type":"card","title":"🃏 21点","children":[
  {"type":"text","value":"你的点数：?  我的明牌：?","style":"body"},
  {"type":"row","children":[
    {"type":"button","label":"要牌","variant":"filled","action":{"type":"callback","event":"bj_hit"}},
    {"type":"button","label":"停牌","variant":"outlined","action":{"type":"callback","event":"bj_stand"}}
  ]},
  {"type":"button","label":"新一局","variant":"text","action":{"type":"callback","event":"bj_new"}}
]}
```
收到 【bj_hit】 → 发一张（用 tool_call run_code 抽牌/算点更稳），爆 21 你赢；【bj_stand】 → 庄家补到 17+ 比大小；bj_new 重开。

■ 5. 记忆翻牌（4×4，8 对）
```quro-ui
{"type":"card","title":"🎴 记忆翻牌","children":[
  {"type":"text","value":"点两张牌，配对成功保留，全配对即胜。","style":"body"},
  {"type":"game_board","id":"mem","rows":4,"cols":4,
   "cells":["❓","❓","❓","❓","❓","❓","❓","❓","❓","❓","❓","❓","❓","❓","❓","❓"],
   "cellAction":{"type":"callback","event":"mem_flip"}},
  {"type":"badge","text":"配对 0/8","background":"primary"}
]}
```
收到 【mem_flip】 row/col → 记翻面；翻两张判配对，命中回显 emoji、未命中翻回 ❓；更新 badge。可扩到 5×5/6×6。

■ 6. 猜单词（英文 wordle-lite：每次猜一个字母，位置反馈）
用 text_input + badge 显示已猜字母与命中位置（绿=对位、黄=在词中、灰=无）。AI 想一个词，回发 letter → 标色，猜全即胜；超限判负。

■ 7. 成语接龙（中文）
用 text_input 收成语，AI 校验首尾字是否相连且非重复，并接下一个；接不上/重复判负。badge 显示轮次与已接字数。

■ 8. 猜价格（高价低价，1-1000）
同猜数字但主题是「猜商品价」，guess 后回「高了/低了」，badge 计次；可加「提示」按钮给区间。

■ 9. 真心话大冒险
```quro-ui
{"type":"card","title":"🎲 真心话大冒险","children":[
  {"type":"text","value":"选模式，我给你题目。","style":"body"},
  {"type":"row","children":[
    {"type":"button","label":"真心话","variant":"outlined","action":{"type":"callback","event":"tod","data":{"mode":"truth"}}},
    {"type":"button","label":"大冒险","variant":"outlined","action":{"type":"callback","event":"tod","data":{"mode":"dare"}}}
  ]},
  {"type":"badge","text":"第 1 题","background":"primary"}
]}
```
收到 【tod】 mode → 随机出一道对应题目，更新 badge 轮次。

■ 10. 抛硬币
```quro-ui
{"type":"card","title":"🪙 抛硬币","children":[
  {"type":"text","value":"点一下，看正反。","style":"body"},
  {"type":"button","label":"抛！","variant":"filled","action":{"type":"callback","event":"coin"}},
  {"type":"badge","text":"？","background":"secondary"}
]}
```
收到 【coin】 → 随机 正/反，更新 badge。

■ 11. 掷骰子（1-6 / 双骰）
```quro-ui
{"type":"card","title":"🎲 掷骰子","children":[
  {"type":"text","value":"点一下掷骰。","style":"body"},
  {"type":"row","children":[
    {"type":"button","label":"掷 1 颗","variant":"filled","action":{"type":"callback","event":"dice1"}},
    {"type":"button","label":"掷 2 颗","variant":"outlined","action":{"type":"callback","event":"dice2"}}
  ]},
  {"type":"badge","text":"？","background":"primary"}
]}
```
收到 【dice1/dice2】 → 随机点数，更新 badge（双骰给和值）。

■ 12. 五子棋人机（15×15，进阶）
用 game_board rows:15,cols:15；cellAction event=gomoku_move；AI 收到落子后按「堵四/活三/进攻」简单策略落子，五连判胜。状态串 gomoku=15×15 网格（⬛/⚪/⬜）。

■ 13. 数字华容道（3×3 滑块，进阶）
game_board 3×3，cells 初始打乱（含一个空位 ⬜），点相邻数字滑入空位；按 12345678⬜ 顺序即胜。收到 【slide】 row/col 判定是否与空位相邻并交换。

■ 14. 知识竞答（百科/常识，计分）
```quro-ui
{"type":"card","title":"🧠 知识竞答","children":[
  {"type":"text","value":"看题，选出你的答案。","style":"body"},
  {"type":"text","value":"Q：世界上最长的河流是？","style":"title"},
  {"type":"row","children":[
    {"type":"button","label":"A 尼罗河","variant":"outlined","action":{"type":"callback","event":"qa","data":{"ans":"A"}}},
    {"type":"button","label":"B 亚马逊","variant":"outlined","action":{"type":"callback","event":"qa","data":{"ans":"B"}}}
  ]},
  {"type":"badge","text":"得分 0","background":"primary"}
]}
```
收到 【qa】 ans → 判定正误、出下一题、更新得分 badge；可多选项/多题。

■ 15. 猜谜语
text_input 收谜底，AI 出题并校验，badge 计猜中数；可加「提示」按钮。

■ 16. 2048（4×4，方向键合并，记分数）
```quro-ui
{"type":"card","title":"🔢 2048","children":[
  {"type":"text","value":"方向键滑动合并相同数字，凑出 2048。","style":"body"},
  {"type":"game_board","id":"g2048","rows":4,"cols":4,
   "cells":["0","0","0","0","0","0","0","0","0","0","0","0","0","0","0","0"],
   "cellAction":{"type":"callback","event":"g2048_tap"}},
  {"type":"row","children":[
    {"type":"button","label":"↑","variant":"outlined","action":{"type":"callback","event":"g2048_move","data":{"dir":"up"}}},
    {"type":"button","label":"↓","variant":"outlined","action":{"type":"callback","event":"g2048_move","data":{"dir":"down"}}},
    {"type":"button","label":"←","variant":"outlined","action":{"type":"callback","event":"g2048_move","data":{"dir":"left"}}},
    {"type":"button","label":"→","variant":"outlined","action":{"type":"callback","event":"g2048_move","data":{"dir":"right"}}}
  ]},
  {"type":"badge","text":"分数 0","background":"primary"},
  {"type":"button","label":"重开","variant":"text","action":{"type":"callback","event":"g2048_reset"}}
]}
```
收到 【g2048_move】 dir → 按方向把同值块合并（"0" 视为空），合并后在随机空位生成 2；重渲 board 与分数 badge；出现 2048 给通关提示，无空位且无可合并则判负。g2048_reset 重置。

■ 17. 扫雷（网格，点击揭雷 / 插旗）
用 game_board（默认 9×9，cells 初始 "□"）。两个交互：cellAction event=mine_tap（点格）→ AI 揭开（保证首次安全；踩雷判负并揭示全部雷；否则显示周围 8 格雷数）；一个 switch「插旗模式」event=mine_toggle 切到插旗后，再点格走同一 event=mine_tap 改为插旗/取消（🚩）。badge 显示剩余雷数；揭开所有非雷格即胜。mine_reset 重开。
（示例卡片：9×9 board + 插旗 switch + 雷数 badge + 重开按钮。）

■ 18. 黑白棋 / 奥赛罗（8×8，落子翻子）
game_board 8×8，cells 用 "⚫"(你)/"⚪"(AI)/" "(空)。cellAction event=reversi_move（row/col）→ 你落 ⚫，AI 用「夹击翻子」策略回敬（任一方向被夹住的 ⚪ 翻成 ⚫）；某方无合法落子则跳过；棋盘满或双方均无子可落时按子数判胜负。badge 显示比分。

■ 19. 推箱子（网格，方向推箱）
game_board 用字符表达：墙 "🧱"、箱 "📦"、目标 "🎯"、人 "🧍"、空 "·"。四个方向按钮 event=sokoban_move data{dir} → 人朝该向走；前方是箱且再前一格为空/目标则推动；箱到目标记 ✅。全部箱到目标即胜；sokoban_reset 重开（可用 run_code 生成关卡更稳）。

■ 20. 数独（9×9，点格填数）
game_board 9×9，预填数字字符串、空格用 " "。先用 cellAction event=sudoku_pick（row/col）选中一格，再给一排 1-9 数字按钮 event=sudoku_fill data{n} 填入选中格；AI 校验该格所在行/列/3×3 宫无重复（冲突标 ❌）。badge 显示错误数；全部合法填满即胜。sudoku_new 换一题。

■ 21. 连连看（配对消除）
game_board 铺满成对 emoji（🍎🍌🍇🍊🍇…），cells 为 emoji 或 " "。cellAction event=onet_pick（row/col）记第一张，再点第二张：同图标则两格置 " " 消除（路径判定太复杂可放宽成「同图标即配」）；否则取消选择。badge 显示剩余对数；清空即胜。

■ 22. 打地鼠（出洞即敲，计得分）
game_board 3×3（9 个洞 "🕳️"），地鼠 "🐹"。按钮「出地鼠」event=whack_spawn → AI 随机在 1-3 个洞放 🐹 并重渲；点 🐹 格 event=whack_hit → +1 分并清该洞，点空洞记 miss。badge 显示得分；连续出洞计分。whack_reset 清零。

■ 23. 迷宫（网格，方向走）
game_board：墙 "🧱"、路 "·"、起点 "🚩"、终点 "🏁"、人 "🧍"。四个方向按钮 event=maze_move data{dir} → 前方非墙则移动人；走到 🏁 即胜。maze_new 生成新迷宫（AI 用 run_code 生成合法迷宫更稳）。

■ 24. 消消乐（交换相邻，三连消）
game_board 铺彩色 emoji（🔴🟡🟢🔵🟣），cells 为 emoji 或 " "。cellAction event=match_pick（row/col）选中；再点相邻格交换：若形成横向/纵向 ≥3 同色则消除并下落补位（AI 用 run_code 算重力与连锁），否则换回。badge 显示分数；无可行步则 match_new 重排。

■ 25. 贪吃蛇（回合制，方向前进）
game_board（如 10×10）：蛇身 "🟩"、蛇头 "🟢"、食物 "🍎"、空 "·"。四个方向按钮 event=snake_dir data{dir} → 改方向并前进一步：吃到食物变长+分，撞墙/撞自身判负。badge 显示长度/分数；snake_reset 重开。（每按一次 = 走一步，无需实时。）

■ 26. 俄罗斯方块简易（回合制落块）
game_board（10×20 竖向）：当前方块 "🟦"、已落定 "🟫"、空 "·"。按钮 左/右/旋转/下落（event=tetris_action data{move:"left|right|rotate|drop"}）→ AI 移动或旋转当前块、落底锁定、消满行计分。badge 显示分数/行数；堆顶溢出判负。tetris_reset 重开。（简化回合制，非实时下落。）

■ 27. 飞行棋竞速（掷骰前进，你 vs AI）
game_board 用 1×20 直线赛道（cells 为序号或 "·"，你与 AI 的棋子用不同 emoji 标记）+ 掷骰按钮 event=ludo_roll → 你掷 1-6 前进，AI 也掷；先到终点格即胜。badge 显示双方位置。ludo_reset 重开。

■ 28. 数织 / Nonogram（按行列线索涂格）
game_board（如 10×10），cells 初始 " "（空）可由 cellAction event=nono_tap（row/col）切换填 "■"。卡片里用 text 给出每行/列线索（如「3 1 2」）。AI 比对隐藏解；badge 显示完成度；全部匹配即胜。nono_new 换图。

■ 现场发明新游戏
用户说「玩个 XX」→ 你用一段话定规则 + 选组件（game_board/button/text_input/stat/badge）画出面板，用 callback 回收操作，在推理里当裁判。需要严谨逻辑就用 tool_call→run_code。上不封顶，尽情发挥。""".trimIndent()
    }
}
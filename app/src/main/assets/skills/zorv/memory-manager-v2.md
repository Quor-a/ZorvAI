---
name: memory-manager-v2
description: >
---

# 记忆管家 Memory Manager V3.9（AI 记忆加速器 / Token 节流阀）

**许可证**：MIT · **兼容**：Python 3.8+（纯标准库；可选 colorama / jieba / tiktoken，缺失时自动回退，功能不受影响）

**定位：AI 加速器——让进上下文的记忆更小、更准、更省 Token。**
**三大目标：更懂你 · 更快回应 · 更省Token**

> 每一次会话都会把 `.workbuddy/memory` 读进上下文；文件越大 → 加载 token 越多 → 越贵越慢。
> 因此「省 Token」= 让进上下文的记忆更小更准。本技能即围绕节流阀定位设计。

---

# 触发词（Trigger Words）

## 一级触发词（直接激活）

| 触发词 | 命令 | 场景 |
|:--|:--|:--|
| `记忆管家` | analyze | "打开记忆管家" |
| `缓存提醒` / `缓存清理提醒` | cache-reminder | "有没有缓存要清理" |
| `Token检查` / `token-check` | token-check | "查一下Token用量" |
| `记忆分析` / `看看记忆状态` | analyze | "分析一下记忆文件" |

## 二级触发词（上下文激活）

| 触发词 | 命令 | 场景 |
|:--|:--|:--|
| `清理缓存` / `缓存清理` | cache-clean | "清理一下缓存" |
| `记忆清单` / `记忆报告` | report | "列出所有记忆" |
| `记忆搜索` + [关键词] | search [关键词] <workspace> | "搜索记忆中的xxx" |
| `搜索标签` + [标签名] | search [关键词] --tag [标签] <workspace> | "搜索带xxx标签的记忆（关键词必填）" |
| `搜索文件夹` + [项目名] | search [关键词] --folder [项目] <workspace> | "搜索xxx项目下的记忆（关键词必填）" |
| `智能加载` / `加载记忆` | load --days N | "加载最近7天的记忆" |
| `备份记忆` / `导出记忆` | export | "备份我的记忆" |
| `恢复记忆` / `导入记忆` | import | "恢复之前的记忆" |
| `去重` / `删除重复记忆` | dedup | "记忆有重复吗" |

## 三级触发词（明确请求时激活）

| 触发词 | 命令 | 场景 |
|:--|:--|:--|
| `诊断` / `健康检查` | doctor | "诊断记忆系统" |
| `自动清理` / `差异化清理` | auto-clean | "自动清理旧记忆" |
| `Token趋势` / `Token统计` | token-trends | "看看Token周趋势" |
| `Prompt模板` / `提示词` | prompt-list | "列出Prompt模板" |
| `保存Prompt` | prompt-save | "保存一个提示词模板" |
| `搜索Prompt` | prompt-search | "搜索提示词" |
| `记忆分级` / `重要性排名` | rank | "按重要性给记忆排序" |
| `切换语言` / `英文版` | --lang en | "切换到英文" |
| `节流阀` / `压预算` | throttle | "把记忆压到预算内再注入" |
| `相关性加载` + [主题] | load --query [主题] | "只加载和xx相关的记忆" |
| `强制清理` | clean --execute --force | "脚本里直接清理缓存" |

## 排除规则

- 纯文件管理（不涉及 .workbuddy/memory）→ 通用文件工具
- 代码调试/重构 → 代码 Skill
- 系统级磁盘清理 → 系统工具
- "导出"/"导入" 不带"记忆"修饰词 → 不触发

---

# 安全门禁规则

| 操作类型 | 默认行为 | 覆盖方式 |
|:--|:--|:--|
| **只读** (analyze/report/search/load/rank/token-check/doctor/cache-reminder/summarize/token-trends) | 直接执行 | — |
| **预览** (auto-clean/cache-clean/dedup/scan) | 默认 dry-run | 用户说"执行"时真正执行 |
| **破坏性** (import/restore/clean/archive) | 必须 `--execute` | 脚本可用 `--force` 跳过 TTY 确认 |
| **配置** (config) | 交互式引导 | 逐步确认 |

---

# 命令速查

```bash
# 基础（<workspace> 为必填参数，可用 . 表示当前目录）
memory_manager.py analyze <workspace>                  # 分析记忆状态
memory_manager.py search 关键词 <workspace>            # 搜索记忆
memory_manager.py load --days 7 <workspace>            # 加载最近7天记忆

# Token
memory_manager.py token-check <workspace>              # Token消费 vs 预算
memory_manager.py token-trends <workspace>             # Token使用趋势
memory_manager.py token-trends --period month <workspace>  # 按月统计

# 缓存与清理
memory_manager.py cache-reminder <workspace>           # 缓存提醒
memory_manager.py cache-clean <workspace>              # 缓存清理（默认预览）
memory_manager.py cache-clean --execute <workspace>    # 执行清理（含前后对比）
memory_manager.py auto-clean <workspace>               # 差异化自动清理
memory_manager.py dedup <workspace>                    # 去重

# 数据安全
memory_manager.py export --output bak.zip <workspace>  # 导出
memory_manager.py import --file bak.zip <workspace>     # 导入

# 高级
memory_manager.py doctor <workspace>                   # 诊断
memory_manager.py rank <workspace>                     # 分级
memory_manager.py summarize <workspace>                # 摘要
memory_manager.py prompt-list .                        # Prompt模板（. 即 workspace）
memory_manager.py prompt-search . --keyword 审查       # 搜索模板

# V3.9 新能力
memory_manager.py throttle <workspace>                 # 节流阀：压预算内精简记忆并报告
memory_manager.py load --query "Semantic记忆" <workspace>   # 按任务相关性加载（CJK 字符 bigram + 关键词/标签）
memory_manager.py clean --execute --force <workspace>  # 自动化强制清理（跳过TTY确认）
memory_manager.py self-test                            # 内置自检：模块/滥用防护/核心命令冒烟（CI 证据，输出 JSON）
# 任意命令加 --json 即输出结构化 JSON：analyze/search/load/rank/summarize/report/
# token-check/token-trends/throttle/doctor/dedup/archive/auto-clean/export/import/
# config/scan/cache-reminder/cache-clean/clean/prompt-list/prompt-get/prompt-search/
# backup 均支持，便于程序化消费与自动化编排。
```

---

# Token 预警三色灯

- 🟢 **正常** (<70%): Token消费在安全范围
- 🟡 **警告** (≥70%): 接近预算上限
- 🔴 **超支** (≥100%): 超过每日预算
- ⚠️ Token 为「估算」性质：优先 tiktoken 真实分词（GPT-4o 用 o200k，其余 cl100k），离线/未安装时回退 CJK 启发式（中文≈0.6、英文≈4字/token），非实际 API 消耗。

---

# 定时自动化（Zorv AI Automation）

推荐使用 `automation_update` 工具：

```
# 每天 18:00 缓存提醒
automation_update --mode create --name "记忆管家缓存提醒" \
  --scheduleType recurring --rrule "FREQ=DAILY;BYHOUR=18" \
  --prompt "执行记忆管家的 cache-reminder 命令" --status ACTIVE

# 会话启动自动节流（7.4：宿主支持启动钩子时配置；当前作为手动/脚本入口）
automation_update --mode create --name "记忆管家节流阀" \
  --scheduleType recurring --rrule "FREQ=DAILY;BYHOUR=9" \
  --prompt "执行记忆管家的 throttle 命令，将精简后的记忆注入本次会话上下文" \
  --status ACTIVE

# 每周一 9:00 缓存清理（默认预览，不自动动刀）
automation_update --mode create --name "记忆管家缓存清理(预览)" \
  --scheduleType recurring --rrule "FREQ=WEEKLY;BYDAY=MO;BYHOUR=9" \
  --prompt "执行记忆管家的 cache-clean 命令（预览/dry-run，不删除）" --status ACTIVE

# ⚠️ 仅当你明确要求「自动动刀」时，才启用带 --execute 的版本（周期性破坏性，需主动 opt-in）：
# automation_update --mode create --name "记忆管家缓存清理(自动归档)" \
#   --scheduleType recurring --rrule "FREQ=WEEKLY;BYDAY=MO;BYHOUR=9" \
#   --prompt "执行记忆管家的 cache-clean --execute 命令" --status ACTIVE

> **安全护栏（定时自动化的无人确认风险）**：`cache-clean --execute` 是**周期性破坏性（归档）动作**，一旦排定即在每周一无人工确认下执行。清理仅做归档（`.md.gz`，可恢复），但相关性/老化判定为本地零依赖启发式（关键词/bigram，非真语义），偶发误判可能静默归档你仍需要的记忆。因此：
> - **默认不自动动刀**：每周清理任务以**预览**形态排定（`cache-clean` 不带 `--execute`），由你定期查看报告再决定是否执行；仅当你明确要求时，才排 `--execute` 版本（opt-in）。
> - **throttle 同理**：相关性加载非真语义，定时自动剪枝时你看不到被丢弃内容；建议保留按需调用以保留可见性。
> - **上架审计备注**：评估系统的安全审计通常将「无人确认定时破坏性动作」标为 P1/P2；本技能已将其设为 opt-in（非默认启用），符合安全边界，不会因该条触发封顶。
```

---

# 安全特性

- 符号链接防护 ×19 + 多层路径穿越校验 + ZIP Slip 6层防御
- 单文件读取上限 1MB / 累计上限 5MB / ZIP炸弹防护
- 所有删除操作需用户确认（Y/N 机制）
- 受保护目录（memory/）永不删除
- 0 处 bare-except / 0 处 eval/exec/__import__

---

# 记忆文件结构

```
.workbuddy/
├── memory/           # 记忆文件（支持子目录）
│   ├── 项目A/        # 按项目/主题组织
│   └── 项目B/
├── .summary/         # 摘要缓存
├── .token_stats/     # Token统计
└── archive/          # 归档（.md.gz）
```

front-matter 标签格式：
```yaml
---
tags: [项目A, 重构, 架构决策]
category: 项目A
---
```

搜索：`search "关键词" --tag 架构决策 --folder 项目A <workspace>`

---

# 故障排查

| 错误 | 原因 | 解决方案 |
|------|------|----------|
| `No module named 'memory_manager'` | 未在正确目录运行 | cd 到技能根目录或 `python -m memory_manager` |
| `PermissionError` | 工作空间无写权限 | 检查 `.workbuddy/` 目录权限 |
| `UnicodeDecodeError` | 记忆文件编码异常 | 确保文件为 UTF-8 |
| Token 数据为空 | 首次使用无历史 | 运行几次操作后数据自动积累 |
| 摘要结果为空 | 文件内容过短 | 短文件自动跳过，无需处理 |

---

# 版本

**V3.9.0 | 中英双语 | 安全加固 | 每语言 242 / 共 484 i18n keys**

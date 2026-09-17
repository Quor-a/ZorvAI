# 插件 Skills 技术构架

> ZorvAI / WorkBuddy · 可安装技能插件

## 构架介绍

**插件 Skills** 是 Skills 体系中「可分发、可安装」的扩展形态。相对**内置技能**（随平台发布、默认可信），插件技能来自**市场 / 外部仓库 / URL**，以 `SKILL.md` + `scripts/` + `references/` + `assets/` 打包，经 `find-skills` 发现、`skills-security-check` 审计、`SkillManage` 安装后，与主理人运行时动态绑定。

它让 ZorvAI 的能力像插件一样热插拔扩展，并可与 **MCP 连接器桥接**（connector-bridged skills），把外部服务封装成「会自己调工具的技能」。所有安装动作强制走安全审计，按 P0（致命）/ P1（警告）/ P2（安全）定级，P0 须用户显式确认。

- **外部来源**：市场检索、Git 仓库 import、URL 下载，区别于内置技能。
- **安装即审计**：`skills-security-check` 扫描 SKILL.md 与脚本，输出风险报告。
- **两级落盘**：用户级 `~/.workbuddy/skills/`（跨项目）/ 项目级 `{workspace}/.workbuddy/skills/`（团队）。
- **连接器桥接**：插件技能可内嵌 MCP 调用，成为「带外部能力的技能」。

## 分层技术构架

```mermaid
graph TB
  subgraph L5[集成层]
    A1[Agent Loop 动态调用] --> A2[expert-manager / Connector 桥接]
  end
  subgraph L4[绑定层]
    B1[Skill 工具装载] --> B2[上下文注入 / 反思修正]
  end
  subgraph L3[审计层]
    C1[skills-security-check] --> C2[P0/P1/P2 定级 / 用户确认闸门]
  end
  subgraph L2[管理层]
    D1[SkillManage 建改删列] --> D2[marketplace 安装器]
    D2 --> D3[用户级/项目级落盘]
  end
  subgraph L1[发现层]
    E1[find-skills 检索] --> E2[recommend-connectors / 市场仓库URL源]
  end
  subgraph L0[包体层]
    F1[SKILL.md] --> F2[scripts/references/assets / agent_created]
  end
```

## 核心组件

| 组件 | 职责 | 关键点 |
|---|---|---|
| SKILL.md | 插件定义文件 | frontmatter 声明名称、描述、触发词、位置 |
| find-skills | 发现可安装插件 | 能力缺口时首调，禁止直接说「做不到」 |
| skills-security-check | 安装前审计 | 输出 P0/P1/P2，P0 须用户确认 |
| SkillManage | 安装/修改/删除 | 用户级优先，除非要求项目级 |
| marketplace 安装器 | 从推荐市场安装 | 一句话检索并安装技能 |
| Connector 桥接 | 技能内嵌 MCP 调用 | 把外部服务封装为「带能力的技能」 |

## 安装生命周期

1. **发现**：能力缺口 → `find-skills` 检索市场/仓库/URL
2. **获取**：download / import / URL 拉取插件包体
3. **审计**：`skills-security-check` 扫描，出 P0-P2 报告
4. **确认**：P0 风险须用户显式确认，P1 警告提示
5. **落盘**：`SkillManage` 写入用户级/项目级 skills 目录
6. **装载**：命中触发词 → Skill 工具注入上下文执行；可经连接器桥接外部能力

## 与内置 Skills 的区别

| 维度 | 内置 Skills | 插件 Skills |
|---|---|---|
| 来源 | 随平台发布、默认可信 | 外部市场/仓库/URL |
| 安全审计 | 不需要 | 强制 P0/P1/P2 审计 |
| 生命周期 | 不可热卸 | 可热装热卸 |
| 连接器 | 可选 | 可内嵌 MCP 桥接 |

两者共用同一套 `SKILL.md` 格式、`SkillManage` 生命周期与两级目录；差异仅在**来源可信度**与**是否强制安全审计**。

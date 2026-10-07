# -*- coding: utf-8 -*-
"""追加集群 UI 的 i18n 键。

铁律：
  - 键格式 qk_%05d，**只能追加不能改已有键**；
  - getString(id, *args) 无条件走 String.format，所以带占位符的键必须 formatted="false"
    之外的都要显式声明 args 数量正确；裸 % 会在真机崩；
  - 中英两套都要写（values/ 与 values-en/）。
原子写：.tmp + os.replace。
"""
import os
import re

ROOT = "D:/Calw OS-project/QuroAI/app/src/main/res"

# (键, 中文, 英文, 是否有格式化参数)
ITEMS = [
    ("qk_03961", "例如：写一篇对比三款国产芯片的短文，要数据有来源", "e.g. Write a short comparison of three domestic chips, with sourced data", False),
    ("qk_03962", "主持 + 0 个角色", "Host + 0 roles", False),
    ("qk_03963", "刷新列表", "Refresh", False),
    ("qk_03964", "新建", "Create", False),
    ("qk_03965", "创建默认集群（主持 + 研究员 / 写手 / 校对）", "Create default cluster (host + researcher / writer / reviewer)", False),
    ("qk_03966", "身份锁定", "identity locked", False),
    ("qk_03967", "模型：%1$s", "Model: %1$s", True),
    ("qk_03968", "%1$d 人提案 · %2$d 技能", "%1$d proposers · %2$d skills", True),
    ("qk_03969", "（未设置工作内容）", "(no duties set)", False),
    ("qk_03970", "%1$s", "%1$s", True),
    ("qk_03971", "技能 %1$d", "skill %1$d", True),
    ("qk_03972", "工具 %1$d", "tools %1$d", True),
    ("qk_03973", "AI 创建", "AI-made", False),
    ("qk_03974", "添加角色", "Add role", False),
    ("qk_03975", "让 AI 起草配置", "Let AI draft this role", False),
    ("qk_03976", "删除角色", "Delete role", False),
    ("qk_03977", "身份 / 角色 / 提示词 / 职责 / 触发词 / 模型 / 技能 均可修改", "Identity, persona, prompt, duties, triggers, model and skills are all editable", False),
    ("qk_03978", "① 身份", "1. Identity", False),
    ("qk_03979", "名称", "Name", False),
    ("qk_03980", "图标", "Icon", False),
    ("qk_03981", "签名（显示在发言气泡上）", "Signature (shown on speech bubbles)", False),
    ("qk_03982", "自称（如：我 / 本官 / 本研究员）", "Self-reference (e.g. I / this official)", False),
    ("qk_03983", "② 角色设定", "2. Persona", False),
    ("qk_03984", "角色设定", "Persona", False),
    ("qk_03985", "它是谁、什么性格、说话风格", "Who it is, temperament, speaking style", False),
    ("qk_03986", "③ 系统提示词", "3. System prompt", False),
    ("qk_03987", "系统提示词", "System prompt", False),
    ("qk_03988", "留空则只用身份与角色设定", "Leave blank to use identity and persona only", False),
    ("qk_03989", "④ 工作内容（职责清单）", "4. Duties", False),
    ("qk_03990", "添加一项职责", "Add a duty", False),
    ("qk_03991", "⑤ 禁忌（绝不做的事）", "5. Taboos", False),
    ("qk_03992", "添加一条禁忌", "Add a taboo", False),
    ("qk_03993", "⑥ 触发词（主持据此选人）", "6. Trigger keywords", False),
    ("qk_03994", "添加触发词", "Add a keyword", False),
    ("qk_03995", "命中才派活给它，留空则接所有指派", "Only assigned on match; blank means accepts any assignment", False),
    ("qk_03996", "⑦ 模型绑定（可与其它角色不同）", "7. Model binding (may differ per role)", False),
    ("qk_03997", "⑧ 技能", "8. Skills", False),
    ("qk_03998", "权限与限额", "Permissions and limits", False),
    ("qk_03999", "保存", "Save", False),
    ("qk_04000", "取消", "Cancel", False),
    ("qk_04001", "让 AI 起草角色配置", "Let AI draft a role", False),
    ("qk_04002", "描述你缺什么样的角色，AI 生成身份、职责、禁忌与提示词，你再修改。", "Describe the role you need; AI drafts identity, duties, taboos and prompt for you to edit.", False),
    ("qk_04003", "删除角色「%1$s」？", "Delete role \"%1$s\"?", True),
    ("qk_04004", "删除后该角色不再被主持选派。历史消息与产物保留。", "It will no longer be selected by the host. History and artifacts are kept.", False),
    ("qk_04005", "删除", "Delete", False),
    ("qk_04006", "主持身份：内置不可修改", "Host identity: built-in and immutable", False),
    ("qk_04007", "主持模型", "Host model", False),
    ("qk_04008", "编排策略", "Orchestration policy", False),
    ("qk_04009", "终止策略", "Termination policy", False),
    ("qk_04010", "风格微调（不改身份与职责）", "Style only (identity and duties untouched)", False),
    ("qk_04011", "添加", "Add", False),
    ("qk_04012", "未读到宿主模型列表，将沿用当前绑定。", "No host models found; keeping the current binding.", False),
    ("qk_04013", "宿主模型配置 ID", "Host model config ID", False),
    ("qk_04014", "降级链（逗号分隔）", "Fallback chain (comma separated)", False),
    ("qk_04015", "失败时依次切换", "Switch to these in order on failure", False),
    ("qk_04016", "▶ 请求参数与上下文隔离", "> Request params and context isolation", False),
    ("qk_04017", "历史窗口（轮）", "History window (turns)", False),
    ("qk_04018", "黑板键白名单（留空=全部）", "Blackboard key allowlist (blank = all)", False),
    ("qk_04019", "逗号分隔", "Comma separated", False),
    ("qk_04020", "上下文 token 上限", "Context token cap", False),
    ("qk_04021", "可看到其它角色的发言", "Can see other roles' utterances", False),
    ("qk_04022", "可看到历史产物", "Can see past artifacts", False),
    ("qk_04023", "允许调用工具", "Allow tool calls", False),
    ("qk_04024", "允许修改自己的提示词", "Allow modifying its own prompt", False),
    ("qk_04025", "允许创建新角色", "Allow creating new roles", False),
    ("qk_04026", "允许安装技能", "Allow installing skills", False),
    ("qk_04027", "每任务最多轮数", "Max turns per task", False),
    ("qk_04028", "每轮最多工具调用", "Max tool calls per turn", False),
    ("qk_04029", "编排模式", "Orchestration mode", False),
    ("qk_04030", "一次点名几个角色提方案", "How many roles propose at once", False),
    ("qk_04031", "提案并行且互相隔离", "Proposals run in parallel, isolated from each other", False),
    ("qk_04032", "启用批评者", "Enable critic", False),
    ("qk_04033", "收尾前必须验收", "Verify before close", False),
    ("qk_04034", "出错时重规划", "Replan on error", False),
    ("qk_04035", "最大轮数", "Max turns", False),
    ("qk_04036", "墙钟上限（分钟）", "Wall clock cap (minutes)", False),
    ("qk_04037", "token 上限（千）", "Token cap (thousands)", False),
    ("qk_04038", "停滞判定窗口（轮）", "No-progress window (turns)", False),
    ("qk_04039", "停滞时的动作", "Action when stagnant", False),
    ("qk_04040", "尚未安装任何技能。安装后即可在此按角色挂载。", "No skills installed yet. Install some to mount them per role.", False),
    ("qk_04041", "生成", "Generate", False),
    ("qk_04042", "%1$dK token", "%1$dK tokens", True),
    ("qk_04043", "子任务", "Subtasks", False),
    ("qk_04044", "插入", "Inject", False),
    ("qk_04045", "待指派", "unassigned", False),
    ("qk_04046", "计划 %1$d 个子任务", "Planned %1$d subtasks", True),
    ("qk_04047", "换模型 %1$s → %2$s", "Model switched %1$s to %2$s", True),
    ("qk_04048", "重规划 #%1$d：%2$s", "Replan #%1$d: %2$s", True),
    ("qk_04049", "升级人工：%1$s", "Escalated: %1$s", True),
    ("qk_04050", "预算超限：%1$s", "Budget exceeded: %1$s", True),
    ("qk_04051", "错误：%1$s", "Error: %1$s", True),
]


def patch(path, is_default):
    with open(path, "rb") as f:
        raw = f.read()
    text = raw.decode("utf-8")

    added = 0
    skipped = []
    for key, zh, en, _has_args in ITEMS:
        if f'name="{key}"' in text:
            skipped.append(key)
            continue
        value = zh if is_default else en
        # 转义必须先做：& < > 在 XML 里裸写会解析失败
        value = (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        entry = f'    <string name="{key}">{value}</string>\n'
        # 追加到 </resources> 前，保持缩进风格
        idx = text.rindex("</resources>")
        text = text[:idx] + entry + text[idx:]
        added += 1

    if added == 0:
        print(f"  skip (all present): {path}")
        return

    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, path)

    # 复核：前 200 字节必须完好，且新键都在
    check = open(path, "rb").read()
    assert check.startswith(raw[:200]), f"前文被破坏: {path}"
    assert b"</resources>" in check
    for key, _, _, _ in ITEMS:
        assert f'name="{key}"'.encode() in check, f"{key} 缺失于 {path}"
    print(f"  +{added} keys -> {path} (skipped existing: {len(skipped)})")


print("appending cluster i18n keys:")
patch(f"{ROOT}/values/strings_i18n.xml", True)
patch(f"{ROOT}/values-en/strings_i18n.xml", False)
print("done")

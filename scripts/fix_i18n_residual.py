# -*- coding: utf-8 -*-
"""修复 i18n 转换后残留在「非 Composable 作用域」的 stringResource 调用。
这些位置不能调用 @Composable 的 stringResource，改为 ctx/context.getString（各作用域均持有 Context）。
另修复 Toast 首参被误写成 ctx 的问题（应直接用 Toast 自己的 Context 表达式）。
"""
import io, sys

ROOT = 'app/src/main/java/com/ai/assistance/quro/'

# (相对路径, old, new, 期望替换次数)
FIXES = [
    # ---- Toast 首参误用（应使用 Toast 自身的 Context 表达式） ----
    ('activity/QuroMainActivity.kt',
     'Toast.makeText(this, ctx.getString(R.string.qk_03726), Toast.LENGTH_LONG)',
     'Toast.makeText(this, getString(R.string.qk_03726), Toast.LENGTH_LONG)', 1),

    ('ui/ChatScreen.kt',
     'Toast.makeText(appCtx, context.getString(R.string.qk_00020), Toast.LENGTH_LONG)',
     'Toast.makeText(appCtx, appCtx.getString(R.string.qk_00020), Toast.LENGTH_LONG)', 1),
    ('ui/ChatScreen.kt',
     'android.widget.Toast.makeText(appCtx, context.getString(R.string.qk_00056), android.widget.Toast.LENGTH_SHORT)',
     'android.widget.Toast.makeText(appCtx, appCtx.getString(R.string.qk_00056), android.widget.Toast.LENGTH_SHORT)', 1),
    ('ui/ChatScreen.kt',
     'android.widget.Toast.makeText(appCtx, context.getString(R.string.qk_00057), android.widget.Toast.LENGTH_LONG)',
     'android.widget.Toast.makeText(appCtx, appCtx.getString(R.string.qk_00057), android.widget.Toast.LENGTH_LONG)', 1),
    ('ui/ChatScreen.kt',
     'android.widget.Toast.makeText(appCtx, context.getString(R.string.qk_00058), android.widget.Toast.LENGTH_SHORT)',
     'android.widget.Toast.makeText(appCtx, appCtx.getString(R.string.qk_00058), android.widget.Toast.LENGTH_SHORT)', 1),

    # ---- ifBlank / getOrElse 等非 Composable lambda 内的 stringResource ----
    ('ui/ChatScreen.kt',
     'cfg.model.ifBlank { stringResource(R.string.qk_00015) }',
     'if (cfg.model.isBlank()) stringResource(R.string.qk_00015) else cfg.model', 2),
    ('ui/ChatScreen.kt',
     'r.output.ifBlank { stringResource(R.string.qk_00338) }',
     'r.output.ifBlank { ctx.getString(R.string.qk_00338) }', 1),
    ('ui/ChatScreen.kt',
     'stringResource(R.string.qk_00066) + txt.take(200)',
     'ctx.getString(R.string.qk_00066) + txt.take(200)', 1),

    # ---- formatGroup / toHistoryItem：顶层函数，补 Context 形参 ----
    ('ui/ChatScreen.kt',
     'fun QuroConversationMeta.toHistoryItem(active: Boolean): HistoryItem {',
     'fun QuroConversationMeta.toHistoryItem(ctx: Context, active: Boolean): HistoryItem {', 1),
    ('ui/ChatScreen.kt',
     'group = formatGroup(updatedAt),',
     'group = formatGroup(ctx, updatedAt),', 1),
    ('ui/ChatScreen.kt',
     'private fun formatGroup(ts: Long): String {',
     'private fun formatGroup(ctx: Context, ts: Long): String {', 1),
    ('ui/ChatScreen.kt',
     'diffDays < 1 -> stringResource(R.string.qk_00284)',
     'diffDays < 1 -> ctx.getString(R.string.qk_00284)', 1),
    ('ui/ChatScreen.kt',
     'diffDays < 7 -> stringResource(R.string.qk_00285)',
     'diffDays < 7 -> ctx.getString(R.string.qk_00285)', 1),
    ('ui/ChatScreen.kt',
     'else -> stringResource(R.string.qk_00286)',
     'else -> ctx.getString(R.string.qk_00286)', 1),
    ('ui/ChatScreen.kt',
     '    val history = remember(conversations, currentId) {\n'
     '        conversations.map { it.toHistoryItem(it.id == currentId) }\n'
     '    }',
     '    val historyCtx = LocalContext.current\n'
     '    val history = remember(conversations, currentId) {\n'
     '        conversations.map { it.toHistoryItem(historyCtx, it.id == currentId) }\n'
     '    }', 1),

    # ---- 其它非 Composable 作用域 ----
    ('ui/EditorScreen.kt',
     '}.getOrElse { e -> stringResource(R.string.qk_00383) }',
     '}.getOrElse { e -> ctx.getString(R.string.qk_00383) }', 1),
    ('ui/LocalOfficeEditorScreen.kt',
     'else -> stringResource(R.string.qk_00399)',
     'else -> ctx.getString(R.string.qk_00399)', 1),
    ('ui/QuroCmsScreen.kt',
     'ClipData.newPlainText(stringResource(R.string.qk_01563), enginePackage.bootstrapContent)',
     'ClipData.newPlainText(ctx.getString(R.string.qk_01563), enginePackage.bootstrapContent)', 1),
    ('ui/QuroCmsScreen.kt',
     'ClipData.newPlainText(stringResource(R.string.qk_01566), enginePackage.provisionerContent)',
     'ClipData.newPlainText(ctx.getString(R.string.qk_01566), enginePackage.provisionerContent)', 1),
    ('ui/QuroGitHubScreen.kt',
     '.ifBlank { stringResource(R.string.qk_01984) }',
     '.ifBlank { ctx.getString(R.string.qk_01984) }', 1),
    ('ui/QuroModelConfigScreen.kt',
     'presetName.trim().ifBlank { stringResource(R.string.qk_02135, (savedProfiles.size + 1).toString()) }',
     'presetName.trim().ifBlank { ctx.getString(R.string.qk_02135, (savedProfiles.size + 1).toString()) }', 1),
    ('ui/QuroToolCenterScreen.kt',
     'val res = runCatching { tool.run(context, arg) }.getOrElse { stringResource(R.string.qk_02795, (it.message).toString()) }',
     'val res = runCatching { tool.run(context, arg) }.getOrElse { context.getString(R.string.qk_02795, (it.message).toString()) }', 1),
    ('ui/QuroToolCenterScreen.kt',
     'stringResource(R.string.qk_02888, (failed.size).toString()) + failed.entries',
     'context.getString(R.string.qk_02888, (failed.size).toString()) + failed.entries', 1),

    # ---- getProviderDisplayName：顶层函数，补 Context 形参 + 全部调用点 ----
    ('ui/QuroModelConfigScreen.kt',
     'private fun getProviderDisplayName(provider: ApiProviderType): String =',
     'private fun getProviderDisplayName(ctx: Context, provider: ApiProviderType): String =', 1),
    ('ui/QuroModelConfigScreen.kt', 'getProviderDisplayName(selectedProvider)', 'getProviderDisplayName(ctx, selectedProvider)', 1),
    ('ui/QuroModelConfigScreen.kt', 'getProviderDisplayName(it)', 'getProviderDisplayName(ctx, it)', 1),
    ('ui/QuroModelConfigScreen.kt', 'getProviderDisplayName(provider)', 'getProviderDisplayName(ctx, provider)', 2),
    ('ui/QuroModelConfigScreen.kt', 'ApiProviderType.OLLAMA -> stringResource(R.string.qk_02205)', 'ApiProviderType.OLLAMA -> ctx.getString(R.string.qk_02205)', 1),
    ('ui/QuroModelConfigScreen.kt', 'ApiProviderType.OPENAI_LOCAL -> stringResource(R.string.qk_02206)', 'ApiProviderType.OPENAI_LOCAL -> ctx.getString(R.string.qk_02206)', 1),
    ('ui/QuroModelConfigScreen.kt', 'ApiProviderType.MNN -> stringResource(R.string.qk_02207)', 'ApiProviderType.MNN -> ctx.getString(R.string.qk_02207)', 1),
    ('ui/QuroModelConfigScreen.kt', 'ApiProviderType.LLAMA_CPP -> stringResource(R.string.qk_02208)', 'ApiProviderType.LLAMA_CPP -> ctx.getString(R.string.qk_02208)', 1),
    ('ui/QuroModelConfigScreen.kt', 'ApiProviderType.PPINFRA -> stringResource(R.string.qk_02209)', 'ApiProviderType.PPINFRA -> ctx.getString(R.string.qk_02209)', 1),
    ('ui/QuroModelConfigScreen.kt', 'ApiProviderType.OTHER -> stringResource(R.string.qk_02210)', 'ApiProviderType.OTHER -> ctx.getString(R.string.qk_02210)', 1),
]

total = miss = 0
for rel, old, new, expect in FIXES:
    p = ROOT + rel
    s = io.open(p, encoding='utf-8').read()
    n = s.count(old)
    if n == 0:
        print('MISS  %s :: %s' % (rel, old[:60].replace('\n', '\\n')))
        miss += 1
        continue
    if n != expect:
        print('WARN  %s :: found %d expected %d :: %s' % (rel, n, expect, old[:50].replace('\n', '\\n')))
    s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
    total += n
print('applied %d replacements, %d misses' % (total, miss))

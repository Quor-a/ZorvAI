# -*- coding: utf-8 -*-
"""把「逻辑用的数据串」从 qstr()/stringResource() 恢复成中文字面量。

这些不是展示文案而是判定数据（失败关键词表、时效关键词表、shell 命令、
书签迁移比对值…）。一旦随语言变化，中文输入就配不上，功能会静默失效——
这正是「功能像被删掉了」的真实来源之一。
"""
import io
import sys

RULES = [
    # ── 经验分类关键词表（QuroExperienceEngine）──
    ('app/src/main/java/com/ai/assistance/quro/core/experience/QuroExperienceEngine.kt', [
        ('listOf("报错", "崩溃", qstr(R.string.qk_02638), qstr(R.string.qk_00139), "crash"',
         'listOf("报错", "崩溃", "异常", "失败", "crash"'),
        ('listOf("解决", "修复", "方案", qstr(R.string.qk_00758), qstr(R.string.qk_00141), "workaround"',
         'listOf("解决", "修复", "方案", "可用", "成功", "workaround"'),
        ('listOf("模式", qstr(R.string.qk_00135), "用法"', 'listOf("模式", "工具", "用法"'),
        ('listOf(qstr(R.string.qk_01612), "兼容"', 'listOf("版本", "兼容"'),
    ]),
    # ── 工具结果失败判定关键词表（QuroAssistant.toolResultLooksFailed）──
    ('app/src/main/java/com/ai/assistance/quro/core/QuroAssistant.kt', [
        ('"调用失败", qstr(R.string.qk_01012), "请求失败"', '"调用失败", "执行失败", "请求失败"'),
        ('"操作失败", qstr(R.string.qk_00254), "任务失败", qstr(R.string.qk_02077),',
         '"操作失败", "运行失败", "任务失败", "连接失败",'),
        ('"加载失败", qstr(R.string.qk_02274), qstr(R.string.qk_00392), "提交失败"',
         '"加载失败", "生成失败", "保存失败", "提交失败"'),
        ('qstr(R.string.qk_01929), "无响应"', '"不可用", "无响应"'),
    ]),
    # ── 时效关键词表（IntentRouter，决定联网检索 freshness）──
    ('app/src/main/java/com/ai/assistance/quro/core/websearch/IntentRouter.kt', [
        ('listOf(qstr(R.string.qk_00284), "现在", qstr(R.string.qk_02665), "实时", "今日", "today", "now")',
         'listOf("今天", "现在", "刚刚", "实时", "今日", "today", "now")'),
        ('listOf(qstr(R.string.qk_00285), "这周", "近日", "最近", "recent")',
         'listOf("本周", "这周", "近日", "最近", "recent")'),
    ]),
    # ── 书签迁移比对值（QuroBrowserScreen）──
    ('app/src/main/java/com/ai/assistance/quro/ui/QuroBrowserScreen.kt', [
        ('val oldSeedTitles = setOf(qstr(R.string.qk_00879), qstr(R.string.qk_00880))',
         'val oldSeedTitles = setOf("开源地址（点击查看链接回答）", "链接·开源浏览器参考")'),
    ]),
    # ── shell 命令（QuroDevEnvTool 的 raw string，被错误塞进命令里）──
    ('app/src/main/java/com/ai/assistance/quro/core/tools/QuroDevEnvTool.kt', [
        ('|| echo qstr(R.string.qk_02025)', '|| echo "未安装"'),
    ]),
]


def main():
    apply = '--apply' in sys.argv
    total, miss = 0, []
    for path, rules in RULES:
        try:
            src = io.open(path, encoding='utf-8').read()
        except Exception as e:
            print('读取失败 %s: %s' % (path, e))
            continue
        n = 0
        for old, new in rules:
            c = src.count(old)
            if c == 0:
                miss.append('%s :: %s' % (path.split('/')[-1], old[:70]))
                continue
            src = src.replace(old, new)
            n += c
        if n and apply:
            io.open(path, 'w', encoding='utf-8', newline='').write(src)
        if n:
            print('%-42s 恢复 %d 处' % (path.split('/')[-1], n))
            total += n
    print('\n合计恢复 %d 处' % total)
    if miss:
        print('未命中 %d 条：' % len(miss))
        for m in miss:
            print('   ', m)


if __name__ == '__main__':
    main()

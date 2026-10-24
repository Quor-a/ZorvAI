import io, os

p = 'app/src/test/java/com/ai/assistance/quro/core/rag/RagWiringTest.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

old = """    @Test
    fun 提示词选择性模式检索不到时不塞无关规则() {
        // 纯噪声查询 → 一个进阶段都不该加。塞无关规则只会稀释注意力，
        // 让模型在当前无关的规则里漏掉真正该守的那条。
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.SELECTIVE,
            userQuery = "zzzzqqq完全不相关的东西xyzzy",
            base = "BASE",
        )
        assertTrue(
            "乱串不该触发任何规则注入，实际长度 ${out.length}（base=4）",
            out.length <= "BASE".length + 64
        )
    }"""

new = """    @Test
    fun 提示词选择性模式检索不到时不塞无关规则() {
        // 纯噪声查询 → 一个**进阶段**都不该加。塞无关规则只会稀释注意力，
        // 让模型在当前无关的规则里漏掉真正该守的那条。
        //
        // 🔴 2026-10-08 判据修正（实测依据，勿当 bug 改回）：
        // 旧断言是 `out.length <= base.length + 64`，即「一个字都不能加」。
        // 那条断言本身是**死代码的产物**——当时 8 个块里 `alwaysSticky = true`
        // 出现 0 次，`stickyBlocks` 恒为空，于是 SELECTIVE 实际注入 0 字符，
        // 「一个字节都不加」才碰巧成立。探针实测：
        //     blocks 总数=8   stickyBlocks=[]   SELECTIVE 追加字符数=0
        // 一旦给真正该常驻的段打上 alwaysSticky（工具调用纪律 / 输出格式纪律），
        // 基础段就必须每轮注入 —— 那正是设计意图，不是「塞了无关规则」。
        //
        // 现在真正要保证的是：**基础段照常注入，但进阶段（检索结果）一个都不加**。
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.SELECTIVE,
            userQuery = "zzzzqqq完全不相关的东西xyzzy",
            base = "BASE",
        )
        assertTrue("基座必须原样保留", out.startsWith("BASE"))
        assertFalse(
            "乱串不该触发「本轮相关补充规则」进阶段段落：\\n${out.take(600)}",
            out.contains("本轮相关补充规则")
        )
        // 顺带把「基础段非空」钉住，别再退回注入 0 字符的死代码
        assertTrue(
            "基础段必须真的注入（修前 stickyBlocks 为空 → 追加 0 字符）",
            out.length > "BASE".length + 64
        )
    }"""

assert src.count(old) == 1, 'anchor count=%d' % src.count(old)
src = src.replace(old, new, 1)

# 补 assertFalse import
old_imp = "import org.junit.Assert.assertTrue"
assert src.count(old_imp) == 1
src = src.replace(old_imp, "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue", 1)

assert src != orig
t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(src)
os.replace(t, p)
print('OK  %d -> %d' % (len(orig), len(src)))
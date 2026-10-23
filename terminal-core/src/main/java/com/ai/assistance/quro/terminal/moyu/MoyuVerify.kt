package com.ai.assistance.quro.terminal.moyu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 墨语言测试跑器 + 修复闭环（aifix）的 **Kotlin 实现**。
 *
 * 为什么要在 App 里重做：官方 `tests/run.sh` 与 `tools/aifix.sh` 都是 bash + python3 + make，
 * Android 上全都没有。而 aifix 恰恰是「AI 修语言 bug」的质量闸门 —— 缺了它，AI 改完代码
 * 跑一遍测试通过就宣称"修好了"，而项目历史上反复出现三种假象：
 *   1. 改的是生成物（src/compiler.mo 由 src/mo 下 compiler_part 分片拼接），下次 sync 冲掉，测试照旧全绿
 *   2. 源码干净但 bin/ 里还是上一轮变异遗留的产物 → 测的是旧东西
 *   3. 测试本来就通过 → 改对改错一个样
 *
 * 本实现复刻 aifix 的硬步骤：应用补丁 → 跑测试必须通过 → **把补丁撤回再跑，必须失败**。
 * 「撤掉修复后测试仍然通过」= 这条修复没被测试钉住，一律判定无效。
 *
 * 测试口径与官方 run.sh 完全一致：
 *   · `tests/` 下的单文件用例     首行注释 `# expect: N` 为期望退出码（缺省 0）
 *   · `tests/<dir>/`          目录含 main.mo（编译前改名 test.mo）+ 若干库文件
 *   · `tests/errors/` 下的用例   **必须编译失败**，且报错包含 `# expect-error: <片段>`
 */
class MoyuVerify(private val rt: MoyuRuntime) {

    companion object {
        private const val TAG = "MoyuVerify"
        private const val PER_TEST_TIMEOUT_MS = 30_000L
    }

    /** 单个用例的结果。 */
    data class CaseResult(
        val name: String,
        val kind: String,      // normal / dir / error
        val passed: Boolean,
        val detail: String,    // 简单可读说明
        val expected: String,
        val actual: String,
    )

    /** 整批结果。 */
    data class SuiteResult(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val cases: List<CaseResult>,
        val log: String,
    ) {
        val ok: Boolean get() = failed == 0 && total > 0
    }

    private val testsDir: File get() = File(rt.moHome, "tests")
    private val libDir: File get() = File(rt.moHome, "lib")

    /** 用例清单（不含 errors 子目录）。 */
    fun listCases(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        testsDir.listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.sortedBy { it.name }?.forEach {
            out.add(it.name to "normal")
        }
        testsDir.listFiles { f -> f.isDirectory && f.name != "errors" }?.sortedBy { it.name }?.forEach { d ->
            if (File(d, "main.mo").exists()) out.add(d.name to "dir")
        }
        File(testsDir, "errors").listFiles { f -> f.isFile && f.name.endsWith(".mo") }
            ?.sortedBy { it.name }?.forEach { out.add(it.name to "error") }
        return out
    }

    /**
     * 跑测试。
     *
     * @param filter 为空 = 全部；否则按名字包含匹配（如 "01_"、"errors/"）
     * @param maxCases 只跑前 N 个（快速冒烟用），0 = 不限
     */
    suspend fun run(filter: String = "", maxCases: Int = 0): SuiteResult =
        withContext(Dispatchers.IO) {
            if (!rt.isInstalled()) {
                return@withContext SuiteResult(0, 0, 0, emptyList(), "墨语言运行时未就绪，请先 setup")
            }
            val all = listCases()
            val picked = all.filter { filter.isBlank() || it.first.contains(filter) }
                .let { list -> if (maxCases > 0) list.take(maxCases) else list }
            if (picked.isEmpty()) {
                return@withContext SuiteResult(0, 0, 0, emptyList(), "没有匹配 \"$filter\" 的用例（共 ${all.size} 个）")
            }

            val sb = StringBuilder()
            val cases = mutableListOf<CaseResult>()
            sb.appendLine("测试：共 ${all.size} 个用例，本次跑 ${picked.size} 个" +
                (if (filter.isNotBlank()) "（过滤 \"$filter\"）" else ""))
            sb.appendLine()

            picked.forEach { (name, kind) ->
                val r = runOne(name, kind)
                cases.add(r)
                sb.appendLine((if (r.passed) "PASS " else "FAIL ") + name + "  " + r.detail)
            }

            val passed = cases.count { it.passed }
            val failed = cases.size - passed
            sb.appendLine()
            sb.append("通过 $passed / 失败 $failed")
            SuiteResult(cases.size, passed, failed, cases, sb.toString().trim())
        }

    /** 跑单个用例。 */
    private suspend fun runOne(name: String, kind: String): CaseResult {
        val work = File(rt.root, "tmp/test-" + System.nanoTime())
        return try {
            work.mkdirs()
            // 标准库始终取 lib/ 最新一份（与官方 run.sh 口径一致）
            libDir.listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.forEach { lib ->
                runCatching { lib.copyTo(File(work, lib.name), overwrite = true) }
            }

            when (kind) {
                "normal" -> {
                    val src = File(testsDir, name)
                    val exp = readExpect(src)
                    src.copyTo(File(work, "test.mo"), overwrite = true)
                    judgeCompileAndRun(work, name, exp, kind)
                }
                "dir" -> {
                    val dir = File(testsDir, name)
                    val mainFile = File(dir, "main.mo")
                    val exp = readExpect(mainFile)
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.forEach { f ->
                        val target = if (f.name == "main.mo") "test.mo" else f.name
                        runCatching { f.copyTo(File(work, target), overwrite = true) }
                    }
                    judgeCompileAndRun(work, name + "/", exp, kind)
                }
                else -> {
                    // 编译错误用例：必须失败，且报错包含指定片段
                    val src = File(testsDir, "errors/$name")
                    val frag = readExpectError(src)
                    src.copyTo(File(work, "test.mo"), overwrite = true)
                    val c = rt.exec(rt.tool("moc"), emptyList(), work, PER_TEST_TIMEOUT_MS)
                    when {
                        c.code == 0 -> CaseResult(
                            name, kind, false, "应报错但编译通过", frag ?: "(任意报错)", "退出码 0"
                        )
                        frag != null && !c.output.contains(frag) -> CaseResult(
                            name, kind, false, "报错不含 \"$frag\"", frag, c.output.take(200)
                        )
                        else -> CaseResult(name, kind, true, "已拦下", frag ?: "(任意报错)", "rc=${c.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "用例 $name 异常", e)
            CaseResult(name, kind, false, "异常：" + (e.message ?: ""), "", "")
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    /** 正常用例：编译（必须成功）→ 运行 → 比对退出码。 */
    private suspend fun judgeCompileAndRun(work: File, name: String, exp: Int, kind: String): CaseResult {
        // moc 在 cwd 下默认读 test.mo、产出 out.elf
        val c = rt.exec(rt.tool("moc"), emptyList(), work, PER_TEST_TIMEOUT_MS)
        if (c.code != 0) {
            return CaseResult(name, kind, false, "编译失败", "rc=$exp", (c.output.takeLast(200).trim()))
        }
        val elf = File(work, "out.elf")
        if (!elf.exists()) {
            return CaseResult(name, kind, false, "编译无产物 out.elf", "rc=$exp", "无产物")
        }
        val r = rt.exec(elf, emptyList(), work, PER_TEST_TIMEOUT_MS)
        return if (r.code == exp) {
            CaseResult(name, kind, true, "= $r.code", "rc=$exp", "rc=${r.code}")
        } else {
            CaseResult(name, kind, false, "期望 $exp 实得 ${r.code}", "rc=$exp", "rc=${r.code}")
        }
    }

    private fun readExpect(f: File): Int {
        val line = f.useLines { it.firstOrNull { l -> l.contains("# expect:") } } ?: return 0
        val v = line.substringAfter("# expect:").trim().substringBefore(' ').trim()
        return v.toIntOrNull() ?: 0
    }

    private fun readExpectError(f: File): String? =
        f.useLines { it.firstOrNull { l -> l.contains("# expect-error:") } }
            ?.substringAfter("# expect-error:")?.trim()?.ifBlank { null }

    // ═════════════════ aifix 闭环 ═════════════════

    /** aifix 各步骤的结果。 */
    data class FixResult(
        val ok: Boolean,
        val steps: List<String>,
    ) {
        val log: String get() = steps.joinToString("\n")
    }

    /**
     * 修复闭环：应用补丁 → 测试必须通过 → 撤回补丁 → 测试**必须失败** → 还原到修复态。
     *
     * @param fileRel 相对 mo 根目录的路径（如 `lib/str.mo`）
     * @param oldText 原文（必须在文件里**唯一**出现）
     * @param newText 新文
     * @param testFilter 只跑匹配的用例；空 = 全部（慢，建议缩小范围）
     * @param noMutate 跳过变异步骤（不建议，等于放弃「修复被钉住」的验证）
     */
    suspend fun aifix(
        fileRel: String,
        oldText: String,
        newText: String,
        testFilter: String = "",
        noMutate: Boolean = false,
    ): FixResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()

        // 守卫 1：不能改生成物
        if (fileRel == "src/compiler.mo") {
            steps.add("❌ 拒绝：src/compiler.mo 是 src/mo/compiler_part*.mo 拼接出来的生成物")
            steps.add("   改编译器要改 src/mo/ 下的片段（App 内需手工重新自举，见下方说明）")
            return@withContext FixResult(false, steps)
        }
        val target = File(rt.moHome, fileRel)
        if (!target.isFile) {
            steps.add("❌ 文件不存在：$fileRel")
            return@withContext FixResult(false, steps)
        }
        if (oldText.isEmpty() || newText.isEmpty()) {
            steps.add("❌ old / new 不能为空")
            return@withContext FixResult(false, steps)
        }

        val original = runCatching { target.readText() }.getOrElse {
            steps.add("❌ 读取失败：" + (it.message ?: ""))
            return@withContext FixResult(false, steps)
        }
        val hits = countOccurrences(original, oldText)
        if (hits == 0) {
            steps.add("❌ 原文在文件里找不到（0 处匹配）。注意实际字符需完全一致 —— 缩进/空行/转义都算")
            steps.add("   可先用 read 打开文件核对片段")
            return@withContext FixResult(false, steps)
        }
        if (hits > 1) {
            steps.add("❌ 原文出现 $hits 次，无法判定改哪一处 —— 请把上下文写长一点，保证唯一")
            return@withContext FixResult(false, steps)
        }
        steps.add("✓ 原文唯一匹配（1 处）")

        // 备份（放 build 目录且不进 assets，避免被清理）
        val bakDir = File(rt.root, "aifix").apply { mkdirs() }
        val bakName = fileRel.replace("/", "_")
        val bakOrig = File(bakDir, "$bakName.orig")
        val bakNew = File(bakDir, "$bakName.new")
        runCatching { target.copyTo(bakOrig, overwrite = true) }

        var mutated = false
        try {
            // 1) 应用补丁
            target.writeText(original.replaceFirst(oldText, newText))
            runCatching { target.copyTo(bakNew, overwrite = true) }
            steps.add("✓ 补丁已应用")

            // 2) 修复后测试必须通过
            val fixed = run(testFilter)
            steps.add("· 修复后：通过 ${fixed.passed} / 失败 ${fixed.failed}")
            if (!fixed.ok) {
                steps.add("❌ 修复后测试仍然失败 —— 补丁无效，源码保持修复态供继续迭代")
                fixed.cases.filter { !it.passed }.take(5).forEach { steps.add("   FAIL " + it.name + "  " + it.detail) }
                return@withContext FixResult(false, steps)
            }
            steps.add("✓ 修复后测试通过")

            if (noMutate) {
                steps.add("· 已跳过变异（noMutate）—— 注意：这等于没有验证「修复被测试钉住」")
                return@withContext FixResult(true, steps)
            }

            // 3) 变异：撤回修复，测试必须失败
            mutated = true
            target.writeText(original)
            val mut = run(testFilter)
            steps.add("· 变异后（已撤回修复）：通过 ${mut.passed} / 失败 ${mut.failed}")
            if (mut.ok) {
                steps.add("❌ 撤回修复后测试仍然全绿 —— 这条修复没被钉住")
                steps.add("   含义：要么测试没覆盖这个缺陷，要么判据只是「能编译/退出码 0」")
                steps.add("   正确做法：先补测试判据，再谈修好了")
                return@withContext FixResult(false, steps)
            }
            steps.add("✓ 撤回修复后测试失败 —— 修复被测试钉住")

            // 4) 还原到修复态
            mutated = false
            target.writeText(runCatching { bakNew.readText() }.getOrElse { original.replaceFirst(oldText, newText) })
            steps.add("✓ 已还原到修复态")
            steps.add("")
            steps.add("结论：修复有效，且被测试钉住")
            FixResult(true, steps)
        } finally {
            // 中断/异常时绝不把源码留在变异态
            if (mutated) {
                runCatching {
                    target.writeText(runCatching { bakNew.readText() }.getOrDefault(original.replaceFirst(oldText, newText)))
                }
                Log.w(TAG, "异常退出，已把 $fileRel 还原到修复态")
            }
        }
    }

    private fun countOccurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = hay.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = hay.indexOf(needle, idx + needle.length)
        }
        return count
    }
}

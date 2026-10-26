package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #478：glob / grep 的 ripgrep 覆盖包**必须真的能在 Android 上执行**。
 *
 * ## 为什么需要这条测试
 * harness 的 `glob` / `grep` 两个工具恒失败，报 `unexpected e_type: 2`。根因是
 * `@vscode/ripgrep-linux-arm64` 打包的 `bin/rg` 是 **Linux/aarch64 的普通发行版二进制**：
 * `e_type = 2`（ET_EXEC，非 PIE）、权限 `0644`。Android 10+ 的 bionic 只加载 ET_DYN，
 * 于是 `exec`/`linker64` 两条路都死。
 *
 * 修法是随包发一份 Termux 构建（`e_type = 3`、`PT_INTERP = /system/bin/linker64`），
 * 以及它唯一的非 bionic 依赖 `libpcre2-8.so`。这两个二进制一旦被换错版本（比如有人
 * 「更新一下 ripgrep」换成官方 release 的 aarch64-linux-gnu 或 musl 静态包），故障会
 * **原样复发且没有任何编译期信号**——所以这里直接对资产做 ELF 体检。
 *
 * 判据全部取自 ELF 结构本身（程序头 / 动态段），不看文件名、不看大小。
 */
class RipgrepOverlayTest {

  private fun assetFile(relative: String): File {
    val candidates = listOf(
      File("src/main/assets/$relative"),
      File("app/src/main/assets/$relative"),
      File("dshmobile/app/src/main/assets/$relative"),
    )
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "缺少覆盖资产 $relative（工作目录 = " + File(".").absolutePath + "）；" +
          "glob/grep 会退回不可执行的 ET_EXEC rg。",
      )
  }

  // 🔴 资产是**原始二进制**，不是 .gz：aapt2 会把 `.gz` 资产解压并抹掉扩展名，
  // 代码按 `.gz` 名 open 会直接 FileNotFoundException（本仓 `.pack` 后缀同一教训）。
  private fun bytes(f: File): ByteArray = f.readBytes()

  /** `e_type`：2 = ET_EXEC（Android 拒绝），3 = ET_DYN（PIE，Android 接受）。 */
  private fun elfType(elf: ByteArray): Int =
    (elf[16].toInt() and 0xFF) or ((elf[17].toInt() and 0xFF) shl 8)

  private fun u16(elf: ByteArray, off: Int): Int =
    (elf[off].toInt() and 0xFF) or ((elf[off + 1].toInt() and 0xFF) shl 8)

  private fun u32(elf: ByteArray, off: Int): Long =
    ((elf[off].toInt() and 0xFF).toLong()) or
      ((elf[off + 1].toInt() and 0xFF).toLong() shl 8) or
      ((elf[off + 2].toInt() and 0xFF).toLong() shl 16) or
      ((elf[off + 3].toInt() and 0xFF).toLong() shl 24)

  private fun u64(elf: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (elf[off + i].toLong() and 0xFF)
    return v
  }

  /** PT_INTERP 指向的解释器路径（PIE 可执行文件才有；共享库为 null）。 */
  private fun interp(elf: ByteArray): String? {
    val phoff = u64(elf, 0x20).toInt()
    val phentsize = u16(elf, 0x36)
    val phnum = u16(elf, 0x38)
    for (i in 0 until phnum) {
      val o = phoff + i * phentsize
      if (u32(elf, o) == 3L) { // PT_INTERP
        val off = u64(elf, o + 8).toInt()
        val size = u64(elf, o + 32).toInt()
        val raw = elf.copyOfRange(off, off + size)
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.US_ASCII)
      }
    }
    return null
  }

  /** DT_NEEDED 列表（按动态段顺序）。 */
  private fun needed(elf: ByteArray): List<String> {
    val shoff = u64(elf, 0x28).toInt()
    val shentsize = u16(elf, 0x3A)
    val shnum = u16(elf, 0x3C)
    var dynOff = -1
    var dynSize = 0
    var dynLink = -1
    for (i in 0 until shnum) {
      val o = shoff + i * shentsize
      if (u32(elf, o + 4) == 6L) { // SHT_DYNAMIC
        dynOff = u64(elf, o + 24).toInt()
        dynSize = u64(elf, o + 32).toInt()
        dynLink = u32(elf, o + 40).toInt()
      }
    }
    if (dynOff < 0 || dynLink < 0) return emptyList()
    val so = shoff + dynLink * shentsize
    val strOff = u64(elf, so + 24).toInt()
    val strSize = u64(elf, so + 32).toInt()
    val strtab = elf.copyOfRange(strOff, strOff + strSize)
    val out = mutableListOf<String>()
    var p = dynOff
    while (p + 16 <= dynOff + dynSize) {
      val tag = u64(elf, p)
      val val0 = u64(elf, p + 8).toInt()
      if (tag == 0L) break
      if (tag == 1L) { // DT_NEEDED
        var end = val0
        while (end < strtab.size && strtab[end] != 0.toByte()) end++
        out.add(String(strtab, val0, end - val0, Charsets.US_ASCII))
      }
      p += 16
    }
    return out
  }

  @Test
  fun `覆盖用的 rg 必须是 PIE 且由 Android linker 启动`() {
    val elf = bytes(assetFile("patched/bin/rg"))
    assertEquals("ELF 魔数不对", 0x7F, elf[0].toInt() and 0xFF)
    assertEquals(
      "rg 必须是 ELFCLASS64（aarch64）",
      2,
      elf[4].toInt() and 0xFF,
    )
    assertEquals(
      "rg 必须 e_type=3(ET_DYN/PIE)；2(ET_EXEC) 就是 bionic 拒绝加载的原始故障",
      3,
      elfType(elf),
    )
    assertEquals(
      "rg 的解释器必须是 Android 的 linker64（Termux 构建），不是 glibc 的 ld-linux",
      "/system/bin/linker64",
      interp(elf),
    )
    val deps = needed(elf)
    assertTrue(
      "rg 依赖了未随包的库：$deps（只允许 bionic 的 libc/libdl + 随包的 libpcre2-8）",
      deps.all { it == "libc.so" || it == "libdl.so" || it == "libpcre2-8.so" },
    )
    assertTrue("rg 依赖 libpcre2-8.so，必须随包", deps.contains("libpcre2-8.so"))
  }

  @Test
  fun `随包的 libpcre2-8 也是 bionic 可加载的共享库`() {
    val elf = bytes(assetFile("patched/lib/libpcre2-8.so"))
    assertEquals("libpcre2-8 必须 e_type=3", 3, elfType(elf))
    assertEquals("共享库不应有解释器", null, interp(elf))
    assertTrue(
      "libpcre2-8 只应依赖 bionic libc：${needed(elf)}",
      needed(elf).all { it == "libc.so" },
    )
  }

  @Test
  fun `引擎启动期必须覆盖 rg 并补可执行位`() {
    val src = File("src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
      .takeIf { it.isFile }
      ?: File("app/src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
    // Kotlin 源码里的 JSON 字面量带转义（\"name\":…），比对前先还原，否则断言永远匹配不到。
    val code = src.readText().replace("\\\"", "\"")
    // 覆盖必须在 applyRuntimePatches（每次引擎启动都会跑）里被调用，否则快照重解压后失效。
    assertTrue("applyRuntimePatches 必须调用 applyRipgrepOverlay()", code.contains("applyRipgrepOverlay()"))
    assertTrue(
      "覆盖目标必须是 @vscode/ripgrep-linux-arm64 的真实路径",
      code.contains("@vscode/ripgrep-linux-arm64/bin/rg"),
    )
    assertTrue(
      "libpcre2-8 必须落到 usr/lib（引擎 env 的 LD_LIBRARY_PATH 就是它）",
      code.contains("patched/lib/libpcre2-8.so"),
    )
    // 包内的 rg 是 0644，不补位照样跑不起来。
    assertTrue("可执行必须显式 setExecutable(true)", code.contains("setExecutable(true"))
    // 🔴 资产名不许带 .gz：aapt2 会把 `.gz` 资产解压并抹扩展名，代码按 `.gz` 名 open 必炸
    // （本仓 `.pack` 后缀就是这么来的）。
    assertTrue(
      "rg 覆盖资产必须是无后缀的 patched/bin/rg",
      code.contains("\"patched/bin/rg\""),
    )
    assertFalse(
      "资产名不得带 .gz（aapt2 会解压并抹掉扩展名）",
      Regex("patched/(bin|lib)/[a-z0-9.\\-]+\\.gz\"").containsMatchIn(code),
    )
  }

  /**
   * #480：**光换二进制不够**，必须让 `@vscode/ripgrep-android-arm64` 平台包存在。
   *
   * `@vscode/ripgrep` 的入口是 `require.resolve('@vscode/ripgrep-${process.platform}-${arch}/bin/rg')`，
   * 而 Android 上 node 的 `process.platform` 是 `android`（不是 linux）。快照里只有
   * `ripgrep-linux-arm64` → resolve 抛错 → `resolveRgPath()` 的 `rgPathPromise` 把这个
   * **被拒绝的 Promise 缓存**在进程内 → 整个进程的 glob/grep 永久 `ripgrep launch failed`。
   *
   * 这条用例守「覆盖逻辑必须造平台包」以及「用符号链接复用同一份二进制，不要重复占包体」。
   */
  @Test
  fun `必须造出 android-arm64 平台包供 require_resolve`() {
    val src = File("src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
      .takeIf { it.isFile }
      ?: File("app/src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
    // Kotlin 源码里的 JSON 字面量带转义（\"name\":…），比对前先还原，否则断言永远匹配不到。
    val code = src.readText().replace("\\\"", "\"")
    assertTrue(
      "必须调用 linkAndroidArm64PlatformPackage",
      code.contains("linkAndroidArm64PlatformPackage("),
    )
    assertTrue(
      "必须造 @vscode/ripgrep-android-arm64 目录（process.platform 在 Android 上是 android）",
      code.contains("@vscode/ripgrep-android-arm64"),
    )
    assertTrue(
      "平台包必须有 package.json（require.resolve 按包名找）",
      code.contains("\"name\":\"@vscode/ripgrep-android-arm64\""),
    )
    assertTrue(
      "bin/rg 优先用符号链接复用 linux-arm64 那份二进制（不重复占包体）",
      code.contains("createSymbolicLink"),
    )
    assertTrue(
      "符号链接失败时必须退化为字节复制（功能优先）",
      code.contains("copyTo(link, overwrite = true)"),
    )
  }
}

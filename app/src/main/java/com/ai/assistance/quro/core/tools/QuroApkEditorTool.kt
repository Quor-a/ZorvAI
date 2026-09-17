package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * apk_editor：APK 包检视与重组（对应「其他 AI」文件与编辑维的 ApkEditor 能力）。
 * 纯 Kotlin（java.util.zip + Android PackageManager），无 ffmpeg 等外部依赖。
 * 支持：list / info / extract / strings / remove / add。
 * 注意：remove/add 重组后的 APK 未重新签名，需自行签名后才能安装（工具会明确提示）。
 */
class QuroApkEditorTool : QuroTool {
    override val name: String = "apk_editor"

    override val description: String =
        "APK 包检视与重组：列出条目 / 读取包信息 / 提取文件 / 提取可读字符串 / 删除或新增条目重组。" +
            "action 取值：list|info|extract|strings|remove|add。" +
            "apk 为 APK 路径；entry 为条目名（extract/remove 用，多个用 ';' 分隔）；" +
            "output_dir 为提取目录 / 重组产物目录；source 为 add 动作要加入的本地文件；" +
            "out_apk 为重组产物路径（默认 <apk>.mod.apk）；max_strings 为 strings 最多返回条数（默认 2000）。" +
            "重组（remove/add）后 APK 未重新签名，无法直接安装，工具会提示。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["list","info","extract","strings","remove","add"],"description":"动作"},
        "apk":{"type":"string","description":"APK 文件路径"},
        "entry":{"type":"string","description":"条目名（extract/remove 用，';' 分隔）"},
        "output_dir":{"type":"string","description":"extract 提取目录 / 重组产物目录"},
        "source":{"type":"string","description":"add 动作：要加入 APK 的本地文件路径"},
        "out_apk":{"type":"string","description":"重组产物 APK 路径（默认 <apk>.mod.apk）"},
        "max_strings":{"type":"integer","description":"strings 最多返回字符串数（默认 2000）"}
      },
      "required":["action","apk"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            val apkPath = args.optString("apk", "")
            if (apkPath.isBlank()) return "参数 apk 为空"
            val apk = File(apkPath)
            if (!apk.isFile || !apk.canRead()) return "APK 不可读：$apkPath"

            when (action) {
                "list" -> listEntries(apk)
                "info" -> packageInfo(context, apk)
                "extract" -> extract(apk, args)
                "strings" -> strings(apk, args.optInt("max_strings", 2000))
                "remove" -> repackage(apk, args, remove = true)
                "add" -> repackage(apk, args, remove = false)
                else -> "不支持的 action：$action（支持 list/info/extract/strings/remove/add）"
            }
        } catch (e: Exception) {
            "apk_editor 执行失败：${e.message}"
        }
    }

    private fun listEntries(apk: File): String {
        val zf = ZipFile(apk)
        val arr = JSONArray()
        try {
            zf.entries().toList().forEach { e ->
                val o = JSONObject()
                o.put("name", e.name)
                o.put("size", e.size)
                o.put("compressed", e.compressedSize)
                o.put("dir", e.isDirectory)
                o.put(
                    "type",
                    when {
                        e.name.matches(Regex("lib/[^/]+/.*\\.so$")) -> "native_lib"
                        e.name == "classes.dex" || e.name.matches(Regex("classes\\d*\\.dex$")) -> "dex"
                        e.name == "AndroidManifest.xml" -> "manifest"
                        e.name == "resources.arsc" -> "resources"
                        e.name.endsWith(".png") || e.name.endsWith(".webp") -> "image"
                        else -> "other"
                    },
                )
                arr.put(o)
            }
        } finally { zf.close() }
        val dexs = (0 until arr.length()).count { arr.getJSONObject(it).optString("type") == "dex" }
        val libs = (0 until arr.length()).count { arr.getJSONObject(it).optString("type") == "native_lib" }
        return "共 ${arr.length()} 个条目；dex=$dexs；native_lib=$libs\n${arr.toString(2)}"
    }

    private fun packageInfo(context: Context, apk: File): String {
        val pm = context.packageManager
        val pi = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES,
        ) ?: return "无法解析 APK（可能不是合法 Android 包）：${apk.absolutePath}"
        val o = JSONObject()
        o.put("package", pi.packageName)
        o.put("versionName", pi.versionName)
        o.put("versionCode", try { pi.longVersionCode } catch (_: Throwable) { pi.versionCode })
        pi.applicationInfo?.let { ai ->
            o.put("minSdk", ai.minSdkVersion)
            o.put("targetSdk", ai.targetSdkVersion)
        }
        val perms = pi.permissions?.mapNotNull { it.name } ?: emptyList<String>()
        val acts = pi.activities?.mapNotNull { it.name } ?: emptyList<String>()
        val svcs = pi.services?.mapNotNull { it.name } ?: emptyList<String>()
        o.put("permissions", JSONArray(perms))
        o.put("activities", JSONArray(acts))
        o.put("services", JSONArray(svcs))
        return o.toString(2)
    }

    private fun extract(apk: File, args: JSONObject): String {
        val entrySel = args.optString("entry", "*")
        val outDir = args.optString("output_dir", "").takeIf { it.isNotBlank() }
            ?: return "extract 需要 output_dir"
        val dir = File(outDir)
        dir.mkdirs()
        val wanted = if (entrySel == "*") null else entrySel.split(";").map { it.trim() }.toSet()
        val zf = ZipFile(apk)
        var n = 0
        try {
            zf.entries().toList().forEach { e ->
                if (wanted != null && e.name !in wanted) return@forEach
                if (e.isDirectory) return@forEach
                val out = File(dir, e.name)
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { ins -> out.outputStream().use { outs -> ins.copyTo(outs) } }
                n++
            }
        } finally { zf.close() }
        return "已提取 $n 个条目到：${dir.absolutePath}"
    }

    private fun strings(apk: File, max: Int): String {
        val raf = RandomAccessFile(apk, "r")
        val arr = JSONArray()
        var dropped = 0
        val total = raf.length()
        val limit = minOf(total, 100L * 1024 * 1024) // 最多扫 100MB，防止超大 APK 卡死
        try {
            val buf = ByteArray(1 shl 16)
            val sb = StringBuilder()
            while (raf.filePointer < limit) {
                val r = raf.read(buf)
                if (r <= 0) break
                for (i in 0 until r) {
                    val b = buf[i].toInt() and 0xFF
                    if (b in 0x20..0x7E) sb.append(b.toChar())
                    else {
                        if (sb.length >= 4) { if (arr.length() < max) arr.put(sb.toString()) else dropped++ }
                        sb.setLength(0)
                    }
                }
            }
            if (sb.length >= 4) { if (arr.length() < max) arr.put(sb.toString()) else dropped++ }
        } finally { raf.close() }
        val note = if (dropped > 0) "（另有 $dropped 条超出上限未列出）" else ""
        return "可读字符串（最多 $max，扫描 ${limit / 1024 / 1024}MB）共 ${arr.length()} 条$note：\n${arr.toString(2)}"
    }

    private fun repackage(apk: File, args: JSONObject, remove: Boolean): String {
        val outApk = args.optString("out_apk", "").takeIf { it.isNotBlank() }
            ?: "${apk.absolutePath}.mod.apk"
        val out = File(outApk)
        out.parentFile?.mkdirs()
        val entrySel = args.optString("entry", "").takeIf { it.isNotBlank() }
        val sel = if (entrySel == null) emptySet() else entrySel.split(";").map { it.trim() }.toSet()
        val source = args.optString("source", "").takeIf { it.isNotBlank() }

        if (remove) {
            if (sel.isEmpty()) return "remove 需要 entry 指定要删除的条目（';' 分隔，不支持 '*'）"
            if (sel.contains("*")) return "remove 不支持 '*'，请逐条指定要删除的条目"
        } else {
            if (source == null) return "add 动作需要 source（要加入的本地文件）"
        }

        val zf = ZipFile(apk)
        val zos = ZipOutputStream(out.outputStream())
        var removed = 0
        val addedName = if (!remove) File(source!!).name else ""
        try {
            zf.entries().toList().forEach { e ->
                if (remove && e.name in sel) { removed++; return@forEach }
                zos.putNextEntry(ZipEntry(e.name))
                if (!e.isDirectory) zf.getInputStream(e).use { ins -> ins.copyTo(zos) }
                zos.closeEntry()
            }
            if (!remove && source != null) {
                val sf = File(source)
                if (!sf.isFile) return "source 不可读：$source"
                zos.putNextEntry(ZipEntry(addedName))
                sf.inputStream().use { ins -> ins.copyTo(zos) }
                zos.closeEntry()
            }
        } finally { zos.close(); zf.close() }

        val verb = if (remove) "移除 $removed 个条目" else "新增 $addedName"
        return "已重组 APK（未重新签名！安装前需 apksigner/zipalign 重签）：${out.absolutePath}（$verb）"
    }
}

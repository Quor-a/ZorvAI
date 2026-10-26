package com.ai.assistance.quro.ui

import android.content.Context
import org.json.JSONObject

/**
 * 开源组件清单（#485「设置 → 关于 ZorvAI → 开源许可声明」的数据源）。
 *
 * 数据文件：`assets/legal/third_party_licenses.json`，由仓库根 `tools/gen_licenses.py` 生成，
 * **与仓库 NOTICE 同一份数据**——避免「应用里说一套、仓库声明写另一套」。
 *
 * 为什么从硬编码 32 条改成资产驱动（这次是全项目排查的结论）：
 *  · 原清单只列了 32 项，实际随包组件约 99 组（Gradle 66 个坐标 + 原生运行时 + 随包前端库
 *    + 内置引擎 556 个 npm 包 + 文档字体 + 系统镜像），遗漏了大量真实存在的许可证义务：
 *    ECJ（EPL-2.0）、LibreOffice Kit（MPL-2.0）、libvips（LGPL-3.0）、Node.js、Python、
 *    BusyBox / bash / proot / QEMU（GPL）、Zig、box64、ripgrep、Ubuntu rootfs、p5.js（LGPL-2.1）、
 *    GSAP（非 OSI 许可）等；
 *  · 原清单还有**错误**：Shizuku 写 Apache-2.0（实测 Maven 元数据是 MIT）、Yoga 写 Apache-2.0（MIT）、
 *    D3 未标许可证（ISC）；
 *  · 硬编码在人读的 Kotlin 里，改一次要动代码并重新发版；资产文件可随仓库同步审计。
 *
 * 本类只做**加载与解析**，不含任何许可证判断逻辑——判断在生成脚本里做（那里能读 POM / banner）。
 */
internal data class LicenseEntry(
    val name: String,
    val license: String,
    /** 0 = 宽松；1 = 弱 copyleft（LGPL / MPL / EPL）；2 = 强 copyleft（GPL / AGPL）。 */
    val copyleft: Int,
    val noteZh: String,
    val noteEn: String,
)

internal data class LicenseCategory(
    val id: String,
    val titleZh: String,
    val titleEn: String,
    val entries: List<LicenseEntry>,
)

internal data class LicenseCatalog(
    val generatedAt: String,
    val appLicense: String,
    val appSource: String,
    val total: Int,
    val distribution: List<Pair<String, Int>>,
    val categories: List<LicenseCategory>,
    val noticeZh: String,
    val noticeEn: String,
) {
    /** 界面语言是中文时优先中文说明（条目名保持原样，多为产品名）。 */
    fun isZh(): Boolean = java.util.Locale.getDefault().language.startsWith("zh")

    companion object {
        const val ASSET = "legal/third_party_licenses.json"

        /** 解析失败时抛异常，由调用方显示「清单读取失败」而不是崩掉关于页。 */
        fun load(context: Context): LicenseCatalog {
            val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(raw)

            val dist = mutableListOf<Pair<String, Int>>()
            root.optJSONArray("distribution")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dist.add(o.optString("license") to o.optInt("count"))
                }
            }

            val cats = mutableListOf<LicenseCategory>()
            root.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val entries = mutableListOf<LicenseEntry>()
                    c.optJSONArray("entries")?.let { es ->
                        for (j in 0 until es.length()) {
                            val e = es.getJSONObject(j)
                            entries.add(
                                LicenseEntry(
                                    name = e.optString("name"),
                                    license = e.optString("license"),
                                    copyleft = e.optInt("copyleft"),
                                    noteZh = e.optString("noteZh"),
                                    noteEn = e.optString("noteEn"),
                                ),
                            )
                        }
                    }
                    cats.add(
                        LicenseCategory(
                            id = c.optString("id"),
                            titleZh = c.optString("titleZh"),
                            titleEn = c.optString("titleEn"),
                            entries = entries,
                        ),
                    )
                }
            }

            val app = root.optJSONObject("app")
            val notice = root.optJSONObject("notice")
            return LicenseCatalog(
                generatedAt = root.optString("generatedAt"),
                appLicense = app?.optString("license").orEmpty().ifBlank { "Apache-2.0" },
                appSource = app?.optString("source").orEmpty(),
                total = root.optInt("total", cats.sumOf { it.entries.size }),
                distribution = dist,
                categories = cats,
                noticeZh = notice?.optString("zh").orEmpty(),
                noticeEn = notice?.optString("en").orEmpty(),
            )
        }
    }
}

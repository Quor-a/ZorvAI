package com.ai.assistance.quro.core.cluster

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 🔴 集群「角色类型」唯一真源（2026-11-07 用户裁定）。
 *
 * ## 为什么有这个文件
 *
 * 旧版同一个编辑页里并存两套互不相干的东西：
 * - 分组 1「标签（角色类型）」= [com.ai.assistance.quro.core.QuroTagRepository] 的
 *   42 条**语气/领域标签**，多选，写进 [RoleProfile.tags]；
 * - 分组 2「模型与类型 → 角色类型」= [RoleKind.selectable] **写死的四档枚举**，
 *   单选，写进 [RoleProfile.role]。
 *
 * 表现为「角色类型固定死、和标签各走各的」。用户要的是**一个**角色类型：
 * 可随时切换、可自定义添加。所以这里把类型本身做成一个可增删的库，
 * UI 只保留一处入口；[RoleKind] 退居为类型的**内部能力档位**（决定运行时
 * 能拿到哪些工具，`canExecute` / `isVerifier` 的语义完全不动）。
 *
 * ## 持久化
 *
 * `quro_role_types.json` 只存**用户自定义**类型；内置 4 档由 [BUILT_IN] 常量提供，
 * 每次 [all] 自动并在一起（幂等、无需迁移、升级不会丢）。内置档不可删，
 * 自定义档可增删改。[RoleProfile.tags] 收敛为「单个类型名」的列表。
 *
 * ## 老数据兼容
 *
 * 老档案的 tags 可能是多个能力词（内置卡播种）或语气标签。打开编辑器时取第一个
 * 作为当前类型，并调 [ensureLegacy] 把它登记成自定义类型，绝不丢数据；用户一切换
 * 就自然收敛成单一类型。
 */
data class QuroRoleType(
    /** 类型名（同时写进 RoleProfile.tags，是 UI 唯一展示值）。 */
    val name: String,
    /** 类型说明，展示在编辑页，也会随类型进系统提示词语境。 */
    val hint: String,
    /** 内部能力档位：决定运行时工具权限，UI 上不叫「角色类型」。 */
    val kind: RoleKind,
    /** 内置档不可删；自定义档可增删改。 */
    val builtin: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("hint", hint)
        put("kind", kind.name)
        put("builtin", builtin)
    }

    companion object {
        fun fromJson(o: JSONObject): QuroRoleType {
            val raw = runCatching { RoleKind.valueOf(o.optString("kind", "EXPERT")) }
                .getOrDefault(RoleKind.EXPERT)
            // 主持是集群唯一身份，不允许被自定义类型借用
            val kind = if (raw == RoleKind.HOST) RoleKind.EXPERT else raw
            return QuroRoleType(
                name = o.optString("name", "").trim(),
                hint = o.optString("hint", ""),
                kind = kind,
                builtin = o.optBoolean("builtin", false),
            )
        }
    }
}

object QuroRoleTypeRepository {

    private const val FILE = "quro_role_types.json"

    /** 内置 4 档：与 [RoleKind.selectable] 一一对齐，名称即 RoleKind.label。 */
    val BUILT_IN: List<QuroRoleType> = listOf(
        QuroRoleType(
            "规划",
            "拆解目标、定验收标准、给方案，不动手产出。系统只给它只读与检索类工具。",
            RoleKind.PLANNER, true,
        ),
        QuroRoleType(
            "执行",
            "真正动手的活：文件、终端、网络、设备操作等工具都会下发，可以把事做完。",
            RoleKind.EXECUTOR, true,
        ),
        QuroRoleType(
            "专家",
            "按职责产出具体内容，可查证资料；适合分析、写作、方案类工作。",
            RoleKind.EXPERT, true,
        ),
        QuroRoleType(
            "评审",
            "专职挑毛病、验收产出；系统不会给它改文件/操作设备的工具，只能读和判。",
            RoleKind.CRITIC, true,
        ),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** 自定义类型（读盘，容错：坏文件视为空，绝不阻断 UI）。 */
    private fun loadCustom(ctx: Context): List<QuroRoleType> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return@runCatching emptyList<QuroRoleType>()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            runCatching { QuroRoleType.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }.filter { it.name.isNotBlank() && BUILT_IN.none { b -> b.name == it.name } }
    }.getOrDefault(emptyList())

    private fun saveCustom(ctx: Context, list: List<QuroRoleType>) {
        runCatching {
            val arr = JSONArray()
            list.filterNot { it.builtin }.forEach { arr.put(it.toJson()) }
            file(ctx).writeText(arr.toString())
        }
    }

    /** 全部类型：内置在前，自定义在后。 */
    fun all(ctx: Context): List<QuroRoleType> = BUILT_IN + loadCustom(ctx)

    fun byName(ctx: Context, name: String): QuroRoleType? =
        all(ctx).firstOrNull { it.name == name }

    /** 用户选中的类型对应的能力档位；找不到时回退到角色原有档位。 */
    fun kindOf(ctx: Context, name: String, fallback: RoleKind): RoleKind =
        byName(ctx, name)?.kind ?: fallback

    /** 新建自定义类型。重名 / 空名 / 撞内置名 → false。 */
    fun add(ctx: Context, name: String, hint: String, kind: RoleKind): Boolean {
        val n = name.trim()
        if (n.isBlank() || byName(ctx, n) != null) return false
        val safeKind = if (kind == RoleKind.HOST) RoleKind.EXPERT else kind
        val cur = loadCustom(ctx).toMutableList()
        cur += QuroRoleType(n, hint.trim(), safeKind, false)
        saveCustom(ctx, cur)
        return true
    }

    /** 编辑自定义类型（内置档只读，返回 false）。旧名用于定位，允许改名。 */
    fun update(ctx: Context, oldName: String, name: String, hint: String, kind: RoleKind): Boolean {
        val n = name.trim()
        if (n.isBlank()) return false
        val cur = loadCustom(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.name == oldName }
        if (idx < 0) return false                       // 内置档不可编辑
        if (n != oldName && byName(ctx, n) != null) return false
        val safeKind = if (kind == RoleKind.HOST) RoleKind.EXPERT else kind
        cur[idx] = QuroRoleType(n, hint.trim(), safeKind, false)
        saveCustom(ctx, cur)
        return true
    }

    /** 删除自定义类型（内置档不可删）。 */
    fun delete(ctx: Context, name: String): Boolean {
        if (BUILT_IN.any { it.name == name }) return false
        val cur = loadCustom(ctx)
        val next = cur.filterNot { it.name == name }
        if (next.size == cur.size) return false
        saveCustom(ctx, next)
        return true
    }

    fun isBuiltin(name: String): Boolean = BUILT_IN.any { it.name == name }

    /** 老数据兜底：把不在库里的历史 tag 登记成自定义类型，避免打开编辑器就丢类型。 */
    fun ensureLegacy(ctx: Context, name: String, kind: RoleKind) {
        val n = name.trim()
        if (n.isBlank() || byName(ctx, n) != null) return
        add(ctx, n, "由旧版标签迁移而来的自定义角色类型。", kind)
    }

    /** 默认类型（新建角色 / 删除当前类型后的回退）。 */
    fun default(ctx: Context): QuroRoleType =
        byName(ctx, "专家") ?: BUILT_IN.first()
}

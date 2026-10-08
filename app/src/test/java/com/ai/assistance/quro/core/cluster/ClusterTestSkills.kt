package com.ai.assistance.quro.core.cluster

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 测试侧的集群技能播种工具。
 *
 * 🔴 为什么需要它（本项目实测踩出来的坑）：
 * 本项目的 Robolectric 配置下 `context.assets` **只能看到 Android OS 自带资源**，
 * app 自己的 assets 一个都读不到（`skills/zorv` 也是空）。
 * 所以生产代码 [ClusterSkillStore.seed] 走 `context.assets` 在测试里必然播种出 0 个技能。
 *
 * 这不是生产代码的 bug，是测试环境限制。已有的 [BuiltinSkillAssetsTest] 也是因此
 * 走纯 JVM 文件路径读 assets。
 *
 * 所以这里提供 [seedClusterSkills]：从磁盘读同一份 manifest + 同一份 md，
 * 调用**同一个解析函数** [ClusterSkillStore.parseMd] 落库——
 * 也就是说，除了「从哪里读字节」不同，解析与落库逻辑完全一致，
 * 不会把生产逻辑测给跳过去。
 */
object ClusterTestSkills {

    private val NL: String = System.lineSeparator()

    /** 从 JVM 工作目录向上找仓库根。 */
    fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir")).absoluteFile
        var d: File? = cwd
        repeat(6) {
            val cur = d ?: return cwd
            if (File(cur, "settings.gradle.kts").exists()) return cur
            d = cur.parentFile
        }
        return cwd
    }

    /**
     * 把随包内置的 14 个集群技能播种进 [ctx] 的集群技能库。
     *
     * @return 实际播种进去的技能数
     */
    fun seedClusterSkills(ctx: Context): Int {
        val dir = File(repoRoot(), "app/src/main/assets/cluster-skills")
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return 0
        val arr = runCatching { JSONObject(mf.readText()).optJSONArray("skills") }.getOrNull()
            ?: return 0
        val list = mutableListOf<ClusterSkillStore.ClusterSkill>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val file = o.optString("file").trim()
            if (name.isEmpty() || file.isEmpty()) continue
            val md = File(dir, file).takeIf { it.exists() }?.readText() ?: continue
            list += ClusterSkillStore.parseMd(ClusterSkillStore.stableId(name), name, md)
        }
        ClusterSkillStore.save(ctx, list)
        return list.size
    }

    /**
     * 按能力词从集群库里找一个技能 id，用来给测试角色绑一门真手艺。
     *
     * 找不到返回空串——调用方应如实断言失败，不要退化成「随便绑一个」，
     * 那样这条测试就测不出「没技能会被跳过」这个真实行为了。
     */
    fun skillIdByAbility(ctx: Context, vararg words: String): String {
        val lib = ClusterSkillStore.load(ctx)
        for (w in words) {
            val hit = lib.firstOrNull { s ->
                ClusterSkillStore.abilityHaystack(s).any { it.contains(w) }
            }
            if (hit != null) return hit.id
        }
        return ""
    }
}

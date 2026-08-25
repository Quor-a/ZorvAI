package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv

/**
 * CMS v2 依赖解析器（原创运行时 · 依赖关联）。
 *
 * 在执行前校验模块的 4 类依赖是否可用，缺失则给**引导式错误**（指出缺哪个、怎么补）：
 * - [DepKind.MODULE]  另一个已注册 CMS 模块 id
 * - [DepKind.MCP]     MCP 别名
 * - [DepKind.SKILL]   SKILL id
 * - [DepKind.LINUX]   Linux 包（apt-get install -y / pip install，终端沙箱内）
 * - [DepKind.CAPABILITY] 本模块声明的能力 id（旧格式兼容）
 *
 * `provisionedLinux`：部署包自身声明将由 deploy 安装的 Linux 包（如 python3），
 * 视为"部署后即供给"，避免部署前误报缺失。
 */
enum class DepResolution { OK, MISSING, OPTIONAL_MISSING }

data class DepResult(
    val dep: QuroCmsDependency,
    val resolution: DepResolution,
    val detail: String,
)

object CmsDependencyResolver {

    fun resolve(
        context: Context,
        module: QuroCmsModule,
        availableModules: Set<String>,
        availableMcp: Set<String> = emptySet(),
        availableSkills: Set<String> = emptySet(),
        provisionedLinux: Set<String> = emptySet(),
        provisionedEnv: Set<String> = emptySet(),
    ): List<DepResult> {
        return module.dependencies.map { dep ->
            val target = dep.target()
            when (dep.kind) {
                DepKind.MODULE ->
                    if (availableModules.contains(target)) ok(dep, "模块 $target 已注册")
                    else miss(dep, "依赖模块 $target 未安装")
                DepKind.MCP ->
                    if (availableMcp.contains(target)) ok(dep, "MCP $target 可用")
                    else miss(dep, "依赖 MCP 别名 $target 未注册")
                DepKind.SKILL ->
                    if (availableSkills.contains(target)) ok(dep, "SKILL $target 可用")
                    else miss(dep, "依赖 SKILL $target 未注册")
                DepKind.LINUX -> resolveLinux(context, dep, target, provisionedLinux)
                DepKind.ENV -> resolveEnv(context, dep, target, provisionedEnv)
                DepKind.CAPABILITY ->
                    if (module.capabilities.any { it.id == target }) ok(dep, "能力 $target 已声明")
                    else miss(dep, "依赖能力 $target 未在本模块声明")
            }
        }
    }

    private fun resolveLinux(context: Context, dep: QuroCmsDependency, pkg: String, provisioned: Set<String>): DepResult {
        // 部署包声明会安装 → 视为供给，不报缺失。
        if (provisioned.contains(pkg)) return ok(dep, "Linux 包 $pkg（部署时安装）")
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) {
            return if (dep.optional) DepResult(dep, DepResolution.OPTIONAL_MISSING, "Linux 环境未就绪（可选依赖暂不校验）")
            else miss(dep, "依赖 Linux 包 $pkg，但终端环境(proot)未就绪")
        }
        val (c, _) = QuroLinuxEnv.run(context, "dpkg -s $pkg >/dev/null 2>&1", timeoutMs = 20_000)
        return if (c == 0) ok(dep, "Linux 包 $pkg 已装")
        else miss(dep, "依赖 Linux 包 $pkg 未安装（需 apt-get install $pkg）")
    }

    private fun resolveEnv(context: Context, dep: QuroCmsDependency, profile: String, provisioned: Set<String>): DepResult {
        if (provisioned.contains(profile)) return ok(dep, "终端环境 $profile（部署时装配）")
        val p = EnvProfile.parse(profile)
        if (p == null) return miss(dep, "依赖未知环境档 $profile（可选值：NODE/PYTHON/SSH/JAVA/RUST/GO）")
        if (CmsEnvProvisioner.isReady(context, p)) return ok(dep, "终端环境 $profile 已就绪")
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) {
            return if (dep.optional) DepResult(dep, DepResolution.OPTIONAL_MISSING, "终端环境未就绪（可选环境暂不校验）")
            else miss(dep, "依赖终端环境 $profile，但终端(proot)未就绪")
        }
        return miss(dep, "依赖终端环境 $profile 未装配（部署时将自动安装 ${p.profileName}）")
    }

    private fun ok(dep: QuroCmsDependency, detail: String) = DepResult(dep, DepResolution.OK, detail)

    private fun miss(dep: QuroCmsDependency, detail: String): DepResult =
        if (dep.optional) DepResult(dep, DepResolution.OPTIONAL_MISSING, "$detail（可选，不阻塞）")
        else DepResult(dep, DepResolution.MISSING, detail)

    /** 是否可继续执行：无 MISSING（仅 OPTIONAL_MISSING / OK 允许）。 */
    fun allResolved(results: List<DepResult>): Boolean = results.none { it.resolution == DepResolution.MISSING }

    /** 生成引导式错误（指出缺哪个、怎么补）。 */
    fun guidance(results: List<DepResult>): String {
        val missing = results.filter { it.resolution == DepResolution.MISSING }
        if (missing.isEmpty()) return "✅ 依赖全部满足"
        return buildString {
            appendLine("⛔ 依赖缺失，无法执行：")
            missing.forEach { r ->
                append("• [${r.dep.kind.name}] ${r.dep.target()} — ${r.detail}\n")
            }
        }
    }
}

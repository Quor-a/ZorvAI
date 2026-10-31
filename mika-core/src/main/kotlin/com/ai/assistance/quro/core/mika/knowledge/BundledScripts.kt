package com.ai.assistance.quro.core.mika.knowledge

/** 知识或技能目录里的一个脚本此刻能不能跑;不能跑时 [reason] 写原因。 */
data class ScriptState(val file: String, val runnable: Boolean, val reason: String?)

/** 能跑自带脚本的能力交给 knowledge_read、skill_read 与参考资料附注的。 */
interface BundledScripts {
    /** 本任务提供跑脚本的工具(运行环境可能还在准备)。 */
    fun offered(): Boolean

    /** 运行环境此刻就绪时先把技能目录核对成打包的那份,返回 true;没就绪为 false。 */
    suspend fun ready(): Boolean

    /** [skillDir] 下清单里的脚本与此刻能不能跑。 */
    fun scripts(skillDir: String): List<ScriptState>
}

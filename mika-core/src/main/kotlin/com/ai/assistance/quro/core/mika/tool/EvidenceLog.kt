package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.observation.ObsForm
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.verify.EvidenceLedger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val RECENT_READS = 3
internal const val MAX_READ_CHARS = EvidenceLedger.READ_CHARS
private const val MAX_ACTION_LOG = 30
/** 用户给的事实:最近几条,每条按头尾截短。 */
private const val USER_FACTS = 6
private const val USER_FACT_CHARS = EvidenceLedger.OUTPUT_CHARS

/**
 * 完成前交给核查器的材料。读到的东西都是观察([Observation]:读到的界面与网页、沙箱输出与读到的文件、
 * 工具结果里的事实),由能力在工具结果里给出,分发器经 [observe] 记进证据账本 [ledger],带上第几步、什么时候;
 * 用户说的([ObsKind.UserSaid]:记忆检索结果、对提问的回答)另记在 [userFacts]。另有动作清单、每次接管的结局。
 * 每个工具自己决定读到了什么([AgentTool.observations])、动作清单写什么([AgentTool.logTarget])。
 */
internal class EvidenceLog(private val clock: () -> Long = System::currentTimeMillis) {
    val ledger = EvidenceLedger()
    /** 现在是第几步(由调度器在每次执行工具前设好),记进账本的来处。 */
    var step: Int = 0
    /** 最近几次读到的界面状态的文字(界面读取去重用)。 */
    val recentReads = ArrayDeque<String>()
    val actionLog = ArrayDeque<String>()
    /** 用户给的事实:记忆检索结果、本次任务里对 ask_user 的回答。 */
    val userFacts = ArrayDeque<String>()
    /** list_apps 给过的 App 名字,launch_app 的事实里带上。 */
    val appLabels = mutableMapOf<String, String>()
    /** 每次接管的结局;不限条数,不会像动作清单那样滚出去。 */
    val takeovers = mutableListOf<String>()
    /** 助手用 write_file 写过的文件:之后 read_file 读回来的是助手自己写的,不给回答作证。 */
    val writtenPaths = mutableSetOf<String>()

    /**
     * 记一条观察:用户说的进 [userFacts](按头尾截短),其余进账本(脱敏、截短、去掉回显,见 [EvidenceLedger.add])。
     * 返回账本里记下的那一条;没进账本(用户说的、截短后为空的)为 null。
     */
    fun observe(o: Observation): Observation? {
        if (o.kind == ObsKind.UserSaid) {
            noteUserFact(o.text)
            return null
        }
        if (o.form == ObsForm.Interface) {
            recentReads.addLast(o.text)
            while (recentReads.size > RECENT_READS) recentReads.removeFirst()
        }
        return ledger.add(o, step, clock())
    }

    /** 助手打进输入框(不是密码框)的字:输入框里的回显不能给回答作证。 */
    fun noteTyped(text: String) = ledger.noteTyped(text)

    private fun noteUserFact(text: String) {
        userFacts.addLast(if (text.length <= USER_FACT_CHARS) text else text.take(USER_FACT_CHARS * 3 / 4) + "\n…(中间省略)…\n" + text.takeLast(USER_FACT_CHARS / 4))
        while (userFacts.size > USER_FACTS) userFacts.removeFirst()
    }

    fun noteTakeover(line: String) {
        ledger.noteTakeover()
        takeovers += line
        actionLog.addLast(line)
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }

    /**
     * 动作清单的一行:[label] 是「第 N 步」,[target] 是动作的对象、[typed] 是输入的字(都由工具给),
     * [wide] 时对象留长一点(网址、命令、路径)。
     */
    fun logAction(label: String, name: String, target: String?, typed: String?, r: ToolExecution, wide: Boolean) {
        val effect = when {
            r.content["ok"] != JsonPrimitive(true) -> (r.content["error"] as? JsonPrimitive)?.content
                ?: (r.content["exitCode"] as? JsonPrimitive)?.let { "退出码 ${it.content}" } ?: "失败"
            r.changed == false -> "画面没变化"
            else -> "生效"
        }
        val what = listOfNotNull(target?.take(if (wide) 120 else 40), typed).joinToString(" ")
        actionLog.addLast("$label $name($what) $effect")
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }
}

internal fun JsonObject.argStr(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

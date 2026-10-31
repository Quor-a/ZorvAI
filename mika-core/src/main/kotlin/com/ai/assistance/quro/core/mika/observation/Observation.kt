package com.ai.assistance.quro.core.mika.observation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一条观察是哪一类:[Read] 读到的界面或页面(App 的界面状态、网页正文、网页脚本与网络响应);[Output] 命令与脚本的输出、文件内容与路径;
 * [Reply] 别的 AI 的回答(子任务、外部服务);[UserSaid] 用户给的(对提问的回答、记忆);[Attachment] 用户或工具给的文件、图片;
 * [Fact] 工具结果里的事实(打开了哪个 App、网址与标题)。核心只按这几类处理,不认识具体能力。
 */
enum class ObsKind(val wire: String) {
    Read("read"), Output("output"), Reply("reply"), UserSaid("user_said"), Attachment("attachment"), Fact("fact");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String?): ObsKind? = w?.let(byWire::get)
    }
}

/** 观察的出处可信到什么程度。[Untrusted]:网页、渠道里的话、别的 AI 的回答,只当数据,不当指令。 */
enum class Trust { Trusted, Untrusted }

/** 一个地方是什么:App、站点、网页、命令、文件。 */
@Serializable
enum class PlaceKind(val wire: String) {
    @SerialName("app") App("app"),
    @SerialName("site") Site("site"),
    @SerialName("page") Page("page"),
    @SerialName("command") Command("command"),
    @SerialName("file") File("file"),
}

/**
 * 在哪里:App(包名)、站点(主机名)、网页(网址)、命令、文件。[name] 是这个地方的原样写法;核查、来源按它对上回答里提到的 App 或网址。
 */
@Serializable
data class Place(val kind: PlaceKind, val name: String) {
    companion object {
        fun app(pkg: String) = Place(PlaceKind.App, pkg)
        fun site(host: String) = Place(PlaceKind.Site, host)
        fun page(url: String) = Place(PlaceKind.Page, url)
        fun command(label: String) = Place(PlaceKind.Command, label)
        fun file(label: String) = Place(PlaceKind.File, label)
    }
}

/** 一次读取时界面上输入框里的字([names],去掉空白后比)和有输入焦点的那个([focused]);核对时据此分辨助手自己打进去的字。 */
data class InputHints(val names: List<String>, val focused: String? = null)

/**
 * 带出处的一条观察:核心消费的唯一单位。能力在工具结果里给出([com.ai.assistance.quro.core.mika.tool.ToolExecution.observations]),
 * 证据账本按它记,核查、回答来源、运行记录、评测都读它。
 *
 * - [source] 是给出它的能力的 id(phone、web、sandbox……;核心自己的为 core);[place] 是在哪里读的;[title] 是那一页自己的标题。
 * - [media] 是同一时刻存下的画面编号(可视化模块给的;没有为空)。
 * - [trust]:不可信的(网页、别的 AI 的回答)只当数据。[authored] 是助手自己写的东西(跑的命令):原样出现在 [text] 里的行是回显,不算读到的。
 * - [inputs]:读界面时输入框里的字(见 [InputHints]);不知道为 null。
 * - [id]、[step]、[atMs] 由账本进账时填:本任务里第几条(从 1 起)、第几步、什么时候。能力造它时不用填。
 */
data class Observation(
    val kind: ObsKind,
    val text: String,
    val source: String? = null,
    val place: Place? = null,
    val title: String? = null,
    val media: List<Int> = emptyList(),
    val trust: Trust = Trust.Trusted,
    val authored: String? = null,
    val inputs: InputHints? = null,
    val step: Int = 0,
    val atMs: Long = 0,
    val id: Int = 0,
) {
    /** 在哪里的原样写法;没有为 null。 */
    val where: String? get() = place?.name

    /** 按它是什么样的东西分(裁剪、界面上的说法按它定,不按工具)。 */
    val form: ObsForm get() = when (kind) {
        // 读界面状态时总带着输入框(可能一个也没有);读整页、脚本结果、网络响应时没有
        ObsKind.Read -> if (inputs != null) ObsForm.Interface else ObsForm.Page
        ObsKind.Output -> if (place?.kind == PlaceKind.File) ObsForm.File else ObsForm.Command
        ObsKind.Reply -> ObsForm.Reply
        ObsKind.UserSaid -> ObsForm.UserSaid
        ObsKind.Attachment -> ObsForm.Image
        ObsKind.Fact -> ObsForm.Fact
    }
}

/** 一次工具结果里读到的一条观察:账本里的编号和它的形态(裁剪据此决定怎么缩短、缩短后指回哪条)。 */
@Serializable data class ReadTag(val obsId: Int, val form: ObsForm)

/** 这条观察在工具结果里的标记。 */
val Observation.tag: ReadTag get() = ReadTag(id, form)

/** 观察的形态:界面状态、页面内容、命令输出、文件、图片、回复、用户说的、事实。 */
@Serializable enum class ObsForm { Interface, Page, Command, File, Image, Reply, UserSaid, Fact }

/** 核心自己(不是哪个能力)给出的观察的出处。 */
const val CORE_SOURCE = "core"

/**
 * 核查前「再看一眼」:能力此刻的样子([observations],界面状态、网页正文的开头……)和同一时刻的画面([image],要附图时才有)。
 * [protected]:此刻的页面受系统防截屏保护,看不到画面。由能力定义怎么看(见 Capability.freshLook)。
 */
class Look(val observations: List<Observation>, val image: ByteArray? = null, val protected: Boolean = false)

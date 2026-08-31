package com.ai.assistance.quro.core.linux

/**
 * Linux 包管理器统一抽象（参照 Kai `linux/` 的 PackageManagerSpec 设计重写）。
 *
 * 为什么需要抽象层：应用内的 Linux 环境可能是 Alpine（apk），也可能是 Ubuntu/Debian（apt），
 * 甚至用户自己换成了 Fedora/Arch。若把 `apt-get install` 写死在工具里，
 * 换个发行版就意味着「装软件」这个动作直接废掉，且报错信息对用户毫无指导性
 * （`apt-get: not found` 并不能告诉用户该用什么）。
 *
 * 抽象层把「装包」这个**意图**与「用哪个包管理器实现」解耦：
 * 运行时读取 /etc/os-release 探测发行版 → 选出对应的 [PackageManagerSpec] → 生成正确命令。
 *
 * 本文件只负责**命令生成**，不负责执行；执行交给 [QuroLinuxEnv.run]，
 * 这样同一套命令既能在 proot 环境跑，也能在 chroot / 真实 root 下复用。
 */

/** 支持的 Linux 发行版。 */
enum class LinuxDistro(
    /** /etc/os-release 里的 ID 或 ID_LIKE 值。 */
    val ids: Set<String>,
    val displayName: String,
) {
    ALPINE(setOf("alpine"), "Alpine Linux"),
    DEBIAN(setOf("debian"), "Debian"),
    UBUNTU(setOf("ubuntu"), "Ubuntu"),
    FEDORA(setOf("fedora", "rhel", "centos", "rocky", "almalinux"), "Fedora / RHEL 系"),
    ARCH(setOf("arch", "archarm", "manjaro"), "Arch Linux"),
    UNKNOWN(emptySet(), "未知发行版"),
    ;

    companion object {
        fun fromId(id: String?): LinuxDistro {
            if (id.isNullOrBlank()) return UNKNOWN
            val v = id.trim().lowercase().trim('"', '\'')
            return entries.firstOrNull { v in it.ids } ?: UNKNOWN
        }
    }
}

/**
 * 包管理器契约：把「装/卸/查/更新」等意图翻译成该发行版的实际命令。
 *
 * 命令都带非交互参数（-y / --noconfirm），因为环境里没有用户能回答 y/n，
 * 缺了它们命令会挂起直到超时。
 */
interface PackageManagerSpec {
    /** 底层二进制名，如 apk / apt-get / dnf / pacman。 */
    val binary: String

    val displayName: String

    fun install(pkgs: List<String>): String
    fun remove(pkgs: List<String>): String
    fun update(): String
    fun upgrade(): String
    fun search(query: String): String
    fun listInstalled(filter: String? = null): String
    fun info(pkg: String): String
    fun clean(): String
}

/** Alpine：apk。 */
object ApkPackageManager : PackageManagerSpec {
    override val binary = "apk"
    override val displayName = "apk (Alpine)"

    override fun install(pkgs: List<String>) = "apk add --no-cache ${pkgs.joinToString(" ")}"
    override fun remove(pkgs: List<String>) = "apk del ${pkgs.joinToString(" ")}"
    override fun update() = "apk update"
    override fun upgrade() = "apk upgrade --available"
    override fun search(query: String) = "apk search ${q(query)}"
    override fun listInstalled(filter: String?) =
        if (filter.isNullOrBlank()) "apk info" else "apk info | grep -i ${q(filter)}"

    override fun info(pkg: String) = "apk info -a ${q(pkg)}"
    override fun clean() = "apk cache clean"
}

/** Debian / Ubuntu：apt-get（查询用 apt-cache，列表用 dpkg）。 */
object AptPackageManager : PackageManagerSpec {
    override val binary = "apt-get"
    override val displayName = "apt (Debian/Ubuntu)"

    // --no-install-recommends：环境是容器化的 proot，装推荐包会显著拖慢并占用空间
    override fun install(pkgs: List<String>) =
        "apt-get install -y --no-install-recommends ${pkgs.joinToString(" ")}"

    override fun remove(pkgs: List<String>) = "apt-get remove -y ${pkgs.joinToString(" ")}"
    override fun update() = "apt-get update"
    override fun upgrade() = "apt-get upgrade -y"
    override fun search(query: String) = "apt-cache search ${q(query)}"
    override fun listInstalled(filter: String?) =
        if (filter.isNullOrBlank()) "dpkg -l" else "dpkg -l | grep -i ${q(filter)}"

    override fun info(pkg: String) = "apt-cache show ${q(pkg)}"
    override fun clean() = "apt-get clean && apt-get autoremove -y"
}

/** Fedora / RHEL 系：dnf（老版本是 yum，这里优先 dnf）。 */
object DnfPackageManager : PackageManagerSpec {
    override val binary = "dnf"
    override val displayName = "dnf (Fedora/RHEL)"

    override fun install(pkgs: List<String>) = "dnf install -y ${pkgs.joinToString(" ")}"
    override fun remove(pkgs: List<String>) = "dnf remove -y ${pkgs.joinToString(" ")}"
    override fun update() = "dnf check-update || true"
    override fun upgrade() = "dnf upgrade -y"
    override fun search(query: String) = "dnf search ${q(query)}"
    override fun listInstalled(filter: String?) =
        if (filter.isNullOrBlank()) "dnf list installed" else "dnf list installed | grep -i ${q(filter)}"

    override fun info(pkg: String) = "dnf info ${q(pkg)}"
    override fun clean() = "dnf clean all"
}

/** Arch：pacman。 */
object PacmanPackageManager : PackageManagerSpec {
    override val binary = "pacman"
    override val displayName = "pacman (Arch)"

    override fun install(pkgs: List<String>) = "pacman -S --noconfirm ${pkgs.joinToString(" ")}"
    override fun remove(pkgs: List<String>) = "pacman -R --noconfirm ${pkgs.joinToString(" ")}"
    override fun update() = "pacman -Sy"
    override fun upgrade() = "pacman -Syu --noconfirm"
    override fun search(query: String) = "pacman -Ss ${q(query)}"
    override fun listInstalled(filter: String?) =
        if (filter.isNullOrBlank()) "pacman -Q" else "pacman -Q | grep -i ${q(filter)}"

    override fun info(pkg: String) = "pacman -Si ${q(pkg)}"
    override fun clean() = "pacman -Scc --noconfirm"
}

/**
 * 命令参数转义：包管理器命令最终交给 shell 执行，
 * 这里挡住最常见的注入/误解析字符，避免 AI 拼出的包名把整条命令带偏。
 */
private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** 发行版探测与包管理器选择。 */
object QuroLinuxDistroDetector {

    /** 包管理器注册表。按发行版取实现，未收录的发行版回落 apt（覆盖率最高）。 */
    private val MANAGERS: Map<LinuxDistro, PackageManagerSpec> = mapOf(
        LinuxDistro.ALPINE to ApkPackageManager,
        LinuxDistro.DEBIAN to AptPackageManager,
        LinuxDistro.UBUNTU to AptPackageManager,
        LinuxDistro.FEDORA to DnfPackageManager,
        LinuxDistro.ARCH to PacmanPackageManager,
        LinuxDistro.UNKNOWN to AptPackageManager,
    )

    /**
     * 解析 /etc/os-release 内容判断发行版。
     * 优先看 ID，其次 ID_LIKE（如 Linux Mint 的 ID_LIKE=ubuntu）。
     * 示例内容：
     * ```
     * ID=ubuntu
     * ID_LIKE=debian
     * VERSION_ID="24.04"
     * ```
     */
    fun detect(osRelease: String?): LinuxDistro {
        if (osRelease.isNullOrBlank()) return LinuxDistro.UNKNOWN
        val map = parseOsRelease(osRelease)
        val id = map["ID"]
        val idLike = map["ID_LIKE"]

        LinuxDistro.fromId(id).let { if (it != LinuxDistro.UNKNOWN) return it }
        // ID_LIKE 可能是空格分隔的多个值（如 "ubuntu debian"）
        idLike?.split(Regex("\\s+"))?.forEach { token ->
            LinuxDistro.fromId(token).let { if (it != LinuxDistro.UNKNOWN) return it }
        }
        return LinuxDistro.UNKNOWN
    }

    /** 解析 KEY=VALUE 文本（值可能带引号）。 */
    fun parseOsRelease(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val raw = line.substring(idx + 1).trim()
                out[key] = raw.trim('"', '\'')
            }
        return out
    }

    /** 取发行版对应的包管理器。 */
    fun packageManagerFor(distro: LinuxDistro): PackageManagerSpec =
        MANAGERS[distro] ?: AptPackageManager

    /** 便捷：直接从 os-release 内容得到包管理器。 */
    fun packageManagerFor(osRelease: String?): PackageManagerSpec =
        packageManagerFor(detect(osRelease))
}

/** 探测发行版的命令（在 Linux 环境内执行，取其输出喂给 [QuroLinuxDistroDetector.detect]）。 */
const val DETECT_DISTRO_CMD = "cat /etc/os-release 2>/dev/null || echo ''"

/**
 * 包管理操作，与 linux_pkg 工具的 action 参数一一对应。
 */
enum class PkgAction(val id: String, val summary: String) {
    INSTALL("install", "安装软件包"),
    REMOVE("remove", "卸载软件包"),
    UPDATE("update", "更新软件源索引"),
    UPGRADE("upgrade", "升级全部已装软件"),
    SEARCH("search", "搜索可用软件包"),
    LIST("list", "列出已安装软件包"),
    INFO("info", "查看软件包详情"),
    CLEAN("clean", "清理包缓存"),
    DETECT("detect", "探测当前发行版与包管理器"),
    ;

    companion object {
        fun from(id: String?): PkgAction? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

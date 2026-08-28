package com.ai.assistance.quro.core.linux

/**
 * 命令翻译器（Kotlin 层，非 shell wrapper）。
 *
 * rootfs 是 Ubuntu 24.04，自带 apt/dpkg/apt-get。
 * 当用户（或 AI 工具）输入非 Ubuntu 命令时，在此处翻译成等价的 Ubuntu 命令，
 * 再传给 proot 执行。不往 /usr/local/bin 写任何 wrapper，避免遮蔽原生命令。
 */
object CommandTranslator {

    /**
     * 翻译一条 shell 命令。返回翻译后的命令；如果无需翻译，原样返回。
     * 仅处理「命令名 + 子命令」模式，管道/重定向等复杂场景不拆分。
     */
    fun translate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        // 按管道拆分，逐段翻译（简单场景：只翻译第一个管道段）
        val pipeParts = trimmed.split("\\|".toRegex(), limit = 2)
        val firstPart = pipeParts[0].trim()

        // 跳过注释和空行
        if (firstPart.startsWith("#")) return trimmed

        val tokens = firstPart.split("\\s+".toRegex())
        if (tokens.isEmpty()) return trimmed

        val cmd = tokens[0].lowercase()
        val translated = when (cmd) {
            // ── Termux ──
            "pkg" -> translatePkg(tokens)
            // ── CentOS / Fedora ──
            "yum" -> translateYum(tokens)
            "dnf" -> translateDnf(tokens)
            // ── Arch ──
            "pacman" -> translatePacman(tokens)
            // ── openSUSE ──
            "zypper" -> translateZypper(tokens)
            // ── Void ──
            "xbps-install", "xbps-remove", "xbps-query" -> translateXbps(tokens)
            // ── NixOS ──
            "nix-env" -> translateNix(tokens)
            // ── Clear Linux ──
            "swupd" -> translateSwupd(tokens)
            // ── 原生命令，不翻译 ──
            else -> null
        }

        if (translated != null) {
            // 如果有管道后半段，拼回去
            return if (pipeParts.size > 1) {
                "$translated | ${pipeParts[1]}"
            } else {
                translated
            }
        }
        return trimmed
    }

    // ═══════ Termux pkg ═══════
    private fun translatePkg(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val sub = tokens[1].lowercase()
        val args = tokens.drop(2).joinToString(" ")
        return when (sub) {
            "install", "i" -> "apt install -y $args"
            "uninstall", "remove", "r", "purge" -> "apt remove -y $args"
            "upgrade", "up" -> "apt upgrade -y"
            "update" -> "apt update"
            "search", "s" -> "apt search $args"
            "list-installed" -> "apt list --installed"
            "reinstall" -> "apt install --reinstall -y $args"
            else -> "apt $args"
        }
    }

    // ═══════ CentOS yum ═══════
    private fun translateYum(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val sub = tokens[1].lowercase()
        val args = tokens.drop(2).joinToString(" ")
        return when (sub) {
            "install", "i" -> "apt install -y $args"
            "remove", "erase", "r" -> "apt remove -y $args"
            "update" -> "apt update"
            "upgrade" -> "apt upgrade -y"
            "search" -> "apt search $args"
            "info" -> "apt show $args"
            "list" -> "apt list --installed"
            "clean" -> "apt clean"
            else -> "apt $args"
        }
    }

    // ═══════ Fedora dnf ═══════
    private fun translateDnf(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val sub = tokens[1].lowercase()
        val args = tokens.drop(2).joinToString(" ")
        return when (sub) {
            "install", "i" -> "apt install -y $args"
            "remove", "erase", "r" -> "apt remove -y $args"
            "upgrade", "up" -> "apt upgrade -y"
            "check-update" -> "apt update"
            "search" -> "apt search $args"
            "info" -> "apt show $args"
            "list" -> "apt list --installed"
            "clean" -> "apt clean"
            else -> "apt $args"
        }
    }

    // ═══════ Arch pacman ═══════
    private fun translatePacman(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val flags = mutableListOf<String>()
        val pkgs = mutableListOf<String>()
        var i = 1
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-S" || t == "--sync" -> flags.add("install")
                t == "-R" || t == "--remove" -> flags.add("remove")
                t == "-Syu" || t == "-S -u" -> return "apt update && apt upgrade -y"
                t == "-Ss" || t == "--search" -> flags.add("search")
                t == "-Si" || t == "--info" -> flags.add("show")
                t == "-Q" || t == "--query" -> flags.add("list")
                t.startsWith("-") -> flags.add(t) // 透传其他 flag
                else -> pkgs.add(t)
            }
            i++
        }
        val action = flags.firstOrNull() ?: return null
        val pkgStr = pkgs.joinToString(" ")
        return when (action) {
            "install" -> "apt install -y $pkgStr"
            "remove" -> "apt remove -y $pkgStr"
            "search" -> "apt search $pkgStr"
            "show" -> "apt show $pkgStr"
            "list" -> "apt list --installed"
            else -> "apt $pkgStr"
        }
    }

    // ═══════ openSUSE zypper ═══════
    private fun translateZypper(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val sub = tokens[1].lowercase()
        val args = tokens.drop(2).joinToString(" ")
        return when (sub) {
            "install", "in", "i" -> "apt install -y $args"
            "remove", "rm", "r", "uninstall" -> "apt remove -y $args"
            "update", "up", "refresh" -> "apt update"
            "list-updates", "lu" -> "apt update"
            "search", "se", "si" -> "apt search $args"
            "info", "if" -> "apt show $args"
            else -> "apt $args"
        }
    }

    // ═══════ Void xbps ═══════
    private fun translateXbps(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val cmd = tokens[0].lowercase()
        val sub = tokens[1].lowercase()
        val args = tokens.drop(2).joinToString(" ")
        return when (cmd) {
            "xbps-install" -> when {
                sub == "-S" || sub == "--sync" -> "apt install -y $args"
                sub == "-Su" || sub == "-S -u" -> "apt update && apt upgrade -y"
                sub.startsWith("-") -> "apt install -y $args"
                else -> "apt install -y $sub $args"
            }
            "xbps-remove" -> when {
                sub == "-R" || sub == "--remove" -> "apt remove -y $args"
                else -> "apt remove -y $sub $args"
            }
            "xbps-query" -> when {
                sub == "-l" || sub == "--list" -> "apt list --installed"
                sub == "-s" || sub == "--search" -> "apt search $args"
                else -> "apt list --installed"
            }
            else -> null
        }
    }

    // ═══════ NixOS nix-env ═══════
    private fun translateNix(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val sub = tokens[1].lowercase()
        val pkg = tokens.drop(2).joinToString(" ").let {
            // nixpkgs.xxx → xxx
            it.removePrefix("nixpkgs.")
        }
        return when (sub) {
            "-i", "--install", "-iA" -> "apt install -y $pkg"
            "-e", "--erase" -> "apt remove -y $pkg"
            "-u", "--upgrade" -> "apt upgrade -y"
            "-q", "--query", "-p", "--profile" -> "apt list --installed"
            else -> "apt $pkg"
        }
    }

    // ═══════ Clear Linux swupd ═══════
    private fun translateSwupd(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val sub = tokens[1].lowercase()
        val args = tokens.drop(2).joinToString(" ")
        return when (sub) {
            "bundle-add" -> "apt install -y $args"
            "bundle-remove" -> "apt remove -y $args"
            "bundle-list" -> "apt list --installed"
            "update" -> "apt update"
            "search" -> "apt search $args"
            else -> "apt $args"
        }
    }
}

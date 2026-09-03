package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.quro.terminal.data.MirrorSource
import com.ai.assistance.quro.terminal.data.PackageManagerType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SourceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // 定义所有内置的源
    private val builtInAptSources = listOf(
        MirrorSource("tuna_apt", "清华源", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/", true),
        MirrorSource("bfsu_apt", "北外源", "https://mirrors.bfsu.edu.cn/ubuntu-ports/", true),
        MirrorSource("aliyun_apt", "阿里源", "https://mirrors.aliyun.com/ubuntu-ports/", true),
        MirrorSource("ustc_apt", "中科大源", "https://mirrors.ustc.edu.cn/ubuntu-ports/", true),
        MirrorSource("official_apt", "官方源", "http://ports.ubuntu.com/ubuntu-ports/", false)
    )

    private val builtInPipSources = listOf(
        MirrorSource("tuna_pip", "清华源", "https://pypi.tuna.tsinghua.edu.cn/simple", true),
        MirrorSource("bfsu_pip", "北外源", "https://mirrors.bfsu.edu.cn/pypi/web/simple", true),
        MirrorSource("aliyun_pip", "阿里源", "https://mirrors.aliyun.com/pypi/simple/", true),
        MirrorSource("ustc_pip", "中科大源", "https://pypi.mirrors.ustc.edu.cn/simple/", true),
        MirrorSource("official_pip", "官方源", "https://pypi.org/simple", true)
    )
    
    private val builtInNpmSources = listOf(
        MirrorSource("taobao_npm", "淘宝源", "https://registry.npmmirror.com/", true),
        MirrorSource("tencent_npm", "腾讯源", "https://mirrors.cloud.tencent.com/npm/", true),
        MirrorSource("huawei_npm", "华为源", "https://repo.huaweicloud.com/repository/npm/", true),
        MirrorSource("official_npm", "官方源", "https://registry.npmjs.org/", true)
    )
    
    private val builtInRustSources = listOf(
        MirrorSource("ustc_rust", "中科大源", "https://mirrors.ustc.edu.cn/rust-static", true),
        MirrorSource("tuna_rust", "清华源", "https://mirrors.tuna.tsinghua.edu.cn/rustup", true),
        MirrorSource("bfsu_rust", "北外源", "https://mirrors.bfsu.edu.cn/rustup", true),
        MirrorSource("sjtu_rust", "上海交大源", "https://mirrors.sjtug.sjtu.edu.cn/rust-static", true),
        MirrorSource("official_rust", "官方源", "https://static.rust-lang.org", true)
    )

    // 获取自定义源
    private fun getCustomSources(pm: PackageManagerType): List<MirrorSource> {
        val key = when (pm) {
            PackageManagerType.APT -> "custom_apt_sources"
            PackageManagerType.PIP -> "custom_pip_sources"
            PackageManagerType.NPM -> "custom_npm_sources"
            PackageManagerType.RUST -> "custom_rust_sources"
        }
        val jsonString = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<MirrorSource>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // 保存自定义源
    fun saveCustomSource(pm: PackageManagerType, source: MirrorSource) {
        val customSources = getCustomSources(pm).toMutableList()
        // 如果已存在相同ID的源，替换它；否则添加
        val index = customSources.indexOfFirst { it.id == source.id }
        if (index >= 0) {
            customSources[index] = source
        } else {
            customSources.add(source)
        }
        
        val key = when (pm) {
            PackageManagerType.APT -> "custom_apt_sources"
            PackageManagerType.PIP -> "custom_pip_sources"
            PackageManagerType.NPM -> "custom_npm_sources"
            PackageManagerType.RUST -> "custom_rust_sources"
        }
        prefs.edit().putString(key, json.encodeToString(customSources)).apply()
    }
    
    // 删除自定义源。返回是否删掉的是当前选中的源（供调用方决定是否切回内置源）
    fun deleteCustomSource(pm: PackageManagerType, sourceId: String): Boolean {
        val wasSelected = getSelectedSourceId(pm) == sourceId
        val customSources = getCustomSources(pm).toMutableList()
        customSources.removeAll { it.id == sourceId }

        val key = when (pm) {
            PackageManagerType.APT -> "custom_apt_sources"
            PackageManagerType.PIP -> "custom_pip_sources"
            PackageManagerType.NPM -> "custom_npm_sources"
            PackageManagerType.RUST -> "custom_rust_sources"
        }
        prefs.edit().putString(key, json.encodeToString(customSources)).apply()

        // 删掉的是当前选中源时，同步把选中偏好回退到默认内置源，杜绝悬空选中
        if (wasSelected) {
            setSelectedSourceId(pm, defaultSourceId(pm))
        }
        return wasSelected
    }
    
    // 获取所有源（内置 + 自定义）
    val aptSources: List<MirrorSource>
        get() = builtInAptSources + getCustomSources(PackageManagerType.APT)
    
    val pipSources: List<MirrorSource>
        get() = builtInPipSources + getCustomSources(PackageManagerType.PIP)
    
    val npmSources: List<MirrorSource>
        get() = builtInNpmSources + getCustomSources(PackageManagerType.NPM)
    
    val rustSources: List<MirrorSource>
        get() = builtInRustSources + getCustomSources(PackageManagerType.RUST)

    // 默认（第一个内置）源ID，作为选中项悬空/未设置时的安全回退
    private fun defaultSourceId(pm: PackageManagerType): String = when (pm) {
        PackageManagerType.APT -> "tuna_apt"
        PackageManagerType.PIP -> "tuna_pip"
        PackageManagerType.NPM -> "taobao_npm"
        PackageManagerType.RUST -> "ustc_rust"
    }

    // 获取当前为特定包管理器选择的源ID
    fun getSelectedSourceId(pm: PackageManagerType): String {
        val key = when (pm) {
            PackageManagerType.APT -> "selected_apt_source"
            PackageManagerType.PIP -> "selected_pip_source"
            PackageManagerType.NPM -> "selected_npm_source"
            PackageManagerType.RUST -> "selected_rust_source"
        }
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: defaultSourceId(pm)
    }
    
    // 获取当前源
    fun getSelectedSource(pm: PackageManagerType): MirrorSource {
        val id = getSelectedSourceId(pm)
        // 选中项不存在（如自定义源被删后偏好悬空）时回退到第一个内置源，避免 NPE
        return when (pm) {
            PackageManagerType.APT -> aptSources.find { it.id == id } ?: builtInAptSources.first()
            PackageManagerType.PIP -> pipSources.find { it.id == id } ?: builtInPipSources.first()
            PackageManagerType.NPM -> npmSources.find { it.id == id } ?: builtInNpmSources.first()
            PackageManagerType.RUST -> rustSources.find { it.id == id } ?: builtInRustSources.first()
        }
    }

    // 保存选择的源ID
    fun setSelectedSourceId(pm: PackageManagerType, sourceId: String) {
        prefs.edit().putString(
            when (pm) {
                PackageManagerType.APT -> "selected_apt_source"
                PackageManagerType.PIP -> "selected_pip_source"
                PackageManagerType.NPM -> "selected_npm_source"
                PackageManagerType.RUST -> "selected_rust_source"
            },
            sourceId
        ).apply()
    }
    
    // 生成更改APT源的Shell命令
    fun getAptSourceChangeCommand(source: MirrorSource): String {
        val sourceUrl = source.url
        return """
        change_ubuntu_source(){
          cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From ZorvAI Settings - ${source.name}
        deb ${sourceUrl} noble main restricted universe multiverse
        deb ${sourceUrl} noble-updates main restricted universe multiverse
        deb ${sourceUrl} noble-backports main restricted universe multiverse
        EOF
          echo "APT source changed to: ${source.name}"
        }
        change_ubuntu_source
        """.trimIndent()
    }
    
    // 生成更改Pip/Uv源的Shell命令
    fun getPipSourceChangeCommand(source: MirrorSource): String {
        val sourceUrl = source.url
        return """
        # For pip/pipx
        mkdir -p ~/.config/pip
        echo '[global]' > ~/.config/pip/pip.conf
        echo 'index-url = ${sourceUrl}' >> ~/.config/pip/pip.conf
        
        # For uv/uvx
        mkdir -p ~/.config/uv
        echo 'index-url = "${sourceUrl}"' > ~/.config/uv/uv.toml
        echo "Pip/Uv source updated to ${source.name}"
        """.trimIndent()
    }

    // 生成更改NPM源的Shell命令
    fun getNpmSourceChangeCommand(source: MirrorSource): String {
        val sourceUrl = source.url
        return "npm config set registry ${sourceUrl}"
    }
    
    // 生成Rust镜像源的环境变量设置命令
    fun getRustSourceEnvCommand(source: MirrorSource): String {
        val baseUrl = source.url
        return """
        export RUSTUP_DIST_SERVER=${baseUrl}
        export RUSTUP_UPDATE_ROOT=${baseUrl}/rustup
        """.trimIndent()
    }
}

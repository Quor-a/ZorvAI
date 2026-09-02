package com.ai.assistance.quro.core.linux

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 镜像源管理器
 * 为 ZorvAI 提供镜像源管理功能，支持 APT、Pip、NPM、Rust 包管理器
 */
class SourceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)

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
            val jsonArray = JSONArray(jsonString)
            val sources = mutableListOf<MirrorSource>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                sources.add(MirrorSource(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    isChinese = obj.optBoolean("isChinese", true)
                ))
            }
            sources
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
        
        val jsonArray = JSONArray()
        customSources.forEach { mirrorSource ->
            val obj = JSONObject()
            obj.put("id", mirrorSource.id)
            obj.put("name", mirrorSource.name)
            obj.put("url", mirrorSource.url)
            obj.put("isChinese", mirrorSource.isChinese)
            jsonArray.put(obj)
        }
        
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }
    
    // 删除自定义源
    fun deleteCustomSource(pm: PackageManagerType, sourceId: String) {
        val customSources = getCustomSources(pm).toMutableList()
        customSources.removeAll { it.id == sourceId }
        
        val key = when (pm) {
            PackageManagerType.APT -> "custom_apt_sources"
            PackageManagerType.PIP -> "custom_pip_sources"
            PackageManagerType.NPM -> "custom_npm_sources"
            PackageManagerType.RUST -> "custom_rust_sources"
        }
        
        val jsonArray = JSONArray()
        customSources.forEach { mirrorSource ->
            val obj = JSONObject()
            obj.put("id", mirrorSource.id)
            obj.put("name", mirrorSource.name)
            obj.put("url", mirrorSource.url)
            obj.put("isChinese", mirrorSource.isChinese)
            jsonArray.put(obj)
        }
        
        prefs.edit().putString(key, jsonArray.toString()).apply()
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

    // 获取当前为特定包管理器选择的源ID
    fun getSelectedSourceId(pm: PackageManagerType): String {
        return when (pm) {
            PackageManagerType.APT -> prefs.getString("selected_apt_source", "tuna_apt") ?: "tuna_apt"
            PackageManagerType.PIP -> prefs.getString("selected_pip_source", "tuna_pip") ?: "tuna_pip"
            PackageManagerType.NPM -> prefs.getString("selected_npm_source", "taobao_npm") ?: "taobao_npm"
            PackageManagerType.RUST -> prefs.getString("selected_rust_source", "ustc_rust") ?: "ustc_rust"
        }
    }
    
    // 获取当前源
    fun getSelectedSource(pm: PackageManagerType): MirrorSource {
        val id = getSelectedSourceId(pm)
        return when (pm) {
            PackageManagerType.APT -> aptSources.find { it.id == id }!!
            PackageManagerType.PIP -> pipSources.find { it.id == id }!!
            PackageManagerType.NPM -> npmSources.find { it.id == id }!!
            PackageManagerType.RUST -> rustSources.find { it.id == id }!!
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
        // 轮次G：APT 源强制 HTTP，与 bootstrap.sh / BUILTIN_BOOTSTRAP 保持一致。
        // proot 内 HTTPS 曾因 CA 证书问题导致 apt-get install 拉 .deb 失败，HTTP TUNA 已验证可用。
        val sourceUrl = source.url.replace("https://", "http://")
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
    
    // 生成所有源配置的Shell命令
    fun generateAllSourceConfigCommands(): String {
        val aptSource = getSelectedSource(PackageManagerType.APT)
        val pipSource = getSelectedSource(PackageManagerType.PIP)
        val npmSource = getSelectedSource(PackageManagerType.NPM)
        val rustSource = getSelectedSource(PackageManagerType.RUST)
        
        return """
        # 配置所有镜像源
        echo "配置镜像源..."
        
        # APT 源
        ${getAptSourceChangeCommand(aptSource)}
        
        # Pip/Uv 源
        ${getPipSourceChangeCommand(pipSource)}
        
        # NPM 源
        ${getNpmSourceChangeCommand(npmSource)}
        
        # Rust 源
        ${getRustSourceEnvCommand(rustSource)}
        
        echo "所有镜像源配置完成"
        """.trimIndent()
    }
}

/**
 * 轮次G：proot 内刷新 APT 索引的稳健命令。
 *
 * 与 bootstrap.sh / bootstrap_fixed.sh / BUILTIN_BOOTSTRAP 中的 quro_manual_apt_index 同源，
 * 但此函数体**独立内联**到交互式终端会话（下拉菜单 / dev_env 工具）命令里，因为 bootstrap
 * 定义的函数不会保留到后续新开的 shell 会话。
 *
 * 设计要点：
 * 1. 若 bootstrap 已建好索引（noble/main Packages 存在且非空），直接跳过，避免每次安装都重拉 12 个组件。
 * 2. 否则用 curl 手动拉清华 TUNA(HTTP) 12 组件 Packages 直写 /var/lib/apt/lists/，
 *    名称规范与 apt 期望一致：<host>_ubuntu-ports_dists_<dist>_<comp>_binary-arm64_Packages。
 * 3. 手动索引无 Release 签名，需配合 bootstrap 注入的 Acquire::AllowInsecureRepositories "true"。
 * 4. 拉取不足 1 个时回退 timeout 25 apt-get update（硬超时，避免占锁/卡死）。
 *
 * 注意：Kotlin 原始字符串里 $ 必须转义为 ${'$'}。
 */
fun quroAptRefreshCommand(): String = """
quro_apt_refresh() {
  local APTL="/var/lib/apt/lists"
  local IDX="${'$'}APTL/mirrors.tuna.tsinghua.edu.cn_ubuntu-ports_dists_noble_main_binary-arm64_Packages"
  if [ -s "${'$'}IDX" ]; then
    echo "[apt] index present, skip refresh"
    return 0
  fi
  local BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists"
  mkdir -p "${'$'}APTL" "${'$'}APTL/partial"
  local ok=0 total=0
  for dist in noble noble-updates noble-security; do
    for comp in main universe multiverse restricted; do
      total=$((total+1))
      local f="mirrors.tuna.tsinghua.edu.cn_ubuntu-ports_dists_${'$'}{dist}_${'$'}{comp}_binary-arm64_Packages"
      if curl -s --max-time 40 -o "${'$'}APTL/${'$'}f.gz" "${'$'}BASE/${'$'}{dist}/${'$'}{comp}/binary-arm64/Packages.gz" \
         && gzip -dc "${'$'}APTL/${'$'}f.gz" > "${'$'}APTL/${'$'}f" 2>/dev/null && [ -s "${'$'}APTL/${'$'}f" ]; then
        rm -f "${'$'}APTL/${'$'}f.gz"; ok=$((ok+1))
      else
        rm -f "${'$'}APTL/${'$'}f.gz" "${'$'}APTL/${'$'}f" 2>/dev/null || true
      fi
    done
  done
  echo "[apt] manual index: ${'$'}ok/${'$'}total fetched"
  if [ "${'$'}ok" -lt 1 ]; then
    if command -v timeout >/dev/null 2>&1; then
      timeout 25 apt-get update 2>&1 | tail -5 || true
    else
      apt-get update 2>&1 | tail -5 || true
    fi
  fi
}
quro_apt_refresh
""".trimIndent()
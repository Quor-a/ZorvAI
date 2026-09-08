package com.ai.assistance.quro.build

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 端侧工程视图模型：管理 buildproject/src 下的多文件 Java 工程。
 *
 * 功能：
 * · 自动初始化默认工程（com/example/hello/Main.java）
 * · 文件树加载、新建/重命名/删除文件与目录
 * · 当前文件编辑与保存
 * · 调用 BuildEngine 编译整个工程为 classes.dex
 * · 导出 DEX 到系统下载/分享
 */
/**
 * 工程级配置（对应「自己写包名 / 应用名 / 图标 / 签名」）。持久化到 buildproject/project_config.json。
 * signing.useCustom=false 时回退内置 debug.keystore；true 时用工程内 keystorePath 指向的自定义签名。
 */
data class SigningConfig(
    val useCustom: Boolean = false,
    val keystorePath: String? = null,
    val alias: String = "buildaci",
    val storePassword: String = "",
    val keyPassword: String = ""
)

data class ProjectConfig(
    val packageName: String = "com.example.buildapp",
    val appLabel: String = "BuildApp",
    val versionName: String = "1.0.0",
    val iconPath: String? = null,
    val signing: SigningConfig = SigningConfig()
)

class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val projectRoot: File = File(application.filesDir, "buildproject")
    private val srcDir: File = File(projectRoot, "src")
    private val outDir: File = File(projectRoot, "out")

    /** 文件树，按目录优先、名字排序。 */
    val files = mutableStateListOf<ProjectFile>()

    /** 当前选中的文件（非目录）。 */
    var selected by mutableStateOf<ProjectFile?>(null)
        private set

    /** 当前编辑器内容。 */
    var editorText by mutableStateOf("")
        private set

    /** 编译日志。 */
    var log by mutableStateOf("点击「编译工程」把 src 下所有 .java 编成 classes.dex。")
        private set

    /** 是否正在编译。 */
    var isBuilding by mutableStateOf(false)
        private set

    /** 上一次编译成功的 classes.dex 路径。 */
    var dexPath by mutableStateOf<String?>(null)
        private set

    /** 上一次构建成功的 APK 路径。 */
    var apkPath by mutableStateOf<String?>(null)
        private set

    /** 工具链自检结果。 */
    var toolStatus by mutableStateOf<List<BuildEngine.ToolStatus>>(emptyList())
        private set

    /** 工程配置（包名/应用名/版本/图标/签名），持久化于 project_config.json。 */
    var projectConfig by mutableStateOf(ProjectConfig())
        private set

    init {
        ensureDefaultProject()
        loadConfig()
        loadFiles()
        // 工具链自检会首次解包 50MB 资产（ecj/d8/apksigner/android.jar/base.apk），
        // 放 IO 协程，避免主线程同步拷贝触发 StrictMode/ANR 看门狗。
        viewModelScope.launch(Dispatchers.IO) { refreshToolStatus() }
    }

    /** 刷新工具链状态。 */
    fun refreshToolStatus() {
        toolStatus = BuildEngine.detectTools(getApplication())
    }

    // =============================================================================================
    // 工程配置 / 导入（包名 / 应用名 / 图标 / 签名 / 源码与资源导入）
    // =============================================================================================

    private fun saveConfig() {
        try {
            val o = JSONObject()
            o.put("packageName", projectConfig.packageName)
            o.put("appLabel", projectConfig.appLabel)
            o.put("versionName", projectConfig.versionName)
            o.put("iconPath", projectConfig.iconPath)
            val s = JSONObject()
            s.put("useCustom", projectConfig.signing.useCustom)
            s.put("keystorePath", projectConfig.signing.keystorePath)
            s.put("alias", projectConfig.signing.alias)
            s.put("storePassword", projectConfig.signing.storePassword)
            s.put("keyPassword", projectConfig.signing.keyPassword)
            o.put("signing", s)
            File(projectRoot, "project_config.json").writeText(o.toString(2))
        } catch (e: Throwable) {
            log += "\n保存配置失败：${e.message}"
        }
    }

    private fun loadConfig() {
        try {
            val f = File(projectRoot, "project_config.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val s = o.optJSONObject("signing")
            val kp = if (s == null || s.isNull("keystorePath")) null else s.optString("keystorePath")
            projectConfig = ProjectConfig(
                packageName = o.optString("packageName", "com.example.buildapp"),
                appLabel = o.optString("appLabel", "BuildApp"),
                versionName = o.optString("versionName", "1.0.0"),
                iconPath = if (o.isNull("iconPath")) null else o.optString("iconPath"),
                signing = SigningConfig(
                    useCustom = s?.optBoolean("useCustom", false) ?: false,
                    keystorePath = kp,
                    alias = s?.optString("alias", "buildaci") ?: "buildaci",
                    storePassword = s?.optString("storePassword", "") ?: "",
                    keyPassword = s?.optString("keyPassword", "") ?: ""
                )
            )
        } catch (_: Throwable) {
            // 解析失败则用默认配置
        }
    }

    fun setPackageName(v: String) { projectConfig = projectConfig.copy(packageName = v); saveConfig() }
    fun setAppLabel(v: String) { projectConfig = projectConfig.copy(appLabel = v); saveConfig() }
    fun setVersionName(v: String) { projectConfig = projectConfig.copy(versionName = v); saveConfig() }
    fun setUseCustomSigning(v: Boolean) {
        projectConfig = projectConfig.copy(signing = projectConfig.signing.copy(useCustom = v)); saveConfig()
    }
    fun setSigningAlias(v: String) {
        projectConfig = projectConfig.copy(signing = projectConfig.signing.copy(alias = v)); saveConfig()
    }
    fun setStorePassword(v: String) {
        projectConfig = projectConfig.copy(signing = projectConfig.signing.copy(storePassword = v)); saveConfig()
    }
    fun setKeyPassword(v: String) {
        projectConfig = projectConfig.copy(signing = projectConfig.signing.copy(keyPassword = v)); saveConfig()
    }

    /** 当前图标字节（供 UI 预览）；无则用内置默认图标。 */
    fun loadIconBytes(): ByteArray? {
        val p = projectConfig.iconPath ?: return null
        val f = File(projectRoot, p)
        return if (f.exists()) f.readBytes() else null
    }

    fun importIcon(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    val bytes = ins.readBytes()
                    val dst = File(projectRoot, "icon.png")
                    dst.writeBytes(bytes)
                    withContext(Dispatchers.Main) {
                        projectConfig = projectConfig.copy(iconPath = "icon.png")
                        saveConfig()
                        log += "\n已导入图标：${bytes.size} 字节（将用于 APK 桌面图标）"
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { log += "\n导入图标失败：${e.message}" }
            }
        }
    }

    /** 端内生成自定义签名 keystore（PKCS12）。失败（如设备不可达 BC）时提示改用导入。 */
    fun generateKeystore(alias: String, storePass: String, keyPass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(projectRoot, "signing.p12")
                BuildEngine.generateKeystore(f, alias, storePass, keyPass)
                withContext(Dispatchers.Main) {
                    projectConfig = projectConfig.copy(
                        signing = SigningConfig(
                            useCustom = true, keystorePath = "signing.p12",
                            alias = alias, storePassword = storePass, keyPassword = keyPass
                        )
                    )
                    saveConfig()
                    log += "\n已生成自定义签名 keystore：${f.absolutePath}（别名 $alias）"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    log += "\n生成签名失败：${e.message}（可改用「导入 keystore」）"
                }
            }
        }
    }

    fun importKeystore(context: Context, uri: android.net.Uri, alias: String, storePass: String, keyPass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = fileNameFromUri(context, uri)
                val dst = File(projectRoot, name)
                context.contentResolver.openInputStream(uri)?.use { ins -> dst.writeBytes(ins.readBytes()) }
                withContext(Dispatchers.Main) {
                    projectConfig = projectConfig.copy(
                        signing = SigningConfig(
                            useCustom = true, keystorePath = name,
                            alias = alias, storePassword = storePass, keyPassword = keyPass
                        )
                    )
                    saveConfig()
                    log += "\n已导入 keystore：$name（别名 $alias）"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { log += "\n导入 keystore 失败：${e.message}" }
            }
        }
    }

    /**
     * 导入文件到工程：
     *  · .java / .kt → 按源码里声明的 package 落位到 src/<pkgpath>/<Class>.java（参与编译）
     *  · 其它 → 放进工程 assets/，构建时注入 APK 的 assets/（用户代码经 AssetManager 读取）
     */
    fun importFile(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = fileNameFromUri(context, uri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                if (name.endsWith(".java", ignoreCase = true) || name.endsWith(".kt", ignoreCase = true)) {
                    val text = String(bytes, Charsets.UTF_8)
                    val pkg = Regex("""package\s+([\w.]+)\s*;""").find(text)?.groupValues?.get(1)
                    val cls = name.substringBeforeLast('.').ifBlank { "Imported" }
                    val relDir = pkg?.replace('.', '/') ?: ""
                    val destDir = File(srcDir, relDir).apply { mkdirs() }
                    val dest = File(destDir, "$cls.java")
                    dest.writeText(text)
                    withContext(Dispatchers.Main) {
                        loadFiles()
                        log += "\n已导入源码：${dest.relativeTo(srcDir).path}（按 package 落位，参与编译）"
                    }
                } else {
                    val adir = File(projectRoot, "assets").apply { mkdirs() }
                    val dest = File(adir, name)
                    dest.writeBytes(bytes)
                    withContext(Dispatchers.Main) {
                        log += "\n已导入资源：${dest.name}（将打包进 APK 的 assets/）"
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { log += "\n导入文件失败：${e.message}" }
            }
        }
    }

    private fun fileNameFromUri(context: Context, uri: android.net.Uri): String {
        var name = "import_${System.currentTimeMillis()}"
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) name = c.getString(i)
                    }
                }
            } else if (uri.scheme == "file") {
                name = uri.lastPathSegment ?: name
            }
        } catch (_: Throwable) {
        }
        return name
    }

    /** 构建完整 APK（按工程配置改写包名/应用名/图标/签名，注入 classes.dex 并签名）。 */
    fun buildApk() {
        val dex = dexPath ?: run {
            log += "\n请先编译工程生成 classes.dex，再构建 APK。"
            return
        }
        isBuilding = true
        log = "构建 APK 中…\n"
        viewModelScope.launch {
            val cfg = projectConfig
            val iconBytes = cfg.iconPath?.let { File(projectRoot, it).takeIf { it.exists() }?.readBytes() }
            val ksFile = if (cfg.signing.useCustom && cfg.signing.keystorePath != null)
                File(projectRoot, cfg.signing.keystorePath!!) else null
            val buildConfig = BuildEngine.BuildConfig(
                packageName = cfg.packageName,
                appLabel = cfg.appLabel,
                versionName = cfg.versionName,
                iconBytes = iconBytes,
                keystore = if (cfg.signing.useCustom && ksFile?.exists() == true) ksFile else null,
                keyAlias = if (cfg.signing.useCustom) cfg.signing.alias else "androiddebugkey",
                storePassword = if (cfg.signing.useCustom) cfg.signing.storePassword else "android",
                keyPassword = if (cfg.signing.useCustom) cfg.signing.keyPassword else "android",
                assetsDir = File(projectRoot, "assets").takeIf { it.exists() }
            )
            val out = File(projectRoot, "app-${timestamp()}.apk")
            val r = withContext(Dispatchers.IO) {
                BuildEngine.assembleApk(getApplication(), dex, out, buildConfig)
            }
            isBuilding = false
            apkPath = r.apkPath
            log = r.log
        }
    }

    /** 导出 APK 到系统分享/下载。 */
    fun exportApk(context: Context, uri: android.net.Uri) {
        val path = apkPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    File(path).inputStream().use { it.copyTo(os) }
                }
                withContext(Dispatchers.Main) { log += "\n已导出 APK 到：$uri" }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { log += "\n导出失败：${e.message}" }
            }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())

    /** 保证默认工程存在：一个带 package 的 Main.java。 */
    private fun ensureDefaultProject() {
        srcDir.mkdirs()
        // 旧版 BuildAci 内置的 com/example/buildapp 多文件示例存在编译错误
        // （如 CalculatorActivity.java:81 的 grid.setAlignment(GridLayout.CENTER) ——
        // setAlignment 是 GridLayout.LayoutParams 的方法，不属于 GridLayout），
        // 会导致「打开即构建」失败。该示例非用户所写（升级安装残留在 files/buildproject），
        // 检测到且尚未重置过时，清空并写入一个干净、可直接编译运行的默认工程。
        val staleDemo = File(srcDir, "com/example/buildapp")
        val resetSentinel = File(srcDir, ".demo_reset_v1520")
        if (staleDemo.exists() && !resetSentinel.exists()) {
            srcDir.listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
        // 默认工程：com/example/hello/Main.java（宿主 MainActivity 经反射调用其 run() 显示结果）
        val pkgDir = File(srcDir, "com/example/hello")
        pkgDir.mkdirs()
        val main = File(pkgDir, "Main.java")
        if (!main.exists()) {
            main.writeText(
                """package com.example.hello;

public class Main {
    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello from on-device compiled DEX!").append("\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("line ").append(i).append("\n");
        }
        return sb.toString();
    }
}
""".trimIndent()
            )
        }
        // 重置哨兵：确保只在首次打开时清理一次旧示例，不误删用户后续工程文件
        runCatching { resetSentinel.createNewFile() }
    }

    /** 重新扫描 src 目录，生成展平的文件树列表（目录也作为节点插入）。 */
    fun loadFiles() {
        files.clear()
        if (!srcDir.exists()) srcDir.mkdirs()
        val list = scanDir(srcDir, "", 0)
        files.addAll(list)
        if (selected == null || files.none { it.relativePath == selected?.relativePath && !it.isDirectory }) {
            selectFirstJavaFile()
        }
    }

    private fun scanDir(dir: File, relPrefix: String, depth: Int): List<ProjectFile> {
        val result = mutableListOf<ProjectFile>()
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return result
        for (child in children) {
            val rel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
            result.add(ProjectFile(rel, child.name, child.isDirectory, depth))
            if (child.isDirectory) {
                result.addAll(scanDir(child, rel, depth + 1))
            }
        }
        return result
    }

    private fun selectFirstJavaFile() {
        val first = files.firstOrNull { it.isJavaFile }
        if (first != null) select(first)
    }

    /** 选中文件并加载内容到编辑器。 */
    fun select(file: ProjectFile) {
        if (file.isDirectory) return
        // 先保存当前内容到旧文件
        saveCurrent()
        selected = file
        val f = File(srcDir, file.relativePath)
        editorText = if (f.exists()) f.readText() else ""
    }

    /** 编辑器内容变化。 */
    fun onEditorTextChanged(text: String) {
        editorText = text
    }

    /** 把编辑器内容写回磁盘。 */
    fun saveCurrent() {
        val sel = selected ?: return
        val f = File(srcDir, sel.relativePath)
        try {
            f.writeText(editorText)
        } catch (e: Throwable) {
            log += "\n保存 ${sel.name} 失败：${e.message}"
        }
    }

    /** 在指定相对目录下新建文件。 */
    fun createFile(parentRel: String, fileName: String): Boolean {
        val parent = if (parentRel.isEmpty()) srcDir else File(srcDir, parentRel)
        if (!parent.exists() || !parent.isDirectory) return false
        val f = File(parent, fileName.trim())
        return try {
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.createNewFile()
                if (fileName.endsWith(".java", ignoreCase = true)) {
                    val pkg = packageFromPath(File(srcDir, parentRel))
                    val cls = fileName.removeSuffix(".java").removeSuffix(".JAVA")
                    f.writeText(
                        if (pkg.isNotBlank()) "package $pkg;\n\npublic class $cls {\n}\n"
                        else "public class $cls {\n}\n"
                    )
                }
            }
            loadFiles()
            val rel = if (parentRel.isEmpty()) fileName.trim() else "$parentRel/${fileName.trim()}"
            files.find { it.relativePath == rel && !it.isDirectory }?.let { select(it) }
            true
        } catch (e: Throwable) {
            log += "\n新建文件失败：${e.message}"
            false
        }
    }

    /** 在指定相对目录下新建目录。 */
    fun createFolder(parentRel: String, folderName: String): Boolean {
        val parent = if (parentRel.isEmpty()) srcDir else File(srcDir, parentRel)
        val f = File(parent, folderName.trim())
        return try {
            f.mkdirs()
            loadFiles()
            true
        } catch (e: Throwable) {
            log += "\n新建目录失败：${e.message}"
            false
        }
    }

    /** 重命名文件或目录。 */
    fun rename(file: ProjectFile, newName: String): Boolean {
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\')) return false
        val f = File(srcDir, file.relativePath)
        val parent = f.parentFile ?: return false
        val dest = File(parent, newName.trim())
        return try {
            if (dest.exists()) return false
            f.renameTo(dest)
            loadFiles()
            true
        } catch (e: Throwable) {
            log += "\n重命名失败：${e.message}"
            false
        }
    }

    /** 删除文件或空目录。 */
    fun delete(file: ProjectFile): Boolean {
        val f = File(srcDir, file.relativePath)
        return try {
            if (file.isDirectory) {
                f.deleteRecursively()
            } else {
                f.delete()
            }
            if (selected?.relativePath == file.relativePath) {
                selected = null
                editorText = ""
            }
            loadFiles()
            true
        } catch (e: Throwable) {
            log += "\n删除失败：${e.message}"
            false
        }
    }

    /** 编译整个工程。 */
    fun buildProject() {
        saveCurrent()
        isBuilding = true
        dexPath = null
        log = "编译中…\n"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                BuildEngine.compileProject(getApplication(), srcDir, outDir)
            }
            isBuilding = false
            dexPath = r.dexPath
            log = r.log
        }
    }

    /** 导出 DEX。 */
    fun exportDex(context: Context, uri: android.net.Uri) {
        val path = dexPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    File(path).inputStream().use { it.copyTo(os) }
                }
                withContext(Dispatchers.Main) {
                    log += "\n已导出 DEX 到：$uri"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    log += "\n导出失败：${e.message}"
                }
            }
        }
    }

    /** 根据相对目录路径反推 package（如 com/example/hello → com.example.hello）。 */
    private fun packageFromPath(dirUnderSrc: File): String {
        val rel = dirUnderSrc.relativeToOrNull(srcDir)?.path?.replace('/', '.')?.replace('\\', '.') ?: ""
        return rel.trim('.')
    }
}

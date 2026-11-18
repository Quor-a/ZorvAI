package com.ai.assistance.quro.core.pet

import android.content.Context
import com.ai.assistance.quro.genui.aiapp.ui.PetPrefs
import com.ai.assistance.quro.genui.sdk.components.PetSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌宠悬浮窗与语音球宠物模式共用的形象描述。
 *
 * 形象优先级只有一个：安装了宠物包就用包里的雪碧图，没装才回退矢量宠物。
 * 桌宠与语音球都从这里取形象，避免一处换了包、另一处还画着旧形象。
 */
data class PetAppearance(
    val spec: PetSpec,
    val pack: PetPack.LoadedPack?,
    val bubbleEnabled: Boolean,
    val scale: Float,
)

/**
 * 形象解析与预热缓存。
 *
 * 内置包落盘、目录扫描、pet.json 解析都是磁盘 IO，冷启动时放在主线程会
 * 拖住前台服务的 5 秒上限。这里把 IO 收敛到 [load]（后台线程），并保留一份
 * 进程内缓存供服务挂窗时同步命中；[peek] 只做 SharedPreferences 字符串比较，
 * 可以安全地在主线程调用。
 */
object PetAppearanceResolver {

    private const val PREFS = "pet_prefs"
    private const val KEY_PACK = "pet_pack_dir"

    @Volatile private var cacheKey: String? = null
    @Volatile private var cached: PetAppearance? = null

    /** 轻量指纹：不碰素材文件，只比较设置项。 */
    fun key(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return listOf(
            sp.getString("pet_json", "") ?: "",
            if (sp.contains(KEY_PACK)) sp.getString(KEY_PACK, "") ?: "" else "<unset>",
            sp.getFloat("pet_scale", 1.0f).toString(),
            sp.getBoolean("bubble_enabled", true).toString(),
        ).joinToString("|")
    }

    /** 命中内存缓存时同步返回，供悬浮窗挂载的快速路径使用。 */
    fun peek(ctx: Context): PetAppearance? = cached?.takeIf { cacheKey == key(ctx) }

    /** 设置变更、导入或覆盖安装宠物包后调用，下次 [load] 会重新扫描。 */
    fun invalidate() {
        cacheKey = null
        cached = null
    }

    /** IO 解析：内置包落盘 → 扫描目录 → 解析选中的包。结果写入缓存。 */
    suspend fun load(ctx: Context): PetAppearance = withContext(Dispatchers.IO) {
        val requested = key(ctx)
        cached?.takeIf { cacheKey == requested }?.let { return@withContext it }

        val settings = PetPrefs.load(ctx)
        val builtin = PetPackAssets.ensure(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 与桌宠服务保持同一个「首次未选包则启用内置包」的口径。
        val chosen = if (sp.contains(KEY_PACK)) {
            sp.getString(KEY_PACK, null)?.takeIf { it.isNotEmpty() }
        } else {
            builtin?.also { sp.edit().putString(KEY_PACK, it).apply() }
        }
        val pack = chosen?.let { dir ->
            runCatching {
                val (available, _) = PetPack.scan(ctx)
                available.firstOrNull { it.dirName == dir }
            }.getOrNull()
        }
        val result = PetAppearance(
            spec = settings.current,
            pack = pack,
            bubbleEnabled = settings.bubbleEnabled,
            scale = settings.petScale.coerceIn(0.5f, 2.0f),
        )
        cached = result
        // 首次落盘会补写 pet_pack_dir，指纹随之变化，这里按最终设置重算。
        cacheKey = key(ctx)
        result
    }
}

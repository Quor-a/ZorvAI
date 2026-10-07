package com.ai.assistance.quro.core.cluster

import android.content.Context

/**
 * ★ 模型来源 —— 把「Zorv AI 已经配置好的模型」聚合成一份列表 ★
 *
 * 现状：QuroModelConfig 是 SharedPreferences 里的一条"当前生效配置"，
 *       只有 load()/save()，没有 listAll()。所以"已配置模型"要从三处聚合：
 *
 *   1. 当前云端配置      → QuroModelConfigRepository.load()
 *   2. 自定义厂商列表    → QuroCustomProviderRepository（多个 provider）
 *   3. 离线/端侧模型登记 → QuroLocalModelRepository（MNN / llama.cpp）
 *
 * 下面这三处读取被集中在【适配点】注释处。
 * 如果你的仓库里方法名与此处不一致，只需要改这三处，其余代码全部不用动。
 */
interface ClusterModelSource {
    /** 列出宿主当前所有已配置且可用的模型 */
    fun listModels(): List<ModelProfile>
    /** 按 id 取一条 */
    fun get(id: String): ModelProfile?
}

class DefaultClusterModelSource(private val context: Context) : ClusterModelSource {

    private val cache = LinkedHashMap<String, ModelProfile>()
    @Volatile private var lastLoad = 0L
    private val ttlMs = 30_000L

    override fun listModels(): List<ModelProfile> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - lastLoad < ttlMs) return cache.values.toList()
        cache.clear()
        collect().forEach { cache[it.id] = it }
        lastLoad = now
        return cache.values.toList()
    }

    override fun get(id: String): ModelProfile? =
        cache[id] ?: listModels().firstOrNull { it.id == id }

    fun invalidate() { cache.clear(); lastLoad = 0L }

    private fun collect(): List<ModelProfile> {
        val out = ArrayList<ModelProfile>()

        // ——— 适配点 1：当前云端配置 ———
        // QuroModelConfigRepository(context).load() 返回 QuroModelConfig
        runCatching {
            val repo = com.ai.assistance.quro.core.model.QuroModelConfigRepository(context)
            val cfg = repo.load()
            val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
            if (isLocal) {
                out += ModelProfile(
                    id = "local:current",
                    displayName = cfg.localModelPath.substringAfterLast('/').ifBlank { "本地模型" },
                    kind = ModelKind.LOCAL,
                    localModelId = cfg.localModelPath,
                    supportsTools = cfg.localEnableTools,
                    supportsJsonMode = false,
                    maxConcurrency = 1,
                    contextWindow = cfg.contextWindow,
                    available = cfg.localModelPath.isNotBlank()
                )
            } else {
                out += ModelProfile(
                    id = "cloud:current",
                    displayName = cfg.model.ifBlank { cfg.provider },
                    kind = ModelKind.CLOUD,
                    provider = cfg.provider,

                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,                    supportsTools = cfg.enableTools,
                    supportsJsonMode = true,
                    maxConcurrency = 2,
                    contextWindow = cfg.contextWindow,
                    available = cfg.apiKey.isNotBlank() || cfg.baseUrl.isNotBlank()
                )
            }
        }

        // ——— 适配点 2：自定义厂商（多条） ———
        // QuroCustomProviderRepository.loadAll() 已存在，直接调用（反射兜底已无需）
        runCatching {
            val repo = com.ai.assistance.quro.core.model.QuroCustomProviderRepository(context)
            repo.loadAll().forEach { p ->
                val name = p.name
                val base = p.baseUrl
                val model = p.defaultModel
                if (name.isNotBlank() || base.isNotBlank()) {
                    out += ModelProfile(
                        id = "cloud:${name.ifBlank { base }}",
                        displayName = model.ifBlank { name },
                        kind = ModelKind.CLOUD,
                        provider = name,
                        // 把这一条厂商自己的 baseUrl 带进 profile，
                        // 否则角色绑了它也白搭——实际请求仍会打到全局那一个通道。
                        baseUrl = base,                        supportsTools = true,
                        maxConcurrency = 2,
                        available = base.isNotBlank()
                    )
                }
            }
        }

        // ——— 适配点 3：离线模型登记 ———
        // QuroLocalModelRepository.loadAll() 已存在，直接调用（反射兜底已无需）
        runCatching {
            val repo = com.ai.assistance.quro.core.model.QuroLocalModelRepository(context)
            repo.loadAll().forEach { m ->
                val id = m.id
                val name = m.name
                if (name.isNotBlank()) {
                    out += ModelProfile(
                        id = "local:${id.ifBlank { name }}",
                        displayName = name,
                        kind = ModelKind.LOCAL,
                        localModelId = id.ifBlank { name },
                        supportsTools = false,
                        supportsJsonMode = false,
                        maxConcurrency = 1,          // 端侧串行，避免 OOM
                        available = true
                    )
                }
            }
        }

        // 兜底：一个都没解析出来时，至少给一条当前配置，保证集群能跑
        if (out.isEmpty()) {
            out += ModelProfile(
                id = "cloud:current", displayName = "当前模型",
                kind = ModelKind.CLOUD, available = true
            )
        }
        return out.distinctBy { it.id }
    }

    /** 反射读取 POJO 上可能的字段名，避免硬绑定 */
    private fun readString(obj: Any, vararg names: String): String {
        val cls = obj.javaClass
        names.forEach { n ->
            runCatching { cls.getDeclaredField(n).apply { isAccessible = true }.get(obj) }
                .getOrNull()?.let { return it.toString() }
            runCatching { cls.getMethod("get${n.replaceFirstChar { it.uppercase() }}").invoke(obj) }
                .getOrNull()?.let { return it.toString() }
        }
        return ""
    }
}

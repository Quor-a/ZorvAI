package com.ai.assistance.quro.core.tools

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * WebView本地库资源解析器
 * 将CDN URL映射到本地assets资源，防止CDN不可用时白屏
 */
class AssetLibResolver(private val context: Context) {
    
    // CDN域名到本地资源的映射
    private val cdnMappings = mutableMapOf<String, String>()
    
    // 支持的CDN域名列表
    private val supportedCdnDomains = listOf(
        "unpkg.com",
        "cdnjs.cloudflare.com",
        "cdn.jsdelivr.net",
        "cdn.bootcss.com",
        "cdn.bootcdn.net",
        "cdnjs.cn",
        "cdn.staticfile.org",
        "cdn.jsdelivr.net",
        "fastly.net",
        "cloudflare.com",
        "jsdelivr.net",
        "githack.com",
        "rawgit.com",
        "rawcdn.githack.com"
    )
    
    init {
        // 初始化CDN映射
        initCdnMappings()
    }
    
    /**
     * 初始化CDN到本地资源的映射
     */
    private fun initCdnMappings() {
        // Three.js
        addMapping("three.min.js", 
            "three/build/three.min.js",
            "three@r128/build/three.min.js",
            "three@0.128.0/build/three.min.js",
            "three@latest/build/three.min.js")
        
        // Marked (Markdown解析)
        addMapping("marked.min.js",
            "marked/marked.min.js",
            "marked@4.0.10/marked.min.js",
            "marked@latest/marked.min.js")
        
        // Highlight.js
        addMapping("highlight.min.js",
            "highlight.js/highlight.min.js",
            "highlight.js@11.6.0/highlight.min.js",
            "highlight.js@latest/highlight.min.js")
        
        // Mermaid (图表)
        addMapping("mermaid.min.js",
            "mermaid/dist/mermaid.min.js",
            "mermaid@9.1.1/dist/mermaid.min.js",
            "mermaid@latest/dist/mermaid.min.js")
        
        // ECharts (图表)
        addMapping("echarts.min.js",
            "echarts/dist/echarts.min.js",
            "echarts@5.3.3/dist/echarts.min.js",
            "echarts@latest/dist/echarts.min.js")
        
        // Chart.js
        addMapping("chart.min.js",
            "chart.js/dist/chart.min.js",
            "chart.js@3.8.0/dist/chart.min.js",
            "chart.js@latest/dist/chart.min.js")
        
        // KaTeX (数学公式)
        addMapping("katex.min.js",
            "katex/dist/katex.min.js",
            "katex@0.15.3/dist/katex.min.js",
            "katex@latest/dist/katex.min.js")
        
        addMapping("katex.min.css",
            "katex/dist/katex.min.css",
            "katex@0.15.3/dist/katex.min.css",
            "katex@latest/dist/katex.min.css")
        
        // D3.js
        addMapping("d3.min.js",
            "d3/dist/d3.min.js",
            "d3@7.4.4/dist/d3.min.js",
            "d3@latest/dist/d3.min.js")
        
        // Plotly.js
        addMapping("plotly.min.js",
            "plotly.js-dist-min/plotly.min.js",
            "plotly.js-dist-min@2.12.1/plotly.min.js",
            "plotly.js-dist-min@latest/plotly.min.js")
        
        // Highlight.js 主题
        addMapping("github-dark.css",
            "highlight.js/styles/github-dark.css",
            "highlight.js@11.6.0/styles/github-dark.css",
            "highlight.js@latest/styles/github-dark.css")
        
        addMapping("github.css",
            "highlight.js/styles/github.css",
            "highlight.js@11.6.0/styles/github.css",
            "highlight.js@latest/styles/github.css")
    }
    
    /**
     * 添加资源映射
     * @param localFileName 本地assets中的文件名
     * @param cdnPaths CDN路径列表（按优先级）
     */
    private fun addMapping(localFileName: String, vararg cdnPaths: String) {
        for (cdnPath in cdnPaths) {
            cdnMappings[cdnPath] = localFileName
        }
    }
    
    /**
     * 处理WebView资源请求
     * @param request WebResourceRequest
     * @return WebResourceResponse或null（使用默认加载）
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        
        // 检查是否是支持的CDN请求
        if (!isCdnRequest(url)) {
            return null
        }
        
        // 提取库名称
        val libraryName = extractLibraryName(url) ?: return null
        
        // 查找对应的本地资源
        val localAsset = findLocalAsset(libraryName) ?: return null
        
        // 加载本地资源
        return loadLocalAsset(localAsset, url)
    }
    
    /**
     * 检查是否是CDN请求
     */
    private fun isCdnRequest(url: String): Boolean {
        return supportedCdnDomains.any { domain ->
            url.contains(domain)
        }
    }
    
    /**
     * 从URL中提取库名称
     */
    private fun extractLibraryName(url: String): String? {
        // 尝试从URL中提取库名称
        // 例如：https://unpkg.com/three@r128/build/three.min.js -> three.min.js
        // 或者：https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js -> three.min.js
        
        val patterns = listOf(
            // 匹配文件名
            Regex("""[/\\]([^/\\]+\.(js|css))$"""),
            // 匹配库名和版本
            Regex("""[/\\]([^/\\]+)/build/([^/\\]+\.(js|css))$"""),
            Regex("""[/\\]([^/\\]+)/dist/([^/\\]+\.(js|css))$"""),
            // 匹配cdnjs格式
            Regex("""[/\\]libs/([^/\\]+)/[^/\\]+/([^/\\]+\.(js|css))$""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                // 返回最后一个捕获组（文件名）
                return match.groupValues.last()
            }
        }
        
        // 尝试简单提取文件名
        val parts = url.split("/")
        val lastPart = parts.lastOrNull()
        if (lastPart != null && (lastPart.endsWith(".js") || lastPart.endsWith(".css"))) {
            return lastPart
        }
        
        return null
    }
    
    /**
     * 查找本地资源
     */
    private fun findLocalAsset(libraryName: String): String? {
        return cdnMappings.values.find { it == libraryName }
    }
    
    /**
     * 加载本地资源
     */
    private fun loadLocalAsset(assetPath: String, originalUrl: String): WebResourceResponse? {
        return try {
            // 构建assets路径
            val assetFilePath = "libs/$assetPath"
            
            // 读取本地资源
            val inputStream = context.assets.open(assetPath)
            
            // 确定MIME类型
            val mimeType = when {
                assetPath.endsWith(".js") -> "application/javascript"
                assetPath.endsWith(".css") -> "text/css"
                assetPath.endsWith(".json") -> "application/json"
                assetPath.endsWith(".html") -> "text/html"
                else -> "text/plain"
            }
            
            // 创建响应
            val responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "max-age=31536000"
            )
            
            WebResourceResponse(
                mimeType,
                "UTF-8",
                200,
                "OK",
                responseHeaders,
                inputStream
            )
        } catch (e: IOException) {
            // 本地资源不存在，使用默认加载
            null
        }
    }
    
    /**
     * 生成CDN错误恢复脚本
     * 用于注入到HTML内容中
     */
    fun generateFallbackScript(): String {
        return """
        <script>
        // CDN错误恢复脚本 - 自动加载本地库
        (function() {
            // 本地库映射
            const LOCAL_LIBS = {
                'three': 'libs/three.min.js',
                'marked': 'libs/marked.min.js',
                'hljs': 'libs/highlight.min.js',
                'mermaid': 'libs/mermaid.min.js',
                'echarts': 'libs/echarts.min.js',
                'Chart': 'libs/chart.min.js',
                'katex': 'libs/katex.min.js',
                'd3': 'libs/d3.min.js',
                'Plotly': 'libs/plotly.min.js'
            };
            
            // CDN URL到本地库的映射
            const CDN_TO_LOCAL = {
                'unpkg.com': {
                    'three': 'three.min.js',
                    'marked': 'marked.min.js',
                    'highlight.js': 'highlight.min.js',
                    'mermaid': 'mermaid.min.js',
                    'echarts': 'echarts.min.js',
                    'chart.js': 'chart.min.js',
                    'katex': 'katex.min.js',
                    'd3': 'd3.min.js',
                    'plotly.js-dist-min': 'plotly.min.js'
                },
                'cdnjs.cloudflare.com': {
                    'three.js': 'three.min.js',
                    'marked': 'marked.min.js',
                    'highlight.js': 'highlight.min.js',
                    'mermaid': 'mermaid.min.js',
                    'echarts': 'echarts.min.js',
                    'chart.js': 'chart.min.js',
                    'katex': 'katex.min.js',
                    'd3': 'd3.min.js',
                    'plotly.js': 'plotly.min.js'
                },
                'cdn.jsdelivr.net': {
                    'three': 'three.min.js',
                    'marked': 'marked.min.js',
                    'highlight.js': 'highlight.min.js',
                    'mermaid': 'mermaid.min.js',
                    'echarts': 'echarts.min.js',
                    'chart.js': 'chart.min.js',
                    'katex': 'katex.min.js',
                    'd3': 'd3.min.js',
                    'plotly.js-dist-min': 'plotly.min.js'
                }
            };
            
            // 拦截资源加载失败
            const originalFetch = window.fetch;
            window.fetch = function(url, options) {
                return originalFetch.call(this, url, options).catch(function(error) {
                    console.log('资源加载失败，尝试本地回退:', url);
                    
                    // 尝试加载本地库
                    for (const [domain, libs] of Object.entries(CDN_TO_LOCAL)) {
                        if (url.includes(domain)) {
                            for (const [libName, localFile] of Object.entries(libs)) {
                                if (url.includes(libName)) {
                                    const localUrl = 'file:///android_asset/libs/' + localFile;
                                    console.log('加载本地库:', localUrl);
                                    return originalFetch.call(this, localUrl, options);
                                }
                            }
                        }
                    }
                    
                    throw error;
                });
            };
            
            // 拦截script标签加载
            const observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.tagName === 'SCRIPT' && node.src) {
                            const script = node;
                            const originalSrc = script.src;
                            
                            script.onerror = function() {
                                console.log('Script加载失败，尝试本地回退:', originalSrc);
                                
                                for (const [domain, libs] of Object.entries(CDN_TO_LOCAL)) {
                                    if (originalSrc.includes(domain)) {
                                        for (const [libName, localFile] of Object.entries(libs)) {
                                            if (originalSrc.includes(libName)) {
                                                const localUrl = 'file:///android_asset/libs/' + localFile;
                                                console.log('加载本地Script:', localUrl);
                                                
                                                const newScript = document.createElement('script');
                                                newScript.src = localUrl;
                                                newScript.onerror = function() {
                                                    console.error('本地库也加载失败:', localUrl);
                                                };
                                                document.head.appendChild(newScript);
                                                return;
                                            }
                                        }
                                    }
                                }
                            };
                        }
                    });
                });
            });
            
            observer.observe(document.head, { childList: true, subtree: true });
            observer.observe(document.body, { childList: true, subtree: true });
            
            // 预加载关键库
            function preloadCriticalLibs() {
                for (const [libName, localPath] of Object.entries(LOCAL_LIBS)) {
                    const link = document.createElement('link');
                    link.rel = 'preload';
                    link.as = localPath.endsWith('.js') ? 'script' : 'style';
                    link.href = 'file:///android_asset/' + localPath;
                    document.head.appendChild(link);
                }
            }
            
            // 页面加载完成后预加载
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', preloadCriticalLibs);
            } else {
                preloadCriticalLibs();
            }
            
            console.log('CDN错误恢复脚本已加载');
        })();
        </script>
        """.trimIndent()
    }
    
    /**
     * 注入脚本到HTML内容
     * @param html 原始HTML内容
     * @return 注入脚本后的HTML
     */
    fun injectFallbackScript(html: String): String {
        val script = generateFallbackScript()
        
        // 在<head>标签后注入
        if (html.contains("<head>")) {
            return html.replace("<head>", "<head>$script")
        }
        
        // 在<body>标签前注入
        if (html.contains("<body>")) {
            return html.replace("<body>", "$script<body>")
        }
        
        // 在HTML开始后注入
        return html.replace("<html>", "<html>$script")
    }
    
    /**
     * 检查本地库是否存在
     */
    fun hasLocalLibrary(libraryName: String): Boolean {
        return try {
            context.assets.open("libs/$libraryName").use { true }
        } catch (e: IOException) {
            false
        }
    }
    
    /**
     * 获取所有可用的本地库
     */
    fun getAvailableLocalLibraries(): List<String> {
        return try {
            context.assets.list("libs")?.toList() ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }
    
    /**
     * 获取库的本地路径
     */
    fun getLocalLibraryPath(libraryName: String): String? {
        return if (hasLocalLibrary(libraryName)) {
            "file:///android_asset/libs/$libraryName"
        } else {
            null
        }
    }
}
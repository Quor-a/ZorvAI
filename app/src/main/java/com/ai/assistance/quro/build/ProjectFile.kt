package com.ai.assistance.quro.build

/**
 * 项目中的文件或目录节点。
 *
 * @param relativePath 相对于 src 根目录的路径（如 "com/example/hello/Main.java"）
 * @param name 显示名
 * @param isDirectory 是否为目录
 * @param depth 在文件树中的缩进层级，仅用于 UI 展示
 */
data class ProjectFile(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int = 0
) {
    val isJavaFile: Boolean get() = !isDirectory && name.endsWith(".java", ignoreCase = true)
}

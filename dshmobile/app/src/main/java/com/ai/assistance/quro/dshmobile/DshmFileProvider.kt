package com.ai.assistance.quro.dshmobile

import androidx.core.content.FileProvider

/**
 * 库模块专用 FileProvider 子类。
 *
 * 为什么需要子类：manifest 合并器以 `android:name`（类名）为元素身份键。宿主 app 自己声明了
 * `androidx.core.content.FileProvider`（authority `com.ai.assistance.quro.fileprovider`），
 * 本模块若沿用同一个类名，两个 provider 会被合并成同一元素，authorities / FILE_PROVIDER_PATHS
 * 必然冲突（host value vs dshmobile value 二选一，宿主赢则本模块文件分享全灭）。
 * 换成子类名后两者是独立元素，各自 authority 与路径映射并存：
 *   - 宿主：androidx.core.content.FileProvider / com.ai.assistance.quro.fileprovider / @xml/file_paths
 *   - 本模块：.DshmFileProvider / ${applicationId}.dshm_fileprovider / @xml/dshm_file_paths
 *
 * 调用侧无需改动：FileProvider.getUriForFile() 按 authority 字符串寻址，不认类名。
 */
class DshmFileProvider : FileProvider()

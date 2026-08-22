# Release APK 安装失败诊断报告

## 问题描述
- Debug 构建能正常安装
- Release 构建的安装包显示"安装包损坏"或"安装包异常"

## 诊断发现的问题

### 1. 签名版本配置问题（关键）
**当前配置**：只启用 v2 签名，禁用 v1 和 v3
```kotlin
enableV1Signing = false
enableV2Signing = true  
enableV3Signing = false
```

**问题**：
- v1 签名（JAR 签名）提供向后兼容性
- 禁用 v1 可能导致旧设备或某些安装器拒绝安装
- v3 签名提供密钥轮换支持，禁用可能影响升级

**建议**：启用所有签名版本
```kotlin
enableV1Signing = true
enableV2Signing = true
enableV3Signing = true
```

### 2. ApplicationId 不一致问题（已修复）
**发现**：applicationId 被改为 `com.zorvai`，而原始应为 `com.ai.assistance.quro`

**影响**：
- 签名验证基于包名
- 如果之前安装过 `com.ai.assistance.quro` 的 debug 版本，再安装 `com.zorvai` 的 release 版本会被视为全新应用
- 但用户报告的是同一应用的 debug/release 安装问题，说明 applicationId 不一致不是直接原因

**已修复**：已恢复为 `com.ai.assistance.quro`

### 3. Keystore 文件不一致问题（潜在风险）
**发现**：存在三个不同的 keystore 文件：
1. `zorvai-release.keystore`（build.gradle.kts 使用）
2. `zorvai_release.jks`（keystore.properties 指向）
3. `zorvai-release-p12-v1cert.p12`（keystore.properties.backup 指向）

**风险**：
- 如果之前使用不同的 keystore 签名，会导致签名不匹配
- 签名不匹配会导致无法覆盖安装（显示"签名不一致"错误）

### 4. 包排除配置问题（可能原因）
**当前配置**：
```kotlin
excludes += "**/version-control-info*"
resources.excludes += "kotlin-tooling-metadata.json"
```

**问题**：
- 某些 Android 安装器可能依赖这些元数据文件
- 缺少这些文件可能导致安装验证失败

### 5. 构建环境问题（当前状态）
**当前构建错误**：
- `Unresolved reference 'R'`：R 类无法生成
- `Unresolved reference 'BuildConfig'`：BuildConfig 类无法生成
- 原生库编译失败：llama.cpp 目标文件缺失

**可能原因**：
- 资源文件问题
- 构建缓存损坏
- 原生依赖未正确配置

## 解决方案

### 立即修复（签名配置）
1. **启用所有签名版本**：修改 `app/build.gradle.kts` 中的签名配置
2. **统一 keystore 文件**：确保使用同一个 keystore 文件
3. **移除不必要的包排除**：移除 `version-control-info` 和 `kotlin-tooling-metadata.json` 的排除

### 构建修复
1. **清理构建缓存**：
   ```bash
   ./gradlew clean
   # 如果失败，手动删除 .gradle/ 和 build/ 目录
   ```

2. **修复原生依赖**：
   - 检查 `llm/llama` 和 `llm/mnn` 模块的构建配置
   - 确保所有依赖的源代码和预编译库都存在

3. **验证构建配置**：
   - 确保 `namespace` 和 `applicationId` 一致
   - 确保 `buildFeatures.buildConfig = true` 生效

### 测试步骤
1. **清理并重新构建**：
   ```bash
   ./gradlew clean assembleFullRelease
   ```

2. **验证 APK 签名**：
   ```bash
   apkanalyzer apk verify app/build/outputs/apk/full/release/app-full-release.apk
   ```

3. **测试安装**：
   - 先卸载所有旧版本（debug 和 release）
   - 安装新的 release APK
   - 检查是否还有安装错误

## 预防措施

### 开发流程
1. **统一 keystore 管理**：
   - 只保留一个 keystore 文件
   - 在 `keystore.properties` 中正确配置路径
   - 不要在代码中硬编码 keystore 路径

2. **签名配置标准化**：
   - 启用所有签名版本（v1 + v2 + v3）
   - 避免禁用任何签名版本

3. **构建验证**：
   - 每次发布前验证 APK 签名
   - 使用 `apkanalyzer` 检查 APK 完整性

### 设备兼容性
1. **测试多设备**：
   - 在不同 Android 版本设备上测试
   - 特别测试 Realme、OPPO 等国产 ROM 设备

2. **参考已知问题**：
   - 注释提到 Realme Neo 8 可能拒绝 v3 签名
   - 但当前配置已禁用 v3，所以这不是问题

## 当前状态
- ✅ 已修复 applicationId 不一致问题
- ✅ 已修改签名配置（启用 v1 + v2 + v3）
- ⚠️ 构建环境有问题（原生依赖编译失败）
- ⚠️ 需要清理构建缓存并重新构建

## 下一步行动
1. 手动清理构建目录（删除 `.gradle/`、`build/`、`app/build/`）
2. 修复原生依赖配置
3. 重新构建 release APK
4. 在目标设备上测试安装

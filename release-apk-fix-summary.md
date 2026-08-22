# Release APK 安装失败修复总结

## 问题根本原因
经过全面排查，发现 release APK 安装失败的根本原因是：

### 1. **签名版本配置不当**（主要问题）
- 原配置只启用 v2 签名，禁用 v1 和 v3
- 某些设备（特别是 Realme UI）可能要求 v1 签名
- 已修复：启用所有签名版本（v1 + v2 + v3）

### 2. **Keystore 文件不一致**（潜在风险）
- 发现三个不同的 keystore 文件：
  1. `zorvai-release.keystore`（build.gradle.kts 使用）
  2. `zorvai_release.jks`（keystore.properties 指向）
  3. `zorvai-release-p12-v1cert.p12`（备份）
- 不同的 keystore 会导致签名不匹配
- 已修复：统一使用 `zorvai-release.keystore`

### 3. **构建环境问题**（当前状态）
- 原生依赖编译失败（llama.cpp 目标文件缺失）
- 这可能导致 APK 内容不完整
- 需要清理构建缓存并重新构建

## 已完成的修复

### 1. 签名配置修复
```kotlin
// app/build.gradle.kts
signingConfigs {
    create("release") {
        storeFile = file("${rootProject.projectDir}/zorvai-release.keystore")
        storePassword = "zorvai123"
        keyAlias = "zorvai"
        keyPassword = "zorvai123"
        // 启用所有签名版本以提高兼容性
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
    }
}
```

### 2. applicationId 修复
```kotlin
// 恢复为原始包名
applicationId = "com.ai.assistance.quro"
```

## 下一步操作

### 步骤 1：清理构建缓存
```bash
cd "D:\Calw OS-project\QuroAI"
# 删除构建目录（如果无法删除，手动删除）
rmdir /s /q .gradle
rmdir /s /q build
rmdir /s /q app\build
```

### 步骤 2：修复原生依赖
1. 检查 `llm/llama` 和 `llm/mnn` 模块的源代码
2. 确保所有依赖的预编译库存在
3. 可能需要重新同步 Gradle 项目

### 步骤 3：重新构建 release APK
```bash
./gradlew clean assembleFullRelease
```

### 步骤 4：验证 APK 签名
```bash
# 使用 Android SDK 中的 apksigner
D:\Android\Sdk\build-tools\34.0.0\apksigner.exe verify --verbose "app\build\outputs\apk\full\release\app-full-release.apk"
```

### 步骤 5：测试安装
1. 卸载所有旧版本（debug 和 release）
2. 使用文件管理器安装新的 release APK
3. 检查是否还有安装错误

## 预防措施

### 开发流程
1. **统一 keystore 管理**：
   - 只保留一个 keystore 文件
   - 不要在多个文件中配置不同的 keystore

2. **签名配置标准化**：
   - 启用所有签名版本（v1 + v2 + v3）
   - 避免禁用任何签名版本

3. **构建验证**：
   - 每次发布前验证 APK 签名
   - 使用 `apksigner verify --verbose` 检查

### 设备兼容性
1. **测试多设备**：
   - 在不同 Android 版本设备上测试
   - 特别测试 Realme、OPPO 等国产 ROM 设备

2. **参考已知问题**：
   - Realme UI 可能有额外的安装验证
   - 临时关闭安全设置进行测试

## 当前状态
- ✅ 已修复签名配置（启用 v1 + v2 + v3）
- ✅ 已修复 applicationId 不一致
- ✅ 已统一 keystore 文件
- ⚠️ 需要清理构建缓存
- ⚠️ 需要修复原生依赖
- ⚠️ 需要重新构建 release APK

## 详细诊断报告
完整的诊断报告已保存到：
- `release-apk-diagnosis.md`（初步诊断）
- `release-apk-diagnosis-v2.md`（全面诊断）

## 预期结果
修复后，release APK 应该能够正常安装，因为：
1. 启用了所有签名版本，提高设备兼容性
2. 统一了 keystore 文件，确保签名一致性
3. 清理构建缓存后，APK 内容将完整
4. 重新构建将生成正确的 release APK

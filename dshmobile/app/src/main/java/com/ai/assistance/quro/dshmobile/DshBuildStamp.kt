package com.ai.assistance.quro.dshmobile

/**
 * dshmobile 壳侧（引导页 / 桥 / 诊断）的 UI 构建戳。
 *
 * versionName 是用户的，不许动；宿主 PackageManager 版本所有包又都一样。区分「桌上这个
 * APK 里装的是哪一版 dshmobile 壳」只能靠它——引导页版本 pill 直接显示
 * `v<宿主版本> · <本戳>`，界面上看不到戳就等于装错包（同 terminal-core 侧
 * DeepSeekHarness.BUILD_STAMP 的做法，两侧戳值保持一致、出包时同改）。
 *
 * 注意与引擎快照指纹的分工：本戳随**壳侧 UI 改动**升；assets/snapshot.tar.xz 的指纹
 * 随**引擎资产/补丁改动**变（EngineManager.snapshotFresh 对账）。两者都变才算「新包」。
 */
internal object DshBuildStamp {
  const val STAMP = "B20261027-0121"
}

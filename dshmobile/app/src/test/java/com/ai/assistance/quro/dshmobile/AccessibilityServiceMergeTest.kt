package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍服务合并契约（#484）。
 *
 * ## 用户报的问题
 * 两张截图：系统「无障碍」列表里同时出现 `Zorv AI` 与 `ZorvAI 设备控制` 两项 ——
 * 同一个 APK 注册了**两个** AccessibilityService：
 *   · app 模块 `com.ai.assistance.quro.service.QuroAccessibilityService`
 *   · dshmobile 模块 `com.ai.assistance.quro.dshmobile.DeviceControlService`
 * 用户必须开两个开关，关一个就有一半能力失效（harness 侧报未授权），这就是「权限重复没有融合」。
 *
 * 修法是**只留一个服务类**：`QuroAccessibilityService` 继承 `DeviceControlService`，
 * 一套生命周期同时供养 ZorvAI 工具与 harness 控制面。
 *
 * 本测试守四件事：
 *   1. 两个清单合起来只能有 **1 个** AccessibilityService 声明；
 *   2. 继承关系成立（`DeviceControlService` 必须是 `open`，否则子类化不了）；
 *   3. 权限判据只剩一个 ComponentName（不再有「开了一个还提示另一个」）；
 *   4. 用户在系统设置里照着找的那句话（`UserCopy.A11Y_SERVICE_NAME`）与清单标签逐字一致。
 */
class AccessibilityServiceMergeTest {

  private fun repoFile(relative: String): File {
    val candidates = listOf(
      File("../../$relative"), // dshmobile/app 是工作目录
      File("../$relative"),
      File(relative),
      File("D:/Calw OS-project/QuroAI/$relative"),
    )
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 $relative（工作目录 = " + File(".").absolutePath + "）")
  }

  private val appManifest: String by lazy { repoFile("app/src/main/AndroidManifest.xml").readText() }
  private val dshManifest: String by lazy { repoFile("dshmobile/app/src/main/AndroidManifest.xml").readText() }

  /** 统计某个清单里「声明了 AccessibilityService intent-filter 的 <service>」个数。 */
  private fun a11yServiceCount(manifest: String): Int =
    Regex("<service[\\s\\S]*?</service>").findAll(manifest)
      .count { it.value.contains("android.accessibilityservice.AccessibilityService") }

  @Test
  fun `两个清单合起来只能有一个无障碍服务声明`() {
    val appCount = a11yServiceCount(appManifest)
    val dshCount = a11yServiceCount(dshManifest)
    assertEquals("app 模块必须保留唯一的无障碍服务声明", 1, appCount)
    assertEquals(
      "dshmobile 清单不得再声明第二个无障碍服务（系统里会出现两项，用户要开两个开关）",
      0,
      dshCount,
    )
    assertEquals("合计必须恰好 1 个", 1, appCount + dshCount)
  }

  @Test
  fun `唯一服务必须是继承合并后的类`() {
    val appManifestA11y = Regex("<service[\\s\\S]*?</service>").findAll(appManifest)
      .map { it.value }
      .first { it.contains("android.accessibilityservice.AccessibilityService") }
    assertTrue(
      "清单里的服务必须是 QuroAccessibilityService（它继承 DeviceControlService）",
      appManifestA11y.contains(".service.QuroAccessibilityService"),
    )
    assertTrue(
      "必须声明 label：系统列表里靠它认出这是哪一项",
      appManifestA11y.contains("android:label="),
    )

    val service = repoFile("app/src/main/java/com/ai/assistance/quro/service/QuroAccessibilityService.kt").readText()
    assertTrue(
      "QuroAccessibilityService 必须继承 DeviceControlService",
      service.contains("class QuroAccessibilityService : DeviceControlService()"),
    )
    // 生命周期必须转发给父类，否则 harness 侧的 instance/标记/ControlCarrier 登记不会发生。
    listOf("super.onServiceConnected()", "super.onAccessibilityEvent(event)", "super.onDestroy()")
      .forEach { assertTrue("缺少父类转发：$it", service.contains(it)) }

    val parent = repoFile("dshmobile/app/src/main/java/com/ai/assistance/quro/dshmobile/DeviceControlService.kt").readText()
    assertTrue(
      "DeviceControlService 必须是 open（Kotlin 类默认 final，不改就子类化不了）",
      parent.contains("open class DeviceControlService"),
    )
  }

  @Test
  fun `权限判据只剩一个 ComponentName`() {
    val helper = repoFile("app/src/main/java/com/ai/assistance/quro/core/permissions/QuroPermissionHelper.kt").readText()
    // 只看 accessibility 这一段：合并后不应再出现第二个服务的 ComponentName。
    val start = helper.indexOf("private fun accessibility(")
    assertTrue("找不到 accessibility() 判据", start >= 0)
    val section = helper.substring(start, (start + 1400).coerceAtMost(helper.length))
    assertFalse(
      "不得再把 DeviceControlService 当成第二个启用判据",
      section.contains("DeviceControlService"),
    )
    assertEquals(
      "只应有一个 ComponentName 构造",
      1,
      Regex("ComponentName\\(").findAll(section).count(),
    )
  }

  @Test
  fun `用户照着找的那句话必须与清单标签逐字一致`() {
    // 系统列表里显示的是清单 label；UserCopy 用它写「去系统设置找 X」——
    // 两处不一致，用户就照着这句话找不到那一项（合并改名后极易漏改）。
    val label = Regex("<string name=\"a11y_service_label\"[^>]*>([^<]*)</string>")
      .find(repoFile("dshmobile/app/src/main/res/values/strings.xml").readText())
      ?.groupValues?.get(1)?.trim()
      ?: ""
    assertTrue("找不到 a11y_service_label", label.isNotBlank())
    assertEquals("UserCopy.A11Y_SERVICE_NAME 必须与清单标签一致", label, UserCopy.A11Y_SERVICE_NAME)

    // 清单 label 指向的字符串资源也必须同值（真正显示在系统设置里的是它）。
    val appLabel = Regex("<string name=\"qk_04410\"[^>]*>([^<]*)</string>")
      .find(repoFile("app/src/main/res/values/strings_i18n.xml").readText())
      ?.groupValues?.get(1)?.trim()
      ?: ""
    assertEquals("清单 label（qk_04410）必须与 a11y_service_label 同值", label, appLabel)
  }

  @Test
  fun `已授权的权限卡不得再挂请求授权主按钮`() {
    val screen = repoFile("app/src/main/java/com/ai/assistance/quro/ui/QuroPermissionScreen.kt").readText()
    val start = screen.indexOf("private fun PrivilegeCard(")
    assertTrue("找不到 PrivilegeCard", start >= 0)
    val section = screen.substring(start, (start + 3000).coerceAtMost(screen.length))
    assertTrue(
      "必须按 state.available 分支处理按钮（已可用却挂「请求授权」就是重复的授权入口）",
      section.contains("if (state.available)"),
    )
    assertTrue(
      "已可用时改为弱化文字链",
      section.contains("R.string.qk_04411"),
    )
  }
}

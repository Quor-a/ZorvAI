package com.ai.assistance.quro.dshmobile

import com.ai.assistance.quro.dshmobile.AcceptRouting.isImageOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件/图片入口分流回归（0.14.0 真机+模拟器实报）。
 *
 * 缺陷形态：点「上传附件」和点「上传图片」，**跳转的是同一个界面**（都是系统文件选择器），
 * 用户原话「俩控件跳转都是跳到了文件 picker 而不是一个文件一个相册」。
 *
 * 真因分两层：
 *   ① 适配层：Tooltip 气泡插在回形针与 input 之间，兄弟相邻判据失效，菜单根本没拦到点击（坑 140）；
 *   ② 壳侧：0.13.7fx-1 为修「空 accept → 受限『近期的图片』视图」把相册分支**整个删掉**，
 *      所有类型一律 SAF。适配层把 accept 正确设成图片类型也没人看。
 *
 * 本测试钉住的是第 ② 层的分流口径：**只有声明为纯图片时才进相册**。
 * 混合声明（含全部文件通配或扩展名）必须退回文档选择器，否则用户挑不到那个非图片文件。
 */
class AcceptRoutingTest {

  @Test
  fun imageWildcardGoesToTheGallery() {
    assertTrue(isImageOnly(listOf("image/*")))
  }

  @Test
  fun concreteImageTypesGoToTheGallery() {
    assertTrue(isImageOnly(listOf("image/png")))
    assertTrue(isImageOnly(listOf("image/jpeg", "image/webp")))
  }

  @Test
  fun acceptIsCaseInsensitive() {
    // WebView 原样透传页面声明的 accept，大小写不受控。
    assertTrue(isImageOnly(listOf("IMAGE/*")))
    assertTrue(isImageOnly(listOf("Image/Png")))
  }

  @Test
  fun attachmentEntryStaysOnTheDocumentPicker() {
    // 「上传附件」把 accept 设成全部文件通配——必须留在 SAF，否则挑不到任意类型文件。
    assertFalse(isImageOnly(listOf("*/*")))
  }

  @Test
  fun emptyDeclarationStaysOnTheDocumentPicker() {
    // accept 未声明：正是当年那个「空 MIME 数组落到受限视图」的场景，必须走显式通配的 SAF 分支。
    assertFalse(isImageOnly(emptyList()))
    assertFalse(isImageOnly(listOf("   ").filter { it.isNotBlank() }))
  }

  @Test
  fun extensionDeclarationsStayOnTheDocumentPicker() {
    assertFalse(isImageOnly(listOf(".pdf")))
    assertFalse(isImageOnly(listOf(".png")))  // 扩展名型也走 SAF：MimeTypeMap 归一化在那里做
  }

  @Test
  fun mixedDeclarationsStayOnTheDocumentPicker() {
    // 混入非图片就退回 SAF：送进相册会让用户看不到那个非图片文件。
    assertFalse(isImageOnly(listOf("image/*", "application/pdf")))
    assertFalse(isImageOnly(listOf("*/*", "image/*")))
  }
}

package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.GenUIParentAxis
import com.ai.assistance.quro.genui.sdk.render.RenderContext

/**
 * 图片组件类型常量
 */
const val IMAGE_TYPE = ComponentTypes.IMAGE

/**
 * Image图片组件渲染器
 *
 * 支持的属性：
 * - src: 图片URL或资源路径（支持数据绑定）
 * - contentScale: 图片缩放方式（fit/crop/inside/none/fillWidth/fillHeight，默认fit）
 *
 * 支持的样式：
 * - width: 图片宽度
 * - height: 图片高度
 * - cornerRadius: 圆角
 * - contentDescription: 内容描述
 */
@Composable
fun ImageRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val src = component.propStringResolved("src", ctx) ?: return
    val contentDescription = component.style.contentDescription ?: ""
    val contentScale = when (component.propString("contentScale")) {
        "crop" -> ContentScale.Crop
        "inside" -> ContentScale.Inside
        "none" -> ContentScale.None
        "fillWidth" -> ContentScale.FillWidth
        "fillHeight" -> ContentScale.FillHeight
        "fillBounds" -> ContentScale.FillBounds
        else -> ContentScale.Fit
    }

    val imageModifier = modifier.then(
        when {
            component.style.width is Dimension.Fixed && component.style.height is Dimension.Fixed -> {
                Modifier.size(
                    width = (component.style.width as Dimension.Fixed).dp.dp,
                    height = (component.style.height as Dimension.Fixed).dp.dp
                )
            }
            component.style.width is Dimension.Fixed -> {
                Modifier.width((component.style.width as Dimension.Fixed).dp.dp)
            }
            component.style.height is Dimension.Fixed -> {
                Modifier.height((component.style.height as Dimension.Fixed).dp.dp)
            }
            // 🔴 2026-10-28「尺寸多大围栏就大大」真元（用户截图的天气卡）：
            //    旧实现这里是无条件 fillMaxSize() —— 模型没写 width/height 时，
            //    图片把**父容器剩余空间整块吃走**：
            //    · 有界父容器（Row / 定高卡 / height:match 的卡）里 → 图片框被撑成
            //      parent 的满高满宽，ContentScale.Fit 再把图缩中间留 letterbox 空白
            //      → 用户看到的就是「卡片内容在顶部、下方巨大空白」；
            //    · Row 里连宽度一起吃掉 → 兄弟节点被压扁，图标被按剩余宽度等比放大。
            //    正确默认：竖排页面里只吃满**宽度**（高度按图片宽高比随内容），
            //    Row 里连宽度都不吃（按图片固有尺寸），把空间留给兄弟节点。
            component.style.width is Dimension.Match ||
                component.style.width is Dimension.Weight ->
                if (GenUIParentAxis.inRow) Modifier else Modifier.fillMaxWidth()
            component.style.height is Dimension.Match ||
                component.style.height is Dimension.Weight ->
                if (GenUIParentAxis.inRow) Modifier else Modifier.fillMaxHeight()
            else -> if (GenUIParentAxis.inRow) Modifier else Modifier.fillMaxWidth()
        }
    )

    val painter: Painter = rememberAsyncImagePainter(
        model = src,
        contentScale = contentScale
    )

    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = imageModifier,
        contentScale = contentScale
    )
}

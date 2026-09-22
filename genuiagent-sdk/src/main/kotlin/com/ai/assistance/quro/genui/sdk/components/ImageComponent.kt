package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
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
            else -> Modifier.fillMaxSize()
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

package com.ai.assistance.quro.core.assistant

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * 直接引用 OPPO 小布（`com.heytap.speechassist`）包内的 Lottie 动效。
 *
 * ## 这不是"照着做"，是把它的资源文件原样搬进来
 * 四个文件从 `Breeno.apk` 里字节级拷出，落在 `app/src/main/assets/lottie/`：
 *
 * | 本地文件名 | 小布包内路径 | 大小 | 原始用途 |
 * |---|---|---|---|
 * | `breeno_listen.json` | `assets/listen/listen_anim.json` | 8,697 B | 聆听中 |
 * | `breeno_shake.json` | `assets/lottie/ca_ui_common_shake.json` | 8,123 B | 出错抖动 |
 * | `breeno_loading.json` | `assets/loading.json` | 8,515 B | 轻量加载 |
 * | `breeno_empty_dark.json` | `assets/ai_ask_empty_content_dark.json` | 31,957 B | 空状态（暗色） |
 *
 * 它包里还有几个更大的同类动效（`server_died_dark.json` 668KB、`opay_network_error.json` 781KB、
 * `loading_dark.json` 520KB），**故意没搬**：我们是 835MB 的包，为三个动效再加 2MB 不值得。
 *
 * ## 为什么值得搬
 * 我们这几个态此前是纯文字（失败卡一行灰字、空列表一行"没有匹配"），
 * 小布在这些高频态上有成熟表达。Lottie 是纯矢量资源、零代码耦合，搬过来就能用。
 * 缺资源或解析失败时 [AssistantLottie] 会**静默不占位**，绝不因为一个动效把界面搞白。
 */
object AssistantLottie {
    const val LISTEN = "lottie/breeno_listen.json"
    const val SHAKE = "lottie/breeno_shake.json"
    const val LOADING = "lottie/breeno_loading.json"
    const val EMPTY_DARK = "lottie/breeno_empty_dark.json"
}

/**
 * 播一个 assets 里的 Lottie 动效。
 *
 * @param loop 是否无限循环（错误抖动这类应该只播一次）
 * @param playing 是否播放（可用来暂停）
 */
@Composable
fun AssistantLottie(
    asset: String,
    modifier: Modifier = Modifier.size(64.dp),
    loop: Boolean = true,
    playing: Boolean = true,
) {
    // 加载失败时 composition 为 null，LottieAnimation 自己什么都不画 —— 不给界面添乱
    val comp by rememberLottieComposition(LottieCompositionSpec.Asset(asset))
    val progress by animateLottieCompositionAsState(
        composition = comp,
        isPlaying = playing,
        iterations = if (loop) LottieConstants.IterateForever else 1,
    )
    LottieAnimation(
        composition = comp,
        progress = { progress },
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

package com.ai.assistance.quro.core.mika.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ai.assistance.quro.core.mika.uistate.VisibleTextLine
import com.ai.assistance.quro.core.mika.uistate.normalize
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 两套内置模型均在本机运行；同一帧并行识别，完成后才释放位图。两套都出错时抛异常(不当成没字)。 */
object PhoneOcr {
    private const val TAG = "PhoneOcr"

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 预热：主动初始化两套识别器。
     *
     * 首次 `process` 要加载模型（约 1s），这一步在真机上表现为「第一次看屏明显卡一下」。
     * 趁空闲先把它付掉，之后的识别就是纯推理。失败无副作用（`lazy` 不会缓存异常，
     * 下次真正用到时会重试）。
     */
    fun warmUp() {
        runCatching { chinese }
        runCatching { latin }
    }

    suspend fun read(bitmap: Bitmap): List<VisibleTextLine> {
        val recognizers = try { listOf(chinese, latin) } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        val image = try { InputImage.fromBitmap(bitmap, 0) } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        return suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(recognizers.size)
            val succeeded = AtomicInteger(0)
            val lines = Collections.synchronizedList(mutableListOf<VisibleTextLine>())

            fun finish(result: Text?) {
                if (result != null) succeeded.incrementAndGet()
                result?.textBlocks?.forEach { block -> block.lines.forEach { line ->
                    val box = line.boundingBox ?: return@forEach
                    val value = line.text.trim()
                    // 有的识别模型不给置信度(为 0),当作没有
                    if (value.isNotEmpty()) lines += VisibleTextLine(value, listOf(
                        normalize(box.left, bitmap.width), normalize(box.top, bitmap.height),
                        normalize(box.right, bitmap.width), normalize(box.bottom, bitmap.height),
                    ), line.confidence.takeIf { it > 0f })
                } }
                if (remaining.decrementAndGet() == 0) {
                    val complete = lines.toList()
                    bitmap.recycle()
                    // 两套都出错不是「屏上没字」:照实报错,读屏结果才写得出 ocr: failed
                    if (continuation.isActive) {
                        if (succeeded.get() == 0) continuation.resumeWithException(IllegalStateException("text recognition failed"))
                        else continuation.resume(complete)
                    }
                }
            }

            for (recognizer in recognizers) try {
                val task: Task<Text> = recognizer.process(image)
                task.addOnCompleteListener { done ->
                    if (!done.isSuccessful) Log.w(TAG, "recognizer failed", done.exception)
                    finish(if (done.isSuccessful) done.result else null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "recognizer failed", e)
                finish(null)
            }
        }
    }
}

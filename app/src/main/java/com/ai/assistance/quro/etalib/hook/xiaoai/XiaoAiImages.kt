package com.ai.assistance.quro.etalib.hook.xiaoai

import com.ai.assistance.quro.etalib.agent.model.AgentModelClient
import java.io.File
import java.io.FileInputStream

internal object XiaoAiImages {
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

    sealed interface Resolution {
        data object NoImage : Resolution

        data class Success(
            val image: AgentModelClient.ModelImage,
        ) : Resolution

        data class Failure(
            val code: FailureCode,
        ) : Resolution
    }

    enum class FailureCode {
        PATH_MISSING,
        FILE_UNREADABLE,
        FILE_EMPTY,
        FILE_TOO_LARGE,
        UNSUPPORTED_FORMAT,
    }

    fun validatePath(path: String?): Resolution {
        val normalized = path?.trim().orEmpty()
        if (normalized.isBlank()) return Resolution.NoImage
        val file = File(normalized)
        if (!file.isFile || !file.canRead()) {
            return Resolution.Failure(FailureCode.FILE_UNREADABLE)
        }
        val length = file.length()
        if (length <= 0L) return Resolution.Failure(FailureCode.FILE_EMPTY)
        if (length > MAX_IMAGE_BYTES) return Resolution.Failure(FailureCode.FILE_TOO_LARGE)
        val mimeType = detectMimeType(file)
            ?: return Resolution.Failure(FailureCode.UNSUPPORTED_FORMAT)
        return Resolution.Success(
            AgentModelClient.ModelImage(
                reference = file.absolutePath,
                mimeType = mimeType,
                bytes = length.toInt(),
                source = "xiaoai",
            )
        )
    }

    fun missingPath(): Resolution.Failure =
        Resolution.Failure(FailureCode.PATH_MISSING)

    private fun detectMimeType(file: File): String? {
        val header = ByteArray(12)
        val read = runCatching {
            FileInputStream(file).use { input -> input.read(header) }
        }.getOrDefault(-1)
        if (read < 3) return null
        return when {
            header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte() -> "image/jpeg"

            read >= 8 &&
                header.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(
                        0x89.toByte(),
                        0x50,
                        0x4E,
                        0x47,
                        0x0D,
                        0x0A,
                        0x1A,
                        0x0A,
                    )
                ) -> "image/png"

            read >= 6 &&
                String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ->
                "image/gif"

            read >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

            else -> null
        }
    }
}

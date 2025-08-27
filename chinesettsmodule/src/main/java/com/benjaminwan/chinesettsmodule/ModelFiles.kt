package com.benjaminwan.chinesettsmodule

import android.content.Context
import java.io.File

/**
 * 负责把 assets 里的模型复制到内部存储 /files/models/tts
 * 路径对应你现在的目录结构：
 * app/src/main/assets/models/tts/fastspeech2_quan.tflite
 * app/src/main/assets/models/tts/mb_melgan.tflite
 * app/src/main/assets/models/tts/baker_mapper.json
 */
object ModelFiles {
    private const val ASSET_DIR = "models/tts"
    private const val FS2_NAME = "fastspeech2_quan.tflite"
    private const val MELGAN_NAME = "mb_melgan.tflite"
    private const val MAPPER_NAME = "baker_mapper.json" // 可选

    fun ensureCopied(context: Context): Pair<File, File> {
        val dstDir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val fs2 = File(dstDir, FS2_NAME)
        val mel = File(dstDir, MELGAN_NAME)
        val map = File(dstDir, MAPPER_NAME)

        copyIfNeeded(context, "$ASSET_DIR/$FS2_NAME", fs2)
        copyIfNeeded(context, "$ASSET_DIR/$MELGAN_NAME", mel)
        // 如果 assets 中存在映射文件就复制（ZhProcessor 可能会用到）
        copyIfNeeded(context, "$ASSET_DIR/$MAPPER_NAME", map, optional = true)

        return fs2 to mel
    }

    private fun copyIfNeeded(
        context: Context,
        assetPath: String,
        dst: File,
        optional: Boolean = false
    ) {
        if (dst.exists() && dst.length() > 0) return
        dst.parentFile?.mkdirs()
        try {
            context.assets.open(assetPath).use { input ->
                dst.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            if (!optional) throw e
            // 可选文件缺失就忽略
        }
    }
}

//package com.example.mnnllmdemo.util
//
//
//import android.content.Context
//import java.io.File
//import java.io.FileOutputStream
//
//object AssetUtils {
//    fun ensureAssetDirCopied(context: Context, assetDir: String, targetDir: File): File {
//        if (!targetDir.exists()) targetDir.mkdirs()
//        val assetManager = context.assets
//        fun copyRec(src: String, dst: File) {
//            val list = assetManager.list(src) ?: emptyArray()
//            if (list.isEmpty()) {
//                // file
//                assetManager.open(src).use { input ->
//                    FileOutputStream(dst).use { out -> input.copyTo(out) }
//                }
//            } else {
//                // dir
//                if (!dst.exists()) dst.mkdirs()
//                list.forEach { name ->
//                    copyRec("$src/$name", File(dst, name))
//                }
//            }
//        }
//        copyRec(assetDir, targetDir)
//        return targetDir
//    }
//}
package com.example.mnnllmdemo.util

import android.content.Context
import java.io.File

object AssetUtils {
    /**
     * 确保将 assets 内指定目录下的所有文件复制到目标目录。
     * @param context Context 用于访问应用的资产管理器
     * @param assetDir assets目录中的相对路径，如 "models/YourModelName"
     * @param destDir  复制到的目标 File 目录
     */
    fun ensureAssetDirCopied(context: Context, assetDir: String, destDir: File) {
        try {
            val assetManager = context.assets
            // 列出资产目录内容
            val files = assetManager.list(assetDir) ?: return
            if (!destDir.exists()) destDir.mkdirs()
            for (fileName in files) {
                val assetPath = if (assetDir.isNotEmpty()) "$assetDir/$fileName" else fileName
                val outFile = File(destDir, fileName)
                // 判断是文件还是子目录
                val subFiles = assetManager.list(assetPath)
                if (subFiles.isNullOrEmpty()) {
                    // 复制文件
                    assetManager.open(assetPath).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    // 递归复制子目录
                    ensureAssetDirCopied(context, assetPath, outFile)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

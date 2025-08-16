package com.alibaba.mnnllm.android.llm

interface GenerateProgressListener {
    /** 返回 true 继续生成，false 中断 */
    fun onProgress(token: String): Boolean
    fun onComplete()
    fun onError(message: String)
}
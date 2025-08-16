package com.alibaba.mnnllm.android.llm


interface ChatSession {
    val debugInfo: String
    val sessionId: String?

    val supportOmni: Boolean
    fun load()
    fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any>
    fun reset(): String
    fun release()
    fun setKeepHistory(keepHistory: Boolean)
    fun setEnableAudioOutput(enable: Boolean)
    fun getHistory(): List<ChatDataItem>?
    fun setHistory(history: List<ChatDataItem>?)
}

/** 极简的会话消息结构，仅用 text 即可 */
data class ChatDataItem(val text: String)
package com.alibaba.mnnllm.android.llm

import android.util.Log
import com.google.gson.Gson
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// （可选）把 ChatDataItem 换成你自己的数据类，或直接用 List<Pair<String, String>>
class LlmSession(
    private val modelId: String,
    override var sessionId: String,
    private val configPath: String,
    private var savedHistory: List<ChatDataItem>?
) : ChatSession {

    override var supportOmni: Boolean = false
    private var nativePtr: Long = 0L

    private val modelLoading = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val releaseRequested = AtomicBoolean(false)
    private var keepHistory = false
    private var extraAssistantPrompt: String? = null

    // —— 显式拼串：把 (role, content) 转成最终 prompt —— //
    private fun buildQwenPrompt(messages: List<Pair<String, String>>): String {
        fun wrap(role: String, content: String): String {
            return when (role) {
                "system"    -> "<|im_start|>system\n$content<|im_end|>\n"
                "user"      -> "<|im_start|>user\n$content<|im_end|>\n"
                "assistant" -> "<|im_start|>assistant\n$content<|im_end|>\n"
                else        -> "<|im_start|>user\n$content<|im_end|>\n"
            }
        }
        val sb = StringBuilder()
        for ((role, content) in messages) sb.append(wrap(role, content))
        // ⭐ assistant 起笔：这是关键，如果没有这句，常会出现 decode_len=1 直接停
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** 最稳妥的生成通道：显式拼串后走 submitNative（与官方链路一致） */
    fun generateFromMessages(
        messages: List<Pair<String, String>>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        val prompt = buildQwenPrompt(messages)
        synchronized(this) {
            generating.set(true)
            try {
                return submitNative(nativePtr, prompt, /*keepHistory=*/false, progressListener)
            } finally {
                generating.set(false)
                if (releaseRequested.get()) release()
            }
        }
    }

    override fun getHistory(): List<ChatDataItem>? = savedHistory
    override fun setHistory(history: List<ChatDataItem>?) { savedHistory = history }

    override fun load() {
        modelLoading.set(true)

        // 让底层自己管理历史（我们 demo 单轮生成，不传历史字符串）
        val historyStringList: List<String>? = null

        // 与官方一致：告知是否 R1、mmap 目录、是否保留历史
        val configMap = hashMapOf(
            "is_r1" to false,                         // 你的 0.6B 不是 R1
            "mmap_dir" to File(configPath).parent,    // mmap 缓存目录
            "keep_history" to keepHistory
        )
        val extraConfig = hashMapOf(
            // 允许动态更新 assistant 模板
            "assistantPromptTemplate" to extraAssistantPrompt
        )

        nativePtr = initNative(
            configPath,
            historyStringList,
            Gson().toJson(extraConfig),
            Gson().toJson(configMap)
        )
        modelLoading.set(false)
        if (releaseRequested.get()) release()
    }

    override fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        Timber.d("start generate: $prompt")
        synchronized(this) {
            generating.set(true)
            try {
                return submitNative(nativePtr, prompt, keepHistory, progressListener)
            } finally {
                generating.set(false)
                if (releaseRequested.get()) release()
            }
        }
    }

    override fun reset(): String {
        synchronized(this) { resetNative(nativePtr) }
        sessionId = System.currentTimeMillis().toString()
        return sessionId
    }

    override fun release() {
        synchronized(this) {
            Log.d(TAG, "release nativePtr=$nativePtr generating=${generating.get()} loading=${modelLoading.get()}")
            if (!generating.get() && !modelLoading.get()) {
                releaseInner()
            } else {
                releaseRequested.set(true) // 等当前动作结束再释放
            }
        }
    }

    private fun releaseInner() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0L
        }
    }

    override val debugInfo: String
        get() = getDebugInfoNative(nativePtr) + "\n"

    override fun setKeepHistory(keepHistory: Boolean) { this.keepHistory = keepHistory }
    override fun setEnableAudioOutput(enable: Boolean) { updateEnableAudioOutputNative(nativePtr, enable) }

    fun updateMaxNewTokens(maxNewTokens: Int) = updateMaxNewTokensNative(nativePtr, maxNewTokens)
    fun updateSystemPrompt(systemPrompt: String) = updateSystemPromptNative(nativePtr, systemPrompt)
    fun updateAssistantPrompt(assistantPrompt: String) {
        extraAssistantPrompt = assistantPrompt
        updateAssistantPromptNative(nativePtr, assistantPrompt)
    }

    // —— 如果你后面想继续用“全历史” JNI 通道，也保留它 —— //
    fun submitFullHistory(
        history: List<Pair<String, String>>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        synchronized(this) {
            generating.set(true)
            try {
                val androidHistory = history.map { android.util.Pair(it.first, it.second) }
                return submitFullHistoryNative(nativePtr, androidHistory, progressListener)
            } finally {
                generating.set(false)
                if (releaseRequested.get()) release()
            }
        }
    }

    // —— JNI —— //
    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun submitFullHistoryNative(
        instanceId: Long,
        history: List<android.util.Pair<String, String>>,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun resetNative(instanceId: Long)
    private external fun getDebugInfoNative(instanceId: Long): String
    private external fun releaseNative(instanceId: Long)

    private external fun updateEnableAudioOutputNative(llmPtr: Long, enable: Boolean)
    private external fun updateMaxNewTokensNative(llmPtr: Long, maxNewTokens: Int)
    private external fun updateSystemPromptNative(llmPtr: Long, systemPrompt: String)
    private external fun updateAssistantPromptNative(llmPtr: Long, assistantPrompt: String)

    companion object {
        private const val TAG = "LlmSession"
        init { System.loadLibrary("mnnllmapp") }
    }
}

package com.example.mnnllmdemo.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alibaba.mnnllm.android.llm.LlmSession
import com.benjaminwan.chinesettsmodule.TtsEngine
import com.example.mnnllmdemo.SafeProgressListener
import com.example.mnnllmdemo.util.AssetUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻会话 + 官方“全历史”通道（submitFullHistory）：
 * - init(context): 资产拷贝 + 初始化TTS（会话延迟创建）
 * - setSystemPrompt(p): 更新 system（只影响后续 ask）
 * - ask(question, ...): 每轮清空历史，submitFullHistory 获取答案；不依赖 onComplete
 * - release(): 释放
 *
 * 要点：
 * 1) submitFullHistory(...) 的返回 HashMap 兜底取整段答案，解决部分机型/版本不回调 onComplete 的问题
 * 2) 过滤 <think>，在主线程串行调用 TTS，避免并发崩溃
 */
object LlmSimpleClient {
    private const val TAG = "LLM"
    private const val MODEL_DIR_IN_ASSETS = "models/Qwen3-0.6B-MNN"
    private const val DEFAULT_SYSTEM = ""

    @Volatile private var appCtx: Context? = null
    private val inited = AtomicBoolean(false)

    // 可动态修改的 system prompt（发送时放到 messages[0]）
    @Volatile private var systemPrompt: String = DEFAULT_SYSTEM

    // 调试：是否打印带 <think> 的原始输出
    @Volatile var logWithThink: Boolean = false

    // —— 常驻会话（懒加载一次，后续复用） —— //
    @Volatile private var session: LlmSession? = null
    private val sessionLock = Any()

    // 全局 TTS 锁，确保语音播报串行
    private val ttsLock = Any()

    /** 一次性：拷贝模型目录 + 初始化 TTS（会话稍后再建） */
    fun init(context: Context) {
        if (!inited.compareAndSet(false, true)) return
        try {
            val ctx = context.applicationContext
            appCtx = ctx

            val localModelDir = File(ctx.filesDir, MODEL_DIR_IN_ASSETS)
            AssetUtils.ensureAssetDirCopied(ctx, MODEL_DIR_IN_ASSETS, localModelDir)
            Log.d(TAG, "Assets copied to: ${localModelDir.absolutePath}")

            try { TtsEngine.init(ctx) } catch (_: Throwable) { /* 忽略 TTS 初始化失败 */ }
        } catch (t: Throwable) {
            inited.set(false)
            throw t
        }
    }

    /** 外部可随时更新 system；空串则回落到默认值 */
    fun setSystemPrompt(p: String) {
        systemPrompt = p
    }

    /** 懒创建/获取常驻会话：仅首次 load，之后复用 */
    private fun getOrCreateSession(ctx: Context): LlmSession {
        session?.let { return it }
        synchronized(sessionLock) {
            session?.let { return it }
            val modelDir = File(ctx.filesDir, MODEL_DIR_IN_ASSETS)
            val configPath = File(modelDir, "config.json").absolutePath
            val s = LlmSession(
                modelId = "Qwen3-0.6B-MNN",
                sessionId = System.currentTimeMillis().toString(),
                configPath = configPath,
                savedHistory = null
            ).apply {
                // 关键：常驻但每轮“无历史”
                setKeepHistory(false)
                setHistory(null)
                // 不设置 assistant/system 模板，交给底层 chat_template 统一处理
                load()
            }
            session = s
            return s
        }
    }

    /**
     * 发起一次推理：
     * - 复用常驻会话，但每次调用前都清空历史，保证“本轮干净”
     * - 走 submitFullHistory(messages, listener) 官方通道
     * - 不依赖 onComplete：优先用流式收集；若为空，从返回 HashMap 兜底取整段答案
     */
    fun ask(
        question: String,
        systemPromptOverride: String? = null,
        onAnswer: ((String) -> Unit)? = null
    ) {
        val ctx = appCtx
        if (ctx == null) {
            Log.e(TAG, "ask() called before init().")
            return
        }

        val sys = (systemPromptOverride ?: systemPrompt).ifBlank { DEFAULT_SYSTEM }
        val s = getOrCreateSession(ctx)

        // 确保本轮无历史
        s.setKeepHistory(false)
        s.setHistory(null)

        val sb = StringBuilder()
        val listener = object : SafeProgressListener() {
            // 流式 token：能拿就收
            override fun onProgressNullable(token: String?): Boolean {
                if (!token.isNullOrEmpty()) sb.append(token)
                return false
            }

            // 某些版本不回调；这里仅打印
            override fun onComplete() {
                Log.d(TAG, "[onComplete] may not be called on some SDK versions")
            }

            override fun onError(message: String) {
                Log.e(TAG, "[LLM Error] $message")
            }
        }

        try {
            val messages = listOf(
                "system" to sys,
                "user" to question
            )

            // ★ 阻塞调用：返回值里通常带有完整答案
            val resultMap = s.submitFullHistory(messages, listener)
            Log.d(TAG, "[submitFullHistory] result keys=${resultMap.keys.joinToString()}")

            // 先取流式；空的话再兜底
            var raw = sb.toString()
            if (raw.isBlank()) {
                raw = extractFinalFromResultMap(resultMap)
                Log.d(TAG, "[fallback] picked from resultMap: ${raw.take(60)}")
            }

            val finalText = sanitizeThink(raw).trim()

            if (logWithThink) Log.d(TAG, "LLM raw: $raw")
            Log.d(TAG, "LLM final: $finalText")

            try { onAnswer?.invoke(finalText) } catch (_: Throwable) {}

            if (finalText.isNotEmpty()) {
                // 等待 TTS 就绪（最多 1.5s），并打印等待时间
                val t0 = System.currentTimeMillis()
                while (!TtsEngine.isInitialized() && System.currentTimeMillis() - t0 < 1500L) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                }
                val waited = System.currentTimeMillis() - t0
                Log.d(TAG, "[TTS] isInit=${TtsEngine.isInitialized()} waited=${waited}ms len=${finalText.length} preview=${finalText.take(40)}")

                // 串行播报，切主线程调用
                try {
                    synchronized(ttsLock) {
                        Log.d(TAG, "[TTS] speak start")
                        Handler(Looper.getMainLooper()).post {
                            try {
                                TtsEngine.speak(finalText, true)
                                Log.d(TAG, "[TTS] speak posted to main")
                            } catch (tt: Throwable) {
                                Log.e(TAG, "[TTS] speak error on main: ${tt.message}", tt)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[TTS] speak error: ${t.message}", t)
                }
            } else {
                Log.w(TAG, "[TTS] finalText empty, skip")
            }

            Log.d(TAG, "[LLM Complete]")

        } catch (e: Throwable) {
            Log.e(TAG, "LLM exception: ${e.message}", e)
        }
    }

    /** 如需彻底回收模型（比如退后台节电）可以调这个 */
    fun release() {
        synchronized(sessionLock) {
            try { session?.release() } catch (_: Throwable) {}
            session = null
        }
        inited.set(false)
        appCtx = null
        Log.d(TAG, "LlmSimpleClient.release(): released session & reset init flag.")
    }

    // ============== 过滤<think>：整段统一处理 ==============
    private fun sanitizeThink(input: String): String {
        var s = input
        s = s.replace(Regex("(?is)<\\s*think\\s*>[\\s\\S]*?<\\s*/\\s*think\\s*>"), "")
        s = s.replace(Regex("(?is)<\\s*think\\s*>[\\s\\S]*$"), "")
        s = s.replace(Regex("(?is)<\\s*/?\\s*think\\s*>"), "")
        return s
    }

    // ============== 从 submitFullHistory 返回的 Map 中兜底抠答案 ==============
    private fun extractFinalFromResultMap(map: Map<String, Any>?): String {
        if (map == null) return ""
        try {
            // 常见候选 key（不同版本/平台命名可能不同）
            val candidates = listOf("text", "result", "response", "answer", "content", "final", "assistant", "message")
            for (k in candidates) {
                val v = map[k]
                if (v is String && v.isNotBlank()) return v
            }

            // 有些会带历史：尝试从历史里找最后一个 assistant
            val hist = map["history"]
            if (hist is List<*>) {
                val lastAssistant = hist.asReversed().firstNotNullOfOrNull { e ->
                    when (e) {
                        is Map<*, *> -> {
                            val role = e["role"]?.toString()?.lowercase()
                            val content = e["content"]?.toString()
                            if (role == "assistant" && !content.isNullOrBlank()) content else null
                        }
                        is Pair<*, *> -> {
                            val role = e.first?.toString()?.lowercase()
                            val content = e.second?.toString()
                            if (role == "assistant" && !content.isNullOrBlank()) content else null
                        }
                        else -> null
                    }
                }
                if (!lastAssistant.isNullOrBlank()) return lastAssistant
            }

            // 兜底：挑第一个非空字符串值
            map.values.forEach { v ->
                val s = when (v) {
                    is String -> v
                    is CharSequence -> v.toString()
                    else -> null
                }
                if (!s.isNullOrBlank()) return s!!
            }
        } catch (_: Throwable) { /* ignore */ }
        return ""
    }
}

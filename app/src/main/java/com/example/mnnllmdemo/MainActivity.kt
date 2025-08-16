package com.example.mnnllmdemo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.android.llm.LlmSession
import com.benjaminwan.chinesettsmodule.TtsEngine
import com.example.mnnllmdemo.util.AssetUtils
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var session: LlmSession? = null
    private lateinit var tv: TextView
    private lateinit var root: ScrollView

    private val ttsSpeaker by lazy { TtsSpeaker(this) }
    private val ttsCollector by lazy { StreamingTtsCollector() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply {
            textSize = 16f
            text = "Initializing...\n"
        }
        root = ScrollView(this).apply { addView(tv) }
        setContentView(root)

        // 先把模型从 assets 拷到内部目录（放到后台，避免主线程卡 5 秒报警）
        thread(name = "CopyAssets") {
            val assetModelDir = "models/Qwen3-0.6B-MNN"
            val localModelDir = File(filesDir, assetModelDir)
            AssetUtils.ensureAssetDirCopied(this, assetModelDir, localModelDir)

            runOnUiThread { append("Assets ready.\n") }

            // 初始化 TTS（只需一次）
            TtsEngine.init(applicationContext)
            runOnUiThread { append("TTS initialized.\n") }

            // 创建并加载 LLM 会话
            val configPath = File(localModelDir, "config.json").absolutePath
            session = LlmSession(
                modelId = "Qwen3-0.6B-MNN",
                sessionId = System.currentTimeMillis().toString(),
                configPath = configPath,
                savedHistory = null
            ).apply {
                setKeepHistory(false)
                setHistory(null)
                load()
                updateAssistantPrompt("<|im_start|>assistant\n%s<|im_end|>\n")
            }
            runOnUiThread { append("LLM session loaded.\n") }

            // 监听器：用 Java 包装，防止 null token 触发 Kotlin NPE
            val listener = object : SafeProgressListener() {
                override fun onProgressNullable(token: String?): Boolean {
                    // 务必返回 false 让引擎继续生成
                    if (token.isNullOrEmpty()) return false

                    ttsCollector.push(token) { sentence ->
                        if (sentence.isNotBlank()) {
                            runOnUiThread { tv.append(sentence) }
                            ttsSpeaker.speak(sentence)
                        }
                    }
                    return false
                }

                override fun onComplete() {
                    ttsCollector.flush { sentence ->
                        if (sentence.isNotBlank()) {
                            runOnUiThread { tv.append(sentence) }
                            ttsSpeaker.speak(sentence)
                        }
                    }
                    runOnUiThread { append("\n\n[Complete]\n") }
                }

                override fun onError(message: String) {
                    runOnUiThread { append("\n[Error] $message\n") }
                }
            }

            // 发起一次演示对话
            try {
                val messages = listOf(
                    "system" to "You are a helpful assistant.",
                    "user" to "用一句话介绍你自己，然后再简短问候一下我。"
                )
                session!!.generateFromMessages(messages, listener)
            } catch (e: Throwable) {
                runOnUiThread { append("\n[Exception] ${e.message}\n") }
            }
        }
    }

    private fun append(s: String) {
        tv.append(s)
        root.post { root.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.release()
        ttsSpeaker.release()
        // 如需彻底释放：TtsEngine.release()
    }
}

/** 过滤 <think>…</think> 并按句末符切片给 TTS 播报 */
private class StreamingTtsCollector {
    private val sb = StringBuilder()
    private var muteThink = false
    private val boundary = Regex("[。！？!?；;~～…\\n]")

    fun push(raw: String?, onSentence: (String) -> Unit) {
        if (raw.isNullOrEmpty()) return
        var token = sanitizeThink(raw)
        if (token.isEmpty()) return
        if (sb.isEmpty()) token = token.trimStart()
        sb.append(token)

        var text = sb.toString()
        while (true) {
            val m = boundary.find(text) ?: break
            val end = m.range.last + 1
            val sentence = text.substring(0, end).trim()
            if (sentence.isNotEmpty()) onSentence(sentence)
            text = text.substring(end)
        }
        sb.clear()
        sb.append(text)
    }

    fun flush(onSentence: (String) -> Unit) {
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) onSentence(tail)
        sb.clear()
    }

    private fun sanitizeThink(input: String): String {
        var s = input
        if (muteThink) {
            val end = s.indexOf("</think>")
            if (end >= 0) {
                muteThink = false
                s = s.substring(end + "</think>".length)
            } else return ""
        }
        while (true) {
            val start = s.indexOf("<think>")
            if (start < 0) break
            val end = s.indexOf("</think>", start)
            s = if (end >= 0) {
                s.removeRange(start, end + "</think>".length)
            } else {
                muteThink = true
                s.substring(0, start)
            }
        }
        return s
    }
}

/** 简易串行 TTS 播放器 + 音频焦点申请 */
private class TtsSpeaker(ctx: Context) {
    private val queue = LinkedBlockingQueue<String>()
    private val workerStarted = AtomicBoolean(false)
    @Volatile private var running = true

    private val audioManager =
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { /* no-op */ }
                .build()
        } else null

    fun speak(sentence: String) {
        if (sentence.isBlank()) return
        queue.offer(sentence)
        ensureWorker()
    }

    private fun ensureWorker() {
        if (workerStarted.compareAndSet(false, true)) {
            thread(name = "TtsSpeaker") {
                try {
                    // 等 TTS 初始化
                    while (running && !TtsEngine.isInitialized()) Thread.sleep(30)

                    // 申请音频焦点
                    requestFocus()

                    while (running) {
                        val s = queue.take()
                        if (s.isNotBlank()) {
                            try {
                                // 第二个参数 true：允许内部做轻度分句
                                TtsEngine.speak(s, true)
                            } catch (_: Throwable) {
                                // 忽略单句失败，继续下一句
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                } finally {
                    abandonFocus()
                }
            }
        }
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Throwable) {}
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
    }

    fun release() {
        running = false
        abandonFocus()
    }
}

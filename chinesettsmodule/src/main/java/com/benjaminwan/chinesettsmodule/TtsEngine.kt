package com.benjaminwan.chinesettsmodule

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.benjaminwan.chinesettsmodule.tts.FastSpeech2
import com.benjaminwan.chinesettsmodule.tts.MBMelGan
import com.benjaminwan.chinesettsmodule.utils.ZhProcessor
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * 纯功能 TTS 引擎（无 UI）
 * - 单工作线程串行合成+播放，避免并发崩溃
 * - speak(text, ...): 将“最新一句”送入队列（覆盖旧的），并中断当前播放
 * - setSpeedAlpha(alpha): 控制 FS2 的时长系数（越小越快）
 */
object TtsEngine {
    private const val SAMPLE_RATE = 24000

    private val isReady = AtomicBoolean(false)
    private var fs2: FastSpeech2? = null
    private var melgan: MBMelGan? = null
    private var zh: ZhProcessor? = null

    /** 发音时长系数：FastSpeech2 的 duration 参数（越小越快）。建议范围 [0.40, 1.20]，默认 0.70 */
    @Volatile private var speedAlpha: Float = 0.70f

    // ====== 队列 & 线程控制：单槽 + 串行播放 ======
    /** 只保留"最新一句"，容量=1。每次 speak() 会清空并放入最新文本。 */
    private val speakQueue = LinkedBlockingDeque<String>(1)
    /** 正在播放时，如果来了新 speak，会先打断当前播放。 */
    private val abortPlay = AtomicBoolean(false)
    /** 工作循环开关 */
    private val workerStarted = AtomicBoolean(false)
    private val stopWorker = AtomicBoolean(false)

    @JvmStatic
    fun init(context: Context) {
        if (isReady.get()) return
        thread(name = "TtsInit") {
            try {
                val (fs2File, melFile) = ModelFiles.ensureCopied(context)
                zh = ZhProcessor(context)
                fs2 = FastSpeech2(fs2File)
                melgan = MBMelGan(melFile)
                isReady.set(true)
            } catch (e: Throwable) {
                e.printStackTrace()
                isReady.set(false)
            } finally {
                ensureWorker()
            }
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        if (!workerStarted.compareAndSet(false, true)) return
        thread(name = "TtsWorker", isDaemon = true) {
            while (!stopWorker.get()) {
                try {
                    val text = speakQueue.take() // 阻塞等一句
                    // 等待就绪（init 还没完成时）
                    while (!isReady.get() && !stopWorker.get()) {
                        Thread.sleep(30)
                    }
                    if (stopWorker.get()) break
                    doSpeak(text)
                } catch (_: InterruptedException) {
                    // ignore, loop
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    @JvmStatic
    fun isInitialized(): Boolean = isReady.get()

    /** 设置发音时长系数（越小越快） */
    @JvmStatic
    fun setSpeedAlpha(alpha: Float) {
        speedAlpha = alpha.coerceIn(0.40f, 1.20f)
    }

    /**
     * 对外 speak：
     * - 清空队列并放入“最新一句”
     * - 置位中断，打断当前播放
     * - 由单线程 worker 串行处理，避免并发崩溃
     */
    @JvmStatic
    fun speak(text: String, preferFastSpeech2: Boolean) {
        if (text.isBlank()) return
        // 即使 init 尚未完成，也先把指令放入队列，worker 就绪后会播
        abortPlay.set(true)          // 请求中断当前播放
        speakQueue.clear()           // 清空旧的排队
        speakQueue.offer(text)       // 只保留最新一句
    }

    @JvmStatic
    fun release() {
        stopWorker.set(true)
        abortPlay.set(true)
        speakQueue.clear()
        fs2 = null
        melgan = null
        zh = null
        isReady.set(false)
    }

    // ====== 实际合成+播放（在单线程中执行） ======
    private fun doSpeak(text: String) {
        try {
            abortPlay.set(false) // 新一轮开始前，清除中断标记

            val sentences = text.split(Regex("[\n，。？！!?；;]"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for (s in sentences) {
                if (abortPlay.get() || stopWorker.get()) break

                val ids: IntArray = zh!!.text2ids(s)

                // 生成 mel（期间也检查中断，尽快放弃这一句）
                if (abortPlay.get() || stopWorker.get()) break
                val mel: TensorBuffer = fs2!!.getMelSpectrogram(ids, speedAlpha) ?: continue

                // 生成音频
                if (abortPlay.get() || stopWorker.get()) break
                val audioF: FloatArray = melgan!!.getAudio(mel) ?: continue

                // float [-1,1] -> PCM16
                val pcm = FloatArray(audioF.size) { i -> audioF[i].coerceIn(-1f, 1f) }
                val shorts = ShortArray(pcm.size) { i -> (pcm[i] * 32767).toInt().toShort() }

                // 播放（可被中断）
                playPcm16(shorts, SAMPLE_RATE)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun playPcm16(data: ShortArray, sampleRate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer, min(data.size * 2, 256 * 1024))

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        try {
            track.play()

            var offset = 0
            val totalFrames = data.size
            val chunkFrames = bufferSize / 2 // short 个数

            while (offset < totalFrames && !abortPlay.get() && !stopWorker.get()) {
                val toWrite = min(chunkFrames, totalFrames - offset)
                val written = track.write(data, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }

            // 如果不是中断，等内部缓冲播完一点点
            if (!abortPlay.get() && !stopWorker.get()) {
                val timeoutMs = (totalFrames * 1000L / sampleRate) + 200L
                val start = System.currentTimeMillis()
                while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < totalFrames &&
                    System.currentTimeMillis() - start < timeoutMs &&
                    !abortPlay.get() && !stopWorker.get()) {
                    Thread.sleep(10)
                }
            }

            // 如果被中断，直接 stop，避免残留
            try { track.stop() } catch (_: IllegalStateException) {}
        } finally {
            track.release()
        }
    }
}

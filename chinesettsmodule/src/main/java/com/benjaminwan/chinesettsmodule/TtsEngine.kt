package com.benjaminwan.chinesettsmodule

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.benjaminwan.chinesettsmodule.tts.FastSpeech2
import com.benjaminwan.chinesettsmodule.tts.MBMelGan
import com.benjaminwan.chinesettsmodule.utils.ZhProcessor
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * 纯功能 TTS 引擎（无 UI）
 * - init(context): 初始化模型（异步）
 * - speak(text, preferFastSpeech2): 合成并播放
 * - isInitialized(): 是否就绪
 * - release(): 释放资源
 */
object TtsEngine {
    private const val SAMPLE_RATE = 24000   // 与模型一致
    private val isReady = AtomicBoolean(false)
    private var fs2: FastSpeech2? = null
    private var melgan: MBMelGan? = null
    private var zh: ZhProcessor? = null

    @JvmStatic
    fun init(context: Context) {
        if (isReady.get()) return
        thread(name = "TtsInit") {
            try {
                // 1) 从 assets 复制到内部目录
                val (fs2File, melFile) = ModelFiles.ensureCopied(context)

                // 2) 初始化处理器和模型
                zh = ZhProcessor(context)
                fs2 = FastSpeech2(fs2File)
                melgan = MBMelGan(melFile)

                isReady.set(true)
            } catch (e: Throwable) {
                e.printStackTrace()
                isReady.set(false)
            }
        }
    }

    @JvmStatic
    fun isInitialized(): Boolean = isReady.get()

    /**
     * 为了兼容你当前反射签名，这里保留 preferFastSpeech2 参数（实际固定用 FS2）。
     */
    @JvmStatic
    fun speak(text: String, preferFastSpeech2: Boolean) {
        if (!isReady.get()) return
        if (text.isBlank()) return

        thread(name = "TtsSpeak") {
            try {
                val sentences = text.split(Regex("[\n，。？！!?；;]"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                for (s in sentences) {
                    val ids: IntArray = zh!!.text2ids(s)
                    val mel: TensorBuffer = fs2!!.getMelSpectrogram(ids, 1.0f) ?: continue
                    val audioF: FloatArray = melgan!!.getAudio(mel) ?: continue

                    // float [-1,1] -> PCM16
                    val pcm = FloatArray(audioF.size) { i -> audioF[i].coerceIn(-1f, 1f) }
                    val shorts = ShortArray(pcm.size) { i -> (pcm[i] * 32767).toInt().toShort() }

                    playPcm16(shorts, SAMPLE_RATE)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    fun release() {
        fs2 = null
        melgan = null
        zh = null
        isReady.set(false)
    }

    private fun playPcm16(data: ShortArray, sampleRate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 稳妥的缓冲：至少 minBuffer，最多 256KB
        val bufferSize = max(minBuffer, min(data.size * 2, 256 * 1024))

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)          // 保持不变
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

            while (offset < totalFrames) {
                val toWrite = min(chunkFrames, totalFrames - offset)
                // 显式阻塞写，直到数据进入内部缓冲
                val written = track.write(data, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }

            // 等待把缓冲区“放干净”再停（否则会被你 stop/flush 掐掉）
            val timeoutMs = (totalFrames * 1000L / sampleRate) + 500L // 音频时长 + 余量
            val start = System.currentTimeMillis()
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < totalFrames &&
                System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(20)
            }

            // 不要 flush（会丢弃未播完数据）
            try { track.stop() } catch (_: IllegalStateException) {}
        } finally {
            track.release()
        }
    }

}

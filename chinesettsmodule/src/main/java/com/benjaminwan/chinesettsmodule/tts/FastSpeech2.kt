package com.benjaminwan.chinesettsmodule.tts

import com.orhanobut.logger.Logger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate   // ✅ 新增
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.nio.FloatBuffer
import java.util.*

class FastSpeech2(file: File) : BaseInference(file) {

    // ✅ 关键：不用父类的 options，自己创建；关闭 XNNPACK，添加 FlexDelegate
    override val interpreter: Interpreter = Interpreter(
        file,
        Interpreter.Options().apply {
            setUseXNNPACK(false)                 // 关掉 XNNPACK，避免与 Flex 冲突和崩溃
            setNumThreads(4)
            addDelegate(FlexDelegate())          // 使用 select-tf-ops
        }
    )

    init {
        printTensorInfo()
    }

    fun getMelSpectrogram(inputIds: IntArray, speed: Float): TensorBuffer {
        interpreter.resizeInput(0, intArrayOf(1, inputIds.size))
        interpreter.allocateTensors()

        val outputMap: MutableMap<Int, Any> = HashMap()
        val outputBuffer = FloatBuffer.allocate(350000)
        outputMap[0] = outputBuffer

        val inputs = Array(1) { IntArray(inputIds.size) }
        inputs[0] = inputIds

        val startTime = System.currentTimeMillis()
        interpreter.runForMultipleInputsOutputs(
            arrayOf<Any>(
                inputs,
                intArrayOf(0),          // speaker
                floatArrayOf(speed),    // speed
                floatArrayOf(1f),       // energy
                floatArrayOf(1f)        // pitch
            ),
            outputMap
        )
        // Logger.i("FastSpeech2 time cost: ${System.currentTimeMillis() - startTime}")

        val size: Int = interpreter.getOutputTensor(0).shape()[2]
        val shape = intArrayOf(1, outputBuffer.position() / size, size)
        val spectrogram = TensorBuffer.createFixedSize(shape, DataType.FLOAT32)
        val outputArray = FloatArray(outputBuffer.position())
        outputBuffer.rewind()
        outputBuffer.get(outputArray)
        spectrogram.loadArray(outputArray)
        return spectrogram
    }
}

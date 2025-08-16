package com.benjaminwan.chinesettsmodule.tts

import com.orhanobut.logger.Logger
import org.tensorflow.lite.Interpreter
import java.io.File

abstract class BaseInference(file: File) {
    // 供子类用的 Interpreter 配置
    protected val options: Interpreter.Options = Interpreter.Options().apply {
        // 线程数按 CPU 核心数的一半（>=1）
        numThreads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
        // 如需可开启 NNAPI：
        // setUseNNAPI(true)
    }

    // 让子类持有具体 interpreter
    abstract val interpreter: Interpreter

    init {
        Logger.i("Load tflite: ${file.absolutePath}")
    }

    // 打印输入/输出张量信息，便于调试
    protected fun printTensorInfo() {
        try {
            for (i in 0 until interpreter.inputTensorCount) {
                val t = interpreter.getInputTensor(i)
                Logger.i("Input[$i] name=${t.name()}, shape=${t.shape().contentToString()}, dtype=${t.dataType()}")
            }
            for (i in 0 until interpreter.outputTensorCount) {
                val t = interpreter.getOutputTensor(i)
                Logger.i("Output[$i] name=${t.name()}, shape=${t.shape().contentToString()}, dtype=${t.dataType()}")
            }
        } catch (e: Throwable) {
            Logger.e("printTensorInfo error: $e")
        }
    }
}

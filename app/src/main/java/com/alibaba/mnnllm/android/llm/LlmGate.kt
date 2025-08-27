package com.alibaba.mnnllm.android.llm

import java.util.concurrent.atomic.AtomicBoolean

/** 简单闸门：LLM 忙时为 true，空闲为 false。Java 友好（@JvmStatic）。 */
object LlmGate {
    private val busy = AtomicBoolean(false)

    @JvmStatic fun setBusy(b: Boolean) { busy.set(b) }
    @JvmStatic fun isBusy(): Boolean = busy.get()
}

package com.example.mnnllmdemo.llm

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.alibaba.mnnllm.android.llm.LlmGate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

object DetResultReporter {
    private const val TAG = "LLM"

    // ====== 批量阈值（按 YOLO tick 计数） ======
    @Volatile private var batchSize: Int = 11
    @JvmStatic fun setBatchSize(value: Int) { batchSize = value.coerceIn(1, 50) }
    @JvmStatic fun setBatchSizeFromInterval(intervalMs: Int) {
        // 建议：约 3000ms 聚合一次
        val mapped = kotlin.math.max(1, Math.round(3000f / intervalMs))
        setBatchSize(mapped)
    }

    // ====== 空播抑制策略 ======
    enum class EmptyPolicy { EDGE_ONLY, COOLDOWN }
    @Volatile private var emptyPolicy: EmptyPolicy = EmptyPolicy.EDGE_ONLY
    @Volatile private var EMPTY_COOLDOWN_MS = 6000L
    @Volatile private var lastSentWasEmpty: Boolean = false
    @Volatile private var lastSentEmptyTs: Long = 0L

    // ====== YOLO 规格（用于归一） ======
    private const val NORM_W = 512f
    private const val NORM_H = 288f

    // ====== 筛选阈值 ======
    private const val CONF_THRES = 0.45f
    private const val MIN_BOX_AREA_FRAC = 0.002f
    private const val REL_INPUT_MIN = -0.01f
    private const val REL_INPUT_MAX = 0.985f
    private const val LATERAL_AREA_MIN_FRAC_FOR_INPUT = 0.01f
    private const val LATERAL_DIST_MAX_FOR_INPUT = 0.90f
    private const val EDGE_X_LEFT_FRAC = 0.15f
    private const val EDGE_X_RIGHT_FRAC = 0.85f
    private const val EDGE_SMALL_AREA_MAX_FRAC = 0.05f
    private const val TOP_K_FOR_INPUT = 5
    private const val NEAR_VIB_THRESHOLD = 0.4f
    private const val NEAR_VIB_COOLDOWN_MS = 600L
    @Volatile private var lastNearVibTs: Long = 0L

    // ====== 线程 / 状态 ======
    @Volatile private var appCtx: Context? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-worker").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
    private val llmBusy = AtomicBoolean(false)
    @Volatile private var pendingBatch: List<String>? = null

    // ====== 震动目标 ======
    @JvmField val vibrateTargets: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    private const val INSTR_PREFIX =
        "你是小智，一个导盲播报助手。阅读输入的场景要素（格式：“物体，方位，距离0.xx；…”），" +
                "输出一句不超过30字的中文导航提示，直接给可执行建议，不要解释或展示思考过程。"
    private fun wrapForLLM(payload: String): String {
        val t = payload.trim()
        return if (t.startsWith("你是小智")) t else INSTR_PREFIX + "\n" + t
    }

    // ====== 去重缓存 ======
    private val lock = Any()
    private data class Item(val label: String, val score: Float, val value: Float, val raw: String, val frameId: Long)
    private data class Header(val label: String, val score: Float, val value: Float, val raw: String, val frameId: Long)
    private val bufByLabel = LinkedHashMap<String, Item>()
    private var pendingHeader: Header? = null
    private var offerCount: Int = 0            // ← 现在表示“YOLO tick 计数” // NEW
    private var currentFrameId: Long = 0L

    // ====== 外部接口 ======
    @JvmStatic fun init(context: Context) {
        appCtx = context.applicationContext
        Log.d(TAG, "DetResultReporter.init()")
    }

    /** 只推进帧号（可选） */
    @JvmStatic fun onFrame(@Suppress("UNUSED_PARAMETER") hasObject: Boolean) {
        currentFrameId++
        if (currentFrameId == Long.MAX_VALUE) currentFrameId = 0L
    }

    /** 结构化输入（若上游已拼 header|bbox，建议直接 offerRaw） */
    @JvmStatic fun offer(label: String, score: Float, value: Float) {
        if (label == "__EMPTY__") return
        vibrateIfNear(value)
        val header = Header(
            label = label,
            score = score,
            value = value,
            raw = "$label,${"%.2f".format(Locale.US, score)},${"%.2f".format(Locale.US, value)}",
            frameId = currentFrameId
        )
        offerHeader(header)
    }

    /** 兼容 header|bbox / 单独 header / 单独 bbox / __EMPTY__ */
    @JvmStatic fun offerRaw(line: String) {
        val t = line.trim()
        if (t.isEmpty()) return

        // —— 空帧行：现在不再在这里计数；只作为“本 tick 无目标”的占位可忽略 —— // NEW
        if (t == "__EMPTY__" || t.startsWith("__EMPTY__")) {
            Log.d(TAG, "offerRaw: EMPTY line (no-op; counting happens in commitTick)")
            return
        }

        // 同行 header|bbox
        if ('|' in t) {
            val headerPart = t.substringBefore('|').trim()
            val bboxPart = t.substringAfter('|').trim()
            val h = tryParseHeader(headerPart)
            val q = parseBbox(bboxPart)
            if (h != null && q != null) {
                synchronized(lock) { putDedup(h.label, h.score, h.value, "${h.raw} | $bboxPart", currentFrameId) }
                return
            }
            if (h != null) offerHeader(h)
            if (bboxPart.lowercase(Locale.US).startsWith("left")) offerBboxLine(bboxPart)
            return
        }

        // 单独 bbox 行
        if (t.startsWith("Left:", ignoreCase = true)) { offerBboxLine(t); return }

        // 单独 header 或裸 label
        val h = tryParseHeader(t)
        if (h != null) offerHeader(h.copy(frameId = currentFrameId))
        else pendingHeader = Header(t, 0f, Float.POSITIVE_INFINITY, t, currentFrameId)
    }

    /** 手动冲洗：立即把现有去重池内容发出去（仍走同一出口），不影响当前 tick 计数 */
    @JvmStatic fun flushNow(context: Context? = null) {
        if (context != null) appCtx = context.applicationContext
        val snapshot: List<String>
        synchronized(lock) {
            pendingHeader = null
            snapshot = if (bufByLabel.isEmpty()) emptyList() else snapshotAndClear()
        }
        sendBatch(snapshot)
    }

    // ====== 关键新增：每次 YOLO 完成后调用一次，只在这里 +1 计数并判断是否触发 ====== //
    @JvmStatic fun commitTick() { // NEW
        var ready: List<String>? = null
        synchronized(lock) {
            // 若还有挂起 header，这里不再强制入库（GLRender 已发送完整行，不会走到这步）
            offerCount++
            Log.d(TAG, "commitTick: tick=${offerCount}/${batchSize}, unique_pool=${bufByLabel.size}")
            if (offerCount >= batchSize) {
                ready = if (bufByLabel.isEmpty()) emptyList() else snapshotAndClear()
                offerCount = 0
            }
        }
        if (ready != null) sendBatch(ready!!)
    }

    // ====== 入库（去重） ======
    private fun offerHeader(h: Header) {
        synchronized(lock) {
            if (h.raw.contains("Left:", ignoreCase = true)) {
                putDedup(h.label, h.score, h.value, h.raw, h.frameId)
            } else {
                pendingHeader?.let { putDedup(it.label, it.score, it.value, it.raw, it.frameId) }
                pendingHeader = h
                Log.d(TAG, "offerHeader: pending='${h.raw}' waiting bbox, frame=${h.frameId}")
            }
        }
    }

    private fun offerBboxLine(bbox: String) {
        synchronized(lock) {
            val h = pendingHeader
            if (h != null) {
                val mergedRaw = "${h.raw} | $bbox"
                putDedup(h.label, h.score, h.value, mergedRaw, h.frameId)
                pendingHeader = null
            } else {
                Log.w(TAG, "offerBboxLine: got bbox but no pending header, drop.")
            }
        }
    }

    /** 跨帧覆盖；同帧保高分；命中 vibrateTargets 震动。**不再在这里加计数**。 */ // NEW
    private fun putDedup(label: String, score: Float, value: Float, raw: String, frameId: Long) {
        val old = bufByLabel[label]
        val replace = when {
            old == null -> true
            frameId > old.frameId -> true
            frameId == old.frameId -> score >= old.score
            else -> false
        }
        if (replace) {
            bufByLabel[label] = Item(label, score, value, raw, frameId)
            val needVibrate = (old == null || frameId > (old?.frameId ?: -1))
            if (needVibrate && label in vibrateTargets) vibrateNow()
            Log.d(TAG, "putDedup: UPSERT label='$label' frame=$frameId")
        } else {
            Log.d(TAG, "putDedup: KEEP   label='$label' keep.frame=${old?.frameId} drop.frame=$frameId")
        }
    }

    private fun snapshotAndClear(): List<String> {
        val lines = bufByLabel.values.map { it.raw }
        Log.d(TAG, "snapshotAndClear: batch_size=${lines.size}, will send & clear.")
        bufByLabel.clear()
        return lines
    }

    // ====== 发送（唯一出口） ======
    private fun sendBatch(lines: List<String>) {
        val ctx = appCtx
        if (ctx == null) {
            Log.w(TAG, "sendBatch: appCtx is null, did you call DetResultReporter.init(context)?")
        } else {
            LlmSimpleClient.init(ctx)
        }

        val content = buildPromptWithFiltering(lines)
        val isEmptyPrompt = content.trim() == "未检测到障碍"

        // ✅ 不再按 emptyPolicy 丢弃空旷；达到批次就发
        // 仅在 LLM 忙时仍丢弃空旷，避免排队全是“空”的占用
        if (!llmBusy.compareAndSet(false, true)) {
            if (isEmptyPrompt) {
                Log.d(TAG, "sendBatch: llmBusy, drop EMPTY while busy")
                return
            }
            pendingBatch = lines // 非空：只保留最新一批
            Log.w(TAG, "sendBatch: llmBusy=true, replace pending batch (unique_size=${lines.size})")
            return
        }

        val prompt = wrapForLLM(content)
        executor.execute {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
            Log.d(TAG, "Trigger LLM batch: unique_size=${lines.size}")
            LlmGate.setBusy(true)
            try {
                LlmSimpleClient.ask(prompt)
            } catch (t: Throwable) {
                Log.e(TAG, "LLM call failed: ${t.message}", t)
            } finally {
                LlmGate.setBusy(false)
                llmBusy.set(false)
                pendingBatch?.let {
                    pendingBatch = null
                    sendBatch(it) // 递归发下一批
                }
            }
        }
    }

    // ====== 解析 + 筛选 ======
    private data class ParsedDet(
        val label: String, val score: Float, val value: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float
    )
    private data class Feat(val p: ParsedDet, val posToken: String, val areaFrac: Float, val cxNorm: Float)

    private fun buildPromptWithFiltering(uniqueLines: List<String>): String {
        val EN2ZH = mapOf(
            "bench" to "长椅","bike" to "自行车","car" to "汽车","chair" to "椅子","curb" to "路缘","door" to "门",
            "downstair" to "下行楼梯","dustbin" to "垃圾桶","elevator" to "电梯","escalator" to "自动扶梯",
            "fire hydrant" to "消火栓","gate" to "闸机","handrail" to "扶手","left-turn tactile paving" to "左转盲道",
            "person" to "行人","pole" to "杆柱","right-turn tactile paving" to "右转盲道","road bench" to "路边平台",
            "road block" to "路障","speed bump" to "减速带","steel barrier" to "护栏","stone block" to "石墩",
            "stop tactile paving" to "提示盲道","straight tactile paving" to "直行盲道","subway" to "地铁",
            "traffic light" to "红绿灯","tree" to "树","upslope" to "上坡","upstair" to "上行楼梯","zebra crossing" to "斑马线"
        )
        fun looksChinese(s: String) = s.any { it in '\u4e00'..'\u9fff' }
        fun toZhLabel(label: String): String {
            if (looksChinese(label)) return label
            val key = label.trim().lowercase(Locale.US)
            return EN2ZH[key] ?: label
        }

        val parsed = uniqueLines.mapNotNull { parseDet(it) }
        val s1 = parsed.filter { it.score >= CONF_THRES }
        val s2 = s1.filter { areaFrac(it) >= MIN_BOX_AREA_FRAC }
        val s3 = s2.filter { it.value.isFinite() && it.value in REL_INPUT_MIN..REL_INPUT_MAX }

        val feats = s3.map { d ->
            val cx = (d.left + d.right) * 0.5f
            val cxNorm = clamp01(cx / NORM_W)
            Feat(
                p = d,
                posToken = when {
                    cxNorm < 0.2f -> "LS"
                    cxNorm < 0.4f -> "LF"
                    cxNorm < 0.6f -> "F"
                    cxNorm < 0.8f -> "RF"
                    else -> "RS"
                },
                areaFrac = areaFrac(d),
                cxNorm = cxNorm
            )
        }

        val s6 = feats.filter { f ->
            when (f.posToken) {
                "LF", "RF", "LS", "RS" ->
                    !(f.areaFrac <= LATERAL_AREA_MIN_FRAC_FOR_INPUT || f.p.value > LATERAL_DIST_MAX_FOR_INPUT)
                else -> true
            }
        }

        val s7 = s6.filter { f ->
            val inEdge = (f.cxNorm < EDGE_X_LEFT_FRAC) || (f.cxNorm > EDGE_X_RIGHT_FRAC)
            !(inEdge && f.areaFrac < EDGE_SMALL_AREA_MAX_FRAC)
        }

        val topK = s7.sortedWith(compareBy<Feat> { it.p.value }.thenByDescending { it.p.score })
            .take(TOP_K_FOR_INPUT)

        val parts = topK.map { f ->
            val nameZh = toZhLabel(f.p.label)
            val orientZh = zhFromPosToken(f.posToken)
            val vStr = "%.2f".format(Locale.US, f.p.value)
            "$nameZh，$orientZh，距离$vStr"
        }

        return if (parts.isEmpty()) "未检测到障碍" else parts.joinToString("；")
    }

    private fun parseDet(raw: String): ParsedDet? {
        val headerStr = raw.substringBefore('|').trim()
        val bboxStr = raw.substringAfter('|', "").trim()
        val h = tryParseHeader(headerStr) ?: return null
        val q = parseBbox(bboxStr) ?: return null
        val (l, t, r, b) = q
        return ParsedDet(h.label, h.score, h.value, l, t, r, b)
    }

    // ====== 工具 ======
    private fun areaFrac(d: ParsedDet): Float {
        val w = max(0f, d.right - d.left)
        val h = max(0f, d.bottom - d.top)
        return (w * h) / (NORM_W * NORM_H + 1e-6f)
    }
    private fun clamp01(v: Float): Float = min(1f, max(0f, v))
    private fun zhFromPosToken(t: String): String = when (t) {
        "LS" -> "左侧"; "LF" -> "左前方"; "F" -> "前方"; "RF" -> "右前方"; "RS" -> "右侧"; else -> "前方"
    }
    private data class Quad<A,B,C,D>(val a: A, val b: B, val c: C, val d: D)

    private fun tryParseHeader(line: String): Header? {
        val main = line.substringBefore('|').trim()
        run {
            val parts = main.split('/').map { it.trim() }
            if (parts.size >= 3) {
                val label = parts[0]
                val score = parseScoreToken(parts[1]) ?: return@run
                val value = parseFloatToken(parts[2]) ?: return@run
                if (label.isNotEmpty()) return Header(label, score, value, main, currentFrameId)
            }
        }
        run {
            val csv = main.split(',').map { it.trim() }
            if (csv.size >= 3) {
                val label = csv[0]
                val score = parseScoreToken(csv[1]) ?: return@run
                val value = parseFloatToken(csv[2]) ?: return@run
                if (label.isNotEmpty()) return Header(label, score, value, main, currentFrameId)
            }
        }
        return null
    }
    private val NUM_RE = Regex("""[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?""")
    private fun parseFloatToken(token: String): Float? = NUM_RE.find(token)?.value?.toFloatOrNull()
    private fun parseScoreToken(token: String): Float? {
        val s = token.trim()
        return if (s.contains('%')) parseFloatToken(s)?.div(100f) else parseFloatToken(s)
    }

    private val BBOX_RE = Regex(
        """Left:\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*Top:\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*Right:\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*,\s*Bottom:\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)""",
        RegexOption.IGNORE_CASE
    )
    private fun parseBbox(s: String): Quad<Float, Float, Float, Float>? {
        val m = BBOX_RE.find(s.trim()) ?: return null
        val (l, t, r, b) = m.groupValues.drop(1).map { it.toFloat() }
        return Quad(l, t, r, b)
    }

    // ====== 震动 ======
    private fun vibrateIfNear(value: Float) {
        if (!value.isFinite() || value >= NEAR_VIB_THRESHOLD) return
        val now = System.currentTimeMillis()
        if (now - lastNearVibTs < NEAR_VIB_COOLDOWN_MS) return
        lastNearVibTs = now
        vibrateNow()
    }
    private fun vibrateNow() {
        val ctx = appCtx ?: return
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed: ${t.message}", t)
        }
    }
}
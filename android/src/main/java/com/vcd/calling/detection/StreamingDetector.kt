package com.vcd.calling.detection

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Model-neutral timestamps are seconds relative to the current input window. Scores are not probabilities. */
data class FrameScore(val startS: Double, val endS: Double, val fakeScore: Double, val speech: Boolean)
data class ModelOutput(val frames: List<FrameScore>)
interface DetectorAdapter {
    val windowSamples: Int
    val minimumSamples: Int
    /** Chosen and validated for this adapter, never inherited from a previous model. */
    val syntheticThreshold: Double
    fun score(mono16k: FloatArray): ModelOutput
}
enum class Evidence { NO_MODEL, INSUFFICIENT_SPEECH, PENDING, READY, OVERLOADED, ERROR }
data class ScoredBin(
    val startS: Double, val endS: Double, val rawScore: Double?, val speechS: Double,
    val suspicious: Boolean?, val evidence: Evidence,
)
data class Segment(val startS: Double, val endS: Double)
data class StreamUpdate(
    val audioTimeS: Double, val evidence: Evidence, val smoothedScore: Double?,
    val bins: List<ScoredBin>, val segments: List<Segment>, val resultAgeMs: Long?,
    val inferenceMs: Long?, val skipped: Int, val scoredThroughS: Double?, val error: String? = null,
)

/**
 * Feed ordered 16 kHz PCM. A status update is emitted each 8000 samples (500 ms of received audio).
 * Exactly one inference can run; busy hops are skipped instead of queued. Results complete separately.
 * A UI timer may repeat the latest status on stalled audio, but must not invent a new scored audio bin.
 * Frame/VAD evidence comes from the adapter; loudness is never used as a fake/real classifier.
 */
class StreamingDetector(
    private val adapter: DetectorAdapter? = null,
    private val minimumSpeechS: Double = 1.5,
    private val minimumBinSpeechS: Double = 0.15,
    private val emaAlpha: Double = 0.25,
    private val maxHistoryBins: Int = 120,
    executor: Executor? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onUpdate: (StreamUpdate) -> Unit,
    private val onResult: (StreamUpdate) -> Unit = {},
) : Closeable {
    private val worker: Executor = executor ?: Executors.newSingleThreadExecutor()
    private val ownsWorker = executor == null
    private val busy = AtomicBoolean(false)
    private val capacity = adapter?.windowSamples ?: 64000
    private val ring = FloatArray(capacity)
    private var write = 0
    private var size = 0
    private var total = 0L
    private var nextHop = 8000L
    private var closed = false
    private var skipped = 0
    private var ema: Double? = null
    private var resultCapturedAt: Long? = null
    private var scoredThrough: Double? = null
    private var lastLatency: Long? = null
    private var lastError: String? = null
    private val history = sortedMapOf<Long, ScoredBin>()

    init {
        require(capacity >= 8000 && minimumSpeechS >= 0 && minimumBinSpeechS in 0.0..0.5)
        require(emaAlpha > 0 && emaAlpha <= 1 && maxHistoryBins > 0)
        adapter?.let {
            require(it.minimumSamples in 1..capacity)
            require(it.syntheticThreshold.isFinite() && it.syntheticThreshold in 0.0..1.0)
        }
    }

    @Synchronized fun push(samples: FloatArray) {
        if (closed) return
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Expected finite mono PCM in [-1,1]" }
        for (sample in samples) {
            ring[write] = sample; write = (write + 1) % capacity
            size = min(capacity, size + 1); total++
            if (total == nextHop) { hop(); nextHop += 8000 }
        }
    }
    private fun snapshot(): FloatArray = FloatArray(size) { ring[(write - size + capacity + it) % capacity] }
    private fun hop() {
        val index = total / 8000 - 1
        var state = when {
            adapter == null -> Evidence.NO_MODEL
            size < adapter.minimumSamples -> Evidence.INSUFFICIENT_SPEECH
            busy.get() -> Evidence.OVERLOADED
            else -> Evidence.PENDING
        }
        if (state == Evidence.OVERLOADED) skipped++
        history.putIfAbsent(index, ScoredBin(index * .5, (index + 1) * .5, null, 0.0, null, state))
        trim()
        onUpdate(update(state))
        val model = adapter ?: return
        if (state != Evidence.PENDING || !busy.compareAndSet(false, true)) return
        val audio = snapshot(); val endS = total / 16000.0
        val startS = endS - audio.size / 16000.0
        val begin = clockMs()
        try {
            worker.execute {
                try {
                    val result = model.score(audio)
                    val bins = localize(result.frames, startS, endS, model.syntheticThreshold)
                    synchronized(this) {
                        if (!closed) {
                            val speechS = bins.sumOf { it.speechS }
                            val sufficient = speechS >= minimumSpeechS
                            for (bin in bins) {
                                val key = kotlin.math.round(bin.startS * 2).toLong()
                                // A half-second bin is finalized once; later overlapping windows do not rewrite history.
                                if (history[key]?.evidence != Evidence.READY) {
                                    history[key] = if (sufficient) bin else bin.copy(rawScore = null, suspicious = null, evidence = Evidence.INSUFFICIENT_SPEECH)
                                }
                            }
                            trim()
                            val latest = bins.lastOrNull()
                            if (sufficient && latest?.rawScore != null) {
                                ema = ema?.let { it + emaAlpha * (latest.rawScore - it) } ?: latest.rawScore
                            } else ema = null
                            resultCapturedAt = begin; scoredThrough = endS; lastLatency = max(0, clockMs() - begin); lastError = null
                            onResult(update(if (sufficient && latest?.evidence == Evidence.READY) Evidence.READY else Evidence.INSUFFICIENT_SPEECH))
                        }
                    }
                } catch (e: Exception) {
                    synchronized(this) {
                        if (!closed) { lastError = e.message ?: "Inference failed"; ema = null; onResult(update(Evidence.ERROR)) }
                    }
                } finally { busy.set(false) }
            }
        } catch (e: Exception) {
            busy.set(false); lastError = e.message; onResult(update(Evidence.ERROR))
        }
    }
    private fun localize(frames: List<FrameScore>, startS: Double, endS: Double, threshold: Double): List<ScoredBin> {
        val duration = endS - startS
        var previousEnd = 0.0
        for (f in frames) {
            require(f.startS.isFinite() && f.endS.isFinite() && f.fakeScore.isFinite())
            require(f.startS >= previousEnd - 1e-6 && f.endS > f.startS && f.endS <= duration + 1e-5)
            require(f.fakeScore in 0.0..1.0)
            previousEnd = f.endS
        }
        val first = kotlin.math.ceil(startS * 2 - 1e-6).toLong()
        val last = kotlin.math.round(endS * 2).toLong() - 1
        return (first..last).map { i ->
            val lo = i * .5; val hi = lo + .5
            var sum = 0.0; var speech = 0.0
            for (f in frames) if (f.speech) {
                val overlap = max(0.0, min(hi, startS + f.endS) - max(lo, startS + f.startS))
                speech += overlap; sum += overlap * f.fakeScore
            }
            val score = if (speech >= minimumBinSpeechS && speech > 0) sum / speech else null
            ScoredBin(lo, hi, score, speech, score?.let { it >= threshold }, if (score == null) Evidence.INSUFFICIENT_SPEECH else Evidence.READY)
        }
    }
    private fun trim() { while (history.size > maxHistoryBins) history.remove(history.firstKey()) }
    private fun update(state: Evidence): StreamUpdate {
        val bins = history.values.toList()
        val segments = mutableListOf<Segment>()
        for (b in bins) if (b.suspicious == true) {
            val last = segments.lastOrNull()
            if (last != null && kotlin.math.abs(last.endS - b.startS) < 1e-6) segments[segments.lastIndex] = last.copy(endS = b.endS)
            else segments += Segment(b.startS, b.endS)
        }
        return StreamUpdate(total / 16000.0, state, ema, bins, segments,
            resultCapturedAt?.let { max(0, clockMs() - it) }, lastLatency, skipped, scoredThrough, lastError)
    }
    @Synchronized override fun close() {
        closed = true
        if (ownsWorker && worker is java.util.concurrent.ExecutorService) worker.shutdownNow()
    }
}

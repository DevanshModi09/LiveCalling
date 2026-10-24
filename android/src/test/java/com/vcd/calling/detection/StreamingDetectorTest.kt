package com.vcd.calling.detection

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class StreamingDetectorTest {
    private val direct = Executor { it.run() }
    private fun adapter(window: Int = 64000, minimum: Int = window, output: (FloatArray) -> List<FrameScore>) = object : DetectorAdapter {
        override val windowSamples = window
        override val minimumSamples = minimum
        override val syntheticThreshold = .7
        override fun score(mono16k: FloatArray) = ModelOutput(output(mono16k))
    }
    @Test fun noModelEmitsEveryHalfSecondWithoutInventingVerdicts() {
        val ticks = mutableListOf<StreamUpdate>()
        StreamingDetector(executor = direct, onUpdate = ticks::add).use { engine ->
            repeat(100) { engine.push(FloatArray(320)) }
        }
        assertEquals(listOf(.5, 1.0, 1.5, 2.0), ticks.map { it.audioTimeS })
        assertTrue(ticks.all { it.evidence == Evidence.NO_MODEL && it.smoothedScore == null })
        assertTrue(ticks.flatMap { it.bins }.all { it.rawScore == null && it.suspicious == null })
    }
    @Test fun localizesOneHundredPartialPatternsAtHalfSecondResolution() {
        // Contract fixtures, NOT model accuracy tests. Adapter outputs are explicitly supplied labels.
        repeat(100) { seed ->
            val start = 1 + seed % 5; val end = minOf(8, start + 1 + seed % 3)
            var result: StreamUpdate? = null
            val model = adapter { List(200) { i ->
                val time = i * .02
                FrameScore(time, time + .02, if (time >= start * .5 && time < end * .5) .95 else .1, true)
            } }
            StreamingDetector(model, executor = direct, onUpdate = {}, onResult = { result = it }).use { engine ->
                repeat(200) { engine.push(FloatArray(320)) }
            }
            val r = requireNotNull(result)
            assertEquals(8, r.bins.size)
            assertEquals(listOf(Segment(start * .5, end * .5)), r.segments)
            for ((i, bin) in r.bins.withIndex()) assertEquals(i in start until end, bin.suspicious)
        }
    }
    @Test fun silenceAndShortSpeechStayInsufficientEvenWithHighFakeScores() {
        var result: StreamUpdate? = null
        val model = adapter { List(200) { i -> FrameScore(i * .02, (i + 1) * .02, .99, i < 25) } }
        StreamingDetector(model, executor = direct, onUpdate = {}, onResult = { result = it }).use { it.push(FloatArray(64000)) }
        assertEquals(Evidence.INSUFFICIENT_SPEECH, result!!.evidence)
        assertNull(result!!.smoothedScore)
        assertTrue(result!!.bins.all { it.suspicious == null && it.rawScore == null })
    }
    @Test fun slowInferenceSkipsInsteadOfQueuingAndReportsAge() {
        val jobs = mutableListOf<Runnable>(); val ticks = mutableListOf<StreamUpdate>()
        var now = 100L; var result: StreamUpdate? = null
        val model = adapter(8000) { listOf(FrameScore(0.0, .5, .9, true)) }
        StreamingDetector(model, minimumSpeechS = 0.0, executor = Executor(jobs::add), clockMs = { now }, onUpdate = ticks::add, onResult = { result = it }).use { engine ->
            engine.push(FloatArray(24000)); assertEquals(1, jobs.size)
            assertEquals(2, ticks.last().skipped); assertEquals(Evidence.OVERLOADED, ticks.last().evidence)
            now = 950; jobs.removeAt(0).run(); assertEquals(850L, result!!.inferenceMs)
            now = 1200; engine.push(FloatArray(8000)); assertEquals(1100L, ticks.last().resultAgeMs)
            assertEquals(.5, ticks.last().scoredThroughS!!, 1e-6)
        }
    }
    @Test fun smoothingStabilizesDisplayWithoutSmearingLocalisation() {
        var high = false; val results = mutableListOf<StreamUpdate>()
        val model = adapter(8000) { high = !high; listOf(FrameScore(0.0, .5, if (high) 1.0 else 0.0, true)) }
        StreamingDetector(model, minimumSpeechS = 0.0, executor = direct, onUpdate = {}, onResult = results::add).use { it.push(FloatArray(32000)) }
        assertEquals(1.0, results[0].smoothedScore!!, 1e-6)
        assertEquals(.75, results[1].smoothedScore!!, 1e-6)
        assertEquals(0.0, results[1].bins.last().rawScore!!, 1e-6)
        assertTrue(results.zipWithNext().all { (a,b) -> kotlin.math.abs(a.smoothedScore!! - b.smoothedScore!!) <= .25 })
    }
    @Test fun rejectsInvalidFramesRatherThanReturningFakeVerdicts() {
        var result: StreamUpdate? = null
        val model = adapter(8000) { listOf(FrameScore(0.0, .5, Double.NaN, true)) }
        StreamingDetector(model, executor = direct, onUpdate = {}, onResult = { result = it }).use { it.push(FloatArray(8000)) }
        assertEquals(Evidence.ERROR, result!!.evidence); assertNull(result!!.smoothedScore)
    }
    @Test fun fullySyntheticAndGenuineFixturesProduceCorrectSegments() {
        for (score in listOf(.05, .95)) {
            var result: StreamUpdate? = null
            val model = adapter { listOf(FrameScore(0.0, 4.0, score, true)) }
            StreamingDetector(model, executor = direct, onUpdate = {}, onResult = { result = it }).use { it.push(FloatArray(64000)) }
            assertEquals(if (score > .7) listOf(Segment(0.0, 4.0)) else emptyList<Segment>(), result!!.segments)
        }
    }
    @Test fun closeDropsLateResultsAndDoesNotOwnSharedExecutor() {
        val jobs = mutableListOf<Runnable>(); var emitted = false
        val model = adapter(8000) { listOf(FrameScore(0.0, .5, .9, true)) }
        val engine = StreamingDetector(model, executor = Executor(jobs::add), onUpdate = {}, onResult = { emitted = true })
        engine.push(FloatArray(8000)); engine.close(); jobs.single().run()
        assertFalse(emitted)
        val shared = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            StreamingDetector(executor = shared, onUpdate = {}).close()
            assertFalse(shared.isShutdown)
        } finally { shared.shutdownNow() }
    }

}

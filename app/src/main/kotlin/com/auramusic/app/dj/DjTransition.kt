package com.auramusic.app.dj

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A saved transition from song [fromId] into song [toId].
 *
 * The incoming song starts at [aOutSec] on the outgoing song's clock, from [bInSec] in its own
 * audio, played at [speed] so both tempos line up. The blend lasts [bars] bars of the outgoing
 * song. Each volume line is a flat part, a straight ramp and a flat part: the outgoing song
 * stays at full volume until [outStart] then falls to silence at [outEnd]; the incoming one
 * stays silent until [inStart] then rises to full volume at [inEnd] (all 0..1 of the blend).
 */
data class DjTransition(
    val fromId: String,
    val toId: String,
    val bars: Int,
    val aOutSec: Float,
    val bInSec: Float,
    val speed: Float,
    val preset: String,
    val outStart: Float,
    val outEnd: Float,
    val inStart: Float,
    val inEnd: Float,
    val bpmFrom: Float,
) {
    val durationSec: Float get() = bars * 4f * 60f / bpmFrom
    val durationMs: Long get() = (durationSec * 1000f).toLong()

    fun outGain(t: Float): Float = ramp(t, outStart, outEnd, 1f, 0f)
    fun inGain(t: Float): Float = ramp(t, inStart, inEnd, 0f, 1f)

    private fun ramp(t: Float, s: Float, e: Float, from: Float, to: Float): Float = when {
        t <= s -> from
        t >= e -> to
        else -> from + (to - from) * (t - s) / (e - s)
    }

    fun toJson(): String = JSONObject()
        .put("from", fromId).put("to", toId).put("bars", bars)
        .put("aOut", aOutSec.toDouble()).put("bIn", bInSec.toDouble()).put("speed", speed.toDouble())
        .put("preset", preset)
        .put("os", outStart.toDouble()).put("oe", outEnd.toDouble())
        .put("is", inStart.toDouble()).put("ie", inEnd.toDouble())
        .put("bpm", bpmFrom.toDouble())
        .toString()

    companion object {
        val PRESETS = listOf("Custom", "Auto", "Fade", "Rise", "Overlap")

        fun fromJson(text: String): DjTransition? = runCatching {
            val o = JSONObject(text)
            DjTransition(
                o.getString("from"), o.getString("to"), o.getInt("bars"),
                o.getDouble("aOut").toFloat(), o.getDouble("bIn").toFloat(), o.getDouble("speed").toFloat(),
                o.getString("preset"),
                o.getDouble("os").toFloat(), o.getDouble("oe").toFloat(),
                o.getDouble("is").toFloat(), o.getDouble("ie").toFloat(),
                o.getDouble("bpm").toFloat(),
            )
        }.getOrNull()

        /** Volume lines of a preset, as (outStart, outEnd, inStart, inEnd). */
        fun presetLines(preset: String): List<Float> = when (preset) {
            "Fade" -> listOf(0f, 1f, 0f, 1f)
            "Rise" -> listOf(0.7f, 1f, 0f, 1f)
            "Overlap" -> listOf(0.75f, 1f, 0f, 0.25f)
            else -> listOf(0.25f, 0.9f, 0.1f, 0.75f) // Auto
        }

        /** Speed factor for the incoming song so its tempo matches the outgoing one (half/double time included). */
        fun speedFor(fromBpm: Float, toBpm: Float): Float {
            val r = listOf(fromBpm / toBpm, fromBpm / (toBpm * 2f), fromBpm / (toBpm / 2f)).minBy { abs(it - 1f) }
            return if (abs(r - 1f) <= 0.10f) r else 1f
        }

        /** Automatic transition: 8 bars (4 on short songs) ending one bar before the outgoing song ends. */
        fun auto(a: DjAnalysis, b: DjAnalysis, preset: String = "Auto", bars: Int? = null): DjTransition {
            val nBars = bars ?: if (a.durationSec < 90f) 4 else 8
            val bar = a.beatSec * 4f
            val dur = nBars * bar
            val raw = a.durationSec - dur - bar
            val k = ((raw - a.firstBeatSec) / bar).roundToInt().coerceAtLeast(0)
            val aOut = (a.firstBeatSec + k * bar).coerceAtLeast(0f)
            val l = presetLines(preset)
            return DjTransition(
                a.songId, b.songId, nBars, aOut, b.firstBeatSec, speedFor(a.bpm, b.bpm), preset,
                l[0], l[1], l[2], l[3], a.bpm,
            )
        }

        private fun file(dir: File, from: String, to: String): File {
            fun clean(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return File(dir, "dj_mixes/${clean(from)}__${clean(to)}.json")
        }

        fun load(dir: File, from: String, to: String): DjTransition? =
            file(dir, from, to).takeIf { it.exists() }?.readText()?.let(::fromJson)

        fun save(dir: File, t: DjTransition) {
            file(dir, t.fromId, t.toId).also { it.parentFile?.mkdirs() }.writeText(t.toJson())
        }

        fun delete(dir: File, from: String, to: String) {
            file(dir, from, to).delete()
        }
    }
}

package com.metrolist.shared.equalizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AuraMusique bass boost (low shelf) and virtualizer (stereo widening), applied on interleaved
 * 16-bit PCM after the equalizer. Both are no-ops when their setting is 0.
 */
class AuraEffects {
    private var shelfDb = Double.NaN
    private var shelfRate = 0
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val x1 = DoubleArray(8)
    private val x2 = DoubleArray(8)
    private val y1 = DoubleArray(8)
    private val y2 = DoubleArray(8)

    private fun configureShelf(
        gainDb: Double,
        sampleRate: Int,
    ) {
        if (gainDb == shelfDb && sampleRate == shelfRate) return
        shelfDb = gainDb
        shelfRate = sampleRate
        // RBJ low shelf, 110 Hz, slope 1.
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * 110.0 / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
        b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0
        b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha) / a0
        a1 = -2 * ((a - 1) + (a + 1) * cosW) / a0
        a2 = ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha) / a0
    }

    /** [bassBoostDb] 0..12, [virtualizer] 0..1. */
    fun process(
        samples: ShortArray,
        count: Int,
        channels: Int,
        sampleRate: Int,
        bassBoostDb: Double,
        virtualizer: Double,
    ) {
        if (count <= 0 || channels <= 0 || channels > 8) return
        if (bassBoostDb > 0.05) {
            configureShelf(bassBoostDb, sampleRate)
            // Headroom so the boosted lows do not clip.
            val headroom = 10.0.pow(-bassBoostDb * 0.4 / 20.0)
            var i = 0
            while (i < count) {
                for (c in 0 until channels) {
                    if (i + c >= count) break
                    val x = samples[i + c].toDouble()
                    val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                    x2[c] = x1[c]
                    x1[c] = x
                    y2[c] = y1[c]
                    y1[c] = y
                    samples[i + c] = (y * headroom).toInt().coerceIn(-32768, 32767).toShort()
                }
                i += channels
            }
        } else {
            shelfDb = Double.NaN
            x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        }
        if (virtualizer > 0.01 && channels == 2) {
            val width = 1.0 + virtualizer * 1.6
            val comp = 1.0 / (1.0 + virtualizer * 0.25)
            var i = 0
            while (i + 1 < count) {
                val l = samples[i].toDouble()
                val r = samples[i + 1].toDouble()
                val mid = (l + r) * 0.5
                val side = (l - r) * 0.5 * width
                samples[i] = ((mid + side) * comp).toInt().coerceIn(-32768, 32767).toShort()
                samples[i + 1] = ((mid - side) * comp).toInt().coerceIn(-32768, 32767).toShort()
                i += 2
            }
        }
    }

    fun reset() {
        shelfDb = Double.NaN
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    }
}

package com.auramusic.app.dj

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of analysing one song: tempo, position of the first beat, and a waveform for display. */
data class DjAnalysis(
    val songId: String,
    val bpm: Float,
    val firstBeatSec: Float,
    val durationSec: Float,
    /** 0..1 peak per bucket, [WAVE_POINTS] buckets over the whole song. */
    val waveform: FloatArray,
) {
    val beatSec: Float get() = 60f / bpm

    fun toJson(): String = JSONObject()
        .put("id", songId).put("bpm", bpm.toDouble()).put("first", firstBeatSec.toDouble())
        .put("dur", durationSec.toDouble())
        .put("wave", JSONArray().also { a -> waveform.forEach { a.put((it * 1000).roundToInt()) } })
        .toString()

    companion object {
        const val WAVE_POINTS = 4000

        fun fromJson(text: String): DjAnalysis? = runCatching {
            val o = JSONObject(text)
            val w = o.getJSONArray("wave")
            DjAnalysis(
                o.getString("id"), o.getDouble("bpm").toFloat(), o.getDouble("first").toFloat(),
                o.getDouble("dur").toFloat(), FloatArray(w.length()) { w.getInt(it) / 1000f },
            )
        }.getOrNull()
    }
}

/**
 * Finds the tempo of a song: the audio is read through the player's own data source (so cache
 * and stream resolution are reused), decoded to mono PCM, reduced to an onset-strength curve,
 * and the beat period is picked by autocorrelation.
 */
@UnstableApi
object DjAnalyzer {
    private const val TARGET_RATE = 11025
    private const val FRAME = 512
    private const val HOP = 256
    private const val MIN_BPM = 70f
    private const val MAX_BPM = 180f

    fun cacheFile(dir: File, songId: String) = File(dir, "dj_analysis/${songId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.json")

    fun load(dir: File, songId: String): DjAnalysis? =
        cacheFile(dir, songId).takeIf { it.exists() }?.readText()?.let(DjAnalysis::fromJson)

    suspend fun analyze(
        songId: String,
        dir: File,
        dataSource: DataSource,
        onProgress: (String) -> Unit = {},
    ): DjAnalysis = withContext(Dispatchers.IO) {
        load(dir, songId)?.let { return@withContext it }
        val tmp = File(dir, "dj_tmp_${System.nanoTime()}.audio")
        try {
            onProgress("Lecture de la chanson…")
            download(dataSource, songId, tmp)
            onProgress("Analyse du rythme…")
            val pcm = decode(tmp)
            val result = analyzePcm(songId, pcm.samples, pcm.rate)
            cacheFile(dir, songId).also { it.parentFile?.mkdirs() }.writeText(result.toJson())
            result
        } finally {
            tmp.delete()
        }
    }

    /** Same chunk size the player asks for: the data source resolves the stream URL per chunk. */
    private const val CHUNK = 512 * 1024L

    private suspend fun download(source: DataSource, songId: String, out: File) {
        out.outputStream().use { os ->
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                coroutineContext.ensureActive()
                var got = 0L
                try {
                    source.open(
                        DataSpec.Builder().setUri(songId.toUri()).setKey(songId)
                            .setPosition(pos).setLength(CHUNK).build(),
                    )
                    while (true) {
                        val n = source.read(buf, 0, buf.size)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        got += n
                    }
                } catch (e: java.io.IOException) {
                    // Asking past the end of the file (exact multiple of the chunk size) means done.
                    if (pos > 0L && got == 0L) return else throw e
                } finally {
                    runCatching { source.close() }
                }
                pos += got
                if (got < CHUNK) return
            }
        }
    }

    private class Pcm(val samples: FloatArray, val rate: Int)

    /** Decodes to mono floats at about [TARGET_RATE] Hz (box-filter decimation). */
    private suspend fun decode(file: File): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var step = max(1, rate / TARGET_RATE)
        val out = FloatArrayList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var acc = 0f
        var accN = 0
        try {
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    val shorts = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = info.size / 2 / channels
                    for (f in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += shorts.get(f * channels + c)
                        acc += s / channels / 32768f
                        if (++accN == step) { out.add(acc / step); acc = 0f; accN = 0 }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    step = max(1, rate / TARGET_RATE)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return Pcm(out.toArray(), rate / step)
    }

    internal fun analyzePcm(songId: String, x: FloatArray, rate: Int): DjAnalysis {
        val duration = x.size / rate.toFloat()
        // Waveform: peak per bucket.
        val wave = FloatArray(DjAnalysis.WAVE_POINTS)
        val per = max(1, x.size / wave.size)
        for (b in wave.indices) {
            var p = 0f
            val s = b * per
            for (i in s until min(x.size, s + per)) p = max(p, abs(x[i]))
            wave[b] = p
        }
        val mx = wave.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in wave.indices) wave[i] = (wave[i] / mx).coerceIn(0f, 1f)

        // Onset strength: positive change of log energy per frame (Hann window).
        val frames = (x.size - FRAME) / HOP
        if (frames < 200) return DjAnalysis(songId, 120f, 0f, duration, wave)
        val win = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / FRAME)).toFloat() }
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            var e = 0f
            val o = f * HOP
            for (i in 0 until FRAME) { val v = x[o + i] * win[i]; e += v * v }
            energy[f] = ln(1f + 1000f * e / FRAME)
        }
        val onset = FloatArray(frames)
        for (f in 1 until frames) onset[f] = max(0f, energy[f] - energy[f - 1])
        // Remove the local mean so steady loudness doesn't bias the autocorrelation.
        val sm = FloatArray(frames)
        val w = 43
        var run = 0f
        for (f in 0 until frames) {
            run += onset[f]
            if (f >= w) run -= onset[f - w]
            sm[f] = run / min(f + 1, w)
        }
        for (f in 0 until frames) onset[f] = max(0f, onset[f] - sm[f])

        val frameSec = HOP / rate.toFloat()
        val minLag = (60f / MAX_BPM / frameSec).toInt()
        val maxLag = (60f / MIN_BPM / frameSec).toInt()
        var bestLag = minLag
        var best = -1f
        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var s = 0f
            for (f in lag until frames) s += onset[f] * onset[f - lag]
            s /= (frames - lag)
            // Gentle preference for typical dance tempos (log-gaussian around 120 BPM).
            val bpm = 60f / (lag * frameSec)
            val l = ln(bpm / 120f)
            scores[lag] = s * exp(-(l * l) / (2 * 0.6f * 0.6f))
            if (scores[lag] > best) { best = scores[lag]; bestLag = lag }
        }
        // Refine with a parabola around the peak for sub-frame precision.
        var lagF = bestLag.toFloat()
        if (bestLag > minLag && bestLag < maxLag) {
            val a = scores[bestLag - 1]; val b = scores[bestLag]; val c = scores[bestLag + 1]
            val d = a - 2 * b + c
            if (d != 0f) lagF += 0.5f * (a - c) / d
        }
        var bpm = 60f / (lagF * frameSec)
        while (bpm < 80f) bpm *= 2f
        while (bpm > 175f) bpm /= 2f
        bpm = (bpm * 10).roundToInt() / 10f

        // Beat phase: the offset inside one beat where onsets add up the most.
        val period = 60f / bpm / frameSec
        val bins = period.roundToInt().coerceAtLeast(1)
        val phase = FloatArray(bins)
        for (f in 0 until frames) phase[((f % period) / period * bins).toInt().coerceIn(0, bins - 1)] += onset[f]
        var pi = 0
        for (i in phase.indices) if (phase[i] > phase[pi]) pi = i
        val first = (pi.toFloat() / bins * period) * frameSec
        return DjAnalysis(songId, bpm, first, duration, wave)
    }

    private class FloatArrayList {
        private var a = FloatArray(1 shl 20)
        private var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}

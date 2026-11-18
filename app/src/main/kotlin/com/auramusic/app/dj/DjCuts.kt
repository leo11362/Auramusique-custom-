package com.auramusic.app.dj

import org.json.JSONArray
import java.io.File

/** Parts of a song the user removed: playback jumps over each [start, end] range (seconds of the original audio). */
data class DjCut(val start: Float, val end: Float)

object DjCuts {
    private fun file(dir: File, id: String) =
        File(dir, "dj_cuts/${id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.json")

    fun load(dir: File, id: String): List<DjCut> = runCatching {
        val a = JSONArray(file(dir, id).readText())
        List(a.length()) { i -> a.getJSONArray(i).let { DjCut(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
    }.getOrDefault(emptyList())

    fun save(dir: File, id: String, cuts: List<DjCut>) {
        val f = file(dir, id)
        if (cuts.isEmpty()) { f.delete(); return }
        f.parentFile?.mkdirs()
        val a = JSONArray()
        merge(cuts).forEach { a.put(JSONArray().put(it.start.toDouble()).put(it.end.toDouble())) }
        f.writeText(a.toString())
    }

    /** Sorted, overlapping ranges joined, tiny ones dropped. */
    fun merge(cuts: List<DjCut>): List<DjCut> {
        val out = ArrayList<DjCut>()
        for (c in cuts.filter { it.end - it.start >= 0.3f }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && c.start <= last.end) out[out.size - 1] = DjCut(last.start, maxOf(last.end, c.end)) else out.add(c)
        }
        return out
    }

    fun removedSec(cuts: List<DjCut>) = cuts.sumOf { (it.end - it.start).toDouble() }.toFloat()
}

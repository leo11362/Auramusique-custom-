package com.auramusic.app.dj

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.auramusic.app.db.MusicDatabase
import com.auramusic.app.db.entities.PlaylistEntity
import com.auramusic.app.models.toMediaMetadata
import com.auramusic.app.playback.MusicService
import com.auramusic.innertube.YouTube
import com.auramusic.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/**
 * Mixes described in `mixes/mixes.json` of the GitHub repo (written by /root/work/tools/mix.py):
 * each entry becomes a playlist with its transitions already saved. Applied once per `rev`.
 *
 * { "mixes": [ { "id", "rev", "name", "songs": ["Title Artist", ...],
 *     "transitions": [ { "bars", "preset", "lines": [outStart,outEnd,inStart,inEnd], "aOut", "bIn" } ] } ] }
 * A single transition entry is reused for every pair. "aOut"/"bIn" (seconds) override the automatic placement.
 */
@UnstableApi
object DjMixSync {
    const val URL = "https://raw.githubusercontent.com/leo11362/Auramusique-custom-/main/mixes/mixes.json"

    /** Returns a short report. Safe to call often: only new or changed mixes do any work. */
    suspend fun sync(
        context: Context,
        database: MusicDatabase,
        service: MusicService,
        onStatus: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val json = (URL("$URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            inputStream.bufferedReader().use { it.readText() }
        }
        val mixes = JSONObject(json).optJSONArray("mixes") ?: JSONArray()
        val appliedFile = File(context.filesDir, "dj_mixes_applied.json")
        val applied = if (appliedFile.exists()) JSONObject(appliedFile.readText()) else JSONObject()
        var done = 0
        for (m in 0 until mixes.length()) {
            val mix = mixes.getJSONObject(m)
            val id = mix.getString("id")
            val rev = mix.optInt("rev", 1)
            val old = applied.optJSONObject(id)
            if (old != null && old.optInt("rev") == rev) continue
            val name = mix.getString("name")
            onStatus("Mix « $name »…")

            val ids = mutableListOf<String>()
            if (old != null) {
                val a = old.getJSONArray("songs")
                for (i in 0 until a.length()) ids += a.getString(i)
            } else {
                val queries = mix.getJSONArray("songs")
                val items = mutableListOf<SongItem>()
                for (i in 0 until queries.length()) {
                    val q = queries.getString(i)
                    onStatus("Recherche : $q")
                    val page = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
                    items += page.items.filterIsInstance<SongItem>().firstOrNull() ?: error("Introuvable : $q")
                }
                val playlistId = PlaylistEntity.generatePlaylistId()
                database.insert(PlaylistEntity(id = playlistId, name = name, bookmarkedAt = LocalDateTime.now(), isEditable = true))
                items.forEach { database.insert(it.toMediaMetadata()) }
                val created = database.playlist(playlistId).first() ?: error("Playlist non créée")
                database.addSongToPlaylist(created, items.map { it.id })
                ids += items.map { it.id }
            }

            val analyses = ids.mapIndexed { i, sid ->
                onStatus("Analyse ${i + 1}/${ids.size}")
                DjAnalyzer.analyze(sid, context.filesDir, { service.createAnalysisDataSource() }) { onStatus("Analyse ${i + 1}/${ids.size} : $it") }
            }
            val trs = mix.optJSONArray("transitions") ?: JSONArray()
            for (i in 0 until ids.size - 1) {
                val spec = if (trs.length() == 0) JSONObject() else trs.getJSONObject(minOf(i, trs.length() - 1))
                var t = DjTransition.auto(
                    analyses[i], analyses[i + 1],
                    preset = spec.optString("preset", "Auto"),
                    bars = if (spec.has("bars")) spec.getInt("bars") else null,
                )
                spec.optJSONArray("lines")?.takeIf { it.length() == 4 }?.let { l ->
                    t = t.copy(
                        outStart = l.getDouble(0).toFloat(), outEnd = l.getDouble(1).toFloat(),
                        inStart = l.getDouble(2).toFloat(), inEnd = l.getDouble(3).toFloat(),
                    )
                }
                if (spec.has("aOut")) t = t.copy(aOutSec = spec.getDouble("aOut").toFloat())
                if (spec.has("bIn")) t = t.copy(bInSec = spec.getDouble("bIn").toFloat())
                DjTransition.save(context.filesDir, t)
            }
            applied.put(id, JSONObject().put("rev", rev).put("songs", JSONArray(ids)))
            appliedFile.writeText(applied.toString())
            done++
        }
        if (done == 0) "Rien de nouveau." else "$done mix appliqué(s) : ouvre la bibliothèque."
    }
}

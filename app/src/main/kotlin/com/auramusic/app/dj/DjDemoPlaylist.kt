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
import java.time.LocalDateTime

/**
 * Builds the playlist of the Spotify "Edit transition" example (Counting Stars, then Levels)
 * with a 16-bar transition already saved between the two.
 */
@UnstableApi
object DjDemoPlaylist {
    const val NAME = "DJ Triple transition"

    /** [lines] = outStart, outEnd, inStart, inEnd (fractions of the transition) when not a preset. */
    class Spec(
        val name: String,
        val songs: List<String>,
        val bars: Int,
        val preset: String,
        val lines: List<Float>? = null,
    )

    val TRIPLE = Spec(NAME, listOf("Counting Stars OneRepublic", "Levels Avicii Radio Edit"), 16, "Fade")

    /** The TikTok mashup: both songs at full volume for 8 bars, the first cut sharply near the end. */
    const val MASHUP_NAME = "Turn Down for What x Crazy in Love"
    val MASHUP = Spec(
        MASHUP_NAME,
        listOf("Turn Down for What DJ Snake Lil Jon", "Crazy In Love Beyonce JAY-Z"),
        8, "Custom", listOf(0.83f, 0.88f, 0f, 0.02f),
    )

    suspend fun create(
        context: Context,
        database: MusicDatabase,
        service: MusicService,
        spec: Spec = TRIPLE,
        onStatus: (String) -> Unit,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        // NonCancellable: leaving the settings screen must not stop the analysis half way.
        val items = mutableListOf<SongItem>()
        for (q in spec.songs) {
            onStatus("Recherche : $q")
            val page = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            items += page.items.filterIsInstance<SongItem>().firstOrNull() ?: error("Introuvable : $q")
        }
        onStatus("Création de la playlist…")
        val playlistId = PlaylistEntity.generatePlaylistId()
        database.insert(PlaylistEntity(id = playlistId, name = spec.name, bookmarkedAt = LocalDateTime.now(), isEditable = true))
        items.forEach { database.insert(it.toMediaMetadata()) }
        val created = database.playlist(playlistId).first() ?: error("Playlist non créée")
        database.addSongToPlaylist(created, items.map { it.id })

        val analyses = items.mapIndexed { i, s ->
            onStatus("Analyse ${i + 1}/2 : ${s.title}")
            DjAnalyzer.analyze(s.id, context.filesDir, { service.createAnalysisDataSource() }) { onStatus("Analyse ${i + 1}/2 : $it") }
        }
        var t = DjTransition.auto(analyses[0], analyses[1], preset = spec.preset, bars = spec.bars)
        spec.lines?.let { t = t.copy(outStart = it[0], outEnd = it[1], inStart = it[2], inEnd = it[3]) }
        DjTransition.save(context.filesDir, t)
        playlistId
    }
}

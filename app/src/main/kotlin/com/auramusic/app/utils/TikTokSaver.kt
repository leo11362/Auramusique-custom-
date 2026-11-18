package com.auramusic.app.utils

import android.content.Context
import com.auramusic.app.db.MusicDatabase
import com.auramusic.app.db.entities.PlaylistEntity
import com.auramusic.app.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.time.LocalDateTime

/**
 * Keeps the raw audio of a TikTok sound on the phone: the file goes to filesDir/tiktok/tt_<id>.mp3
 * (MusicService plays ids starting with "tt_" from there) and the song joins the library and the
 * playlist "Sons TikTok".
 */
object TikTokSaver {
    const val PLAYLIST = "Sons TikTok"

    fun file(dir: File, mediaId: String) = File(dir, "tiktok/${mediaId.filter { it.isLetterOrDigit() || it == '_' }}.mp3")

    private fun download(url: String, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.path + ".part")
        val c = URL(url).openConnection()
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        c.setRequestProperty("Referer", "https://www.tiktok.com/")
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.getInputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (tmp.length() < 1000) { tmp.delete(); error("Audio file empty") }
        tmp.renameTo(to)
    }

    suspend fun save(context: Context, database: MusicDatabase, sound: PlaylistTextImport.TikTokSound): String =
        withContext(Dispatchers.IO) {
            val mediaId = "tt_${sound.id}"
            download(sound.audioUrl, file(context.filesDir, mediaId))
            val thumb = sound.coverUrl?.let { u ->
                runCatching {
                    val f = File(context.filesDir, "tiktok/$mediaId.jpg")
                    download(u, f.also { it.parentFile?.mkdirs() })
                    "file://${f.absolutePath}"
                }.getOrNull()
            }
            val title = sound.title.ifBlank { "TikTok" }
            val meta = MediaMetadata(
                id = mediaId,
                title = title,
                artists = listOf(MediaMetadata.Artist(id = null, name = sound.author.ifBlank { "TikTok" })),
                duration = sound.durationSec,
                thumbnailUrl = thumb,
                inLibrary = LocalDateTime.now(),
            )
            database.insert(meta)
            val existing = database.playlistsByNameAsc().first().firstOrNull { it.playlist.name == PLAYLIST }
            val playlist = existing ?: run {
                val id = PlaylistEntity.generatePlaylistId()
                database.insert(PlaylistEntity(id = id, name = PLAYLIST, bookmarkedAt = LocalDateTime.now(), isEditable = true))
                database.playlist(id).first()!!
            }
            database.addSongToPlaylist(playlist, listOf(mediaId))
            mediaId
        }
}

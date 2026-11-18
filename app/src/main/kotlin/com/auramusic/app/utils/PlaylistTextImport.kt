package com.auramusic.app.utils

import com.auramusic.app.db.entities.ArtistEntity
import com.auramusic.app.db.entities.Song
import com.auramusic.app.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds the placeholder songs (empty id) that AddToPlaylistDialogOnline resolves with a
 * YouTube Music search. Two inputs: a pasted list ("Artist - Title" per line) or the link of a
 * public Spotify playlist / album (read from its public embed page, no login).
 */
object PlaylistTextImport {
    private val spotifyRegex = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(playlist|album)/([A-Za-z0-9]+)""")
    private val separators = listOf(" - ", " – ", " — ")

    fun isSpotifyLink(text: String) = spotifyRegex.containsMatchIn(text)

    fun parseLines(text: String): List<Song> =
        text.lineSequence()
            .map { it.trim().trimStart('-', '•', '*', ' ').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val sep = separators.firstOrNull { line.contains(it) }
                val (artist, title) = if (sep != null) {
                    line.substringBefore(sep).trim() to line.substringAfter(sep).trim()
                } else {
                    "" to line
                }
                if (title.isEmpty()) null else placeholder(title, artist)
            }
            .toList()

    /** Returns (playlist name, tracks). Throws if the page cannot be read or is not public. */
    suspend fun fetchSpotify(text: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val match = spotifyRegex.find(text) ?: error("Not a Spotify playlist or album link")
        val (kind, id) = match.destructured
        val conn = URL("https://open.spotify.com/embed/$kind/$id").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Spotify page not readable")
        val root = JSONObject(json)
        val list = findKey(root, "trackList") as? JSONArray ?: error("No tracks found (is the playlist public?)")
        val name = (findKey(root, "name") as? String) ?: "Spotify import"
        val songs = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").trim()
            if (title.isEmpty()) null else placeholder(title, t.optString("subtitle").trim())
        }
        name to songs
    }

    private fun placeholder(title: String, artist: String): Song =
        Song(
            song = SongEntity(id = "", title = title),
            artists = artist.split(";", ",", "&").map { it.trim() }.filter { it.isNotEmpty() }
                .map { ArtistEntity(id = "", name = it) },
        )

    private fun findKey(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.get(key)
                for (k in node.keys()) findKey(node.get(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findKey(node.get(i), key)?.let { return it }
        }
        return null
    }
}

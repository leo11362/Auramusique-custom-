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

    private val deezerRegex = Regex("""deezer\.com/(?:[a-z]{2}/)?(playlist|album)/(\d+)""")
    private val appleRegex = Regex("""https?://music\.apple\.com/[^\s]+/(?:playlist|album)/[^\s]+""")
    private val tidalRegex = Regex("""tidal\.com/(?:browse/)?(playlist|album)/([0-9a-fA-F-]+)""")
    private val amazonRegex = Regex("""https?://[a-z.]*music\.amazon\.[a-z.]+/[^\s]+""")
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private val tiktokRegex = Regex("""https?://(?:[a-z]+\.)?tiktok\.com/[^\s]+""")
    private val originalSound = Regex("""(?i)^(original sound|son original|sonido original|originalton|suono originale|som original).*""")

    fun isTikTokLink(text: String) = tiktokRegex.containsMatchIn(text)

    /** Reads the sound of a TikTok video (page metadata, no login) as one song to look up on YouTube Music. */
    suspend fun fetchTikTok(text: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val url = tiktokRegex.find(text)!!.value
        val html = httpGet(url)
        val i = html.indexOf("\"music\":{")
        if (i < 0) error("Sound not found on this TikTok page")
        val block = html.substring(i, minOf(html.length, i + 4000))
        fun field(k: String) = Regex("\"$k\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(block)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject("{\"v\":\"$it\"}").getString("v") }.getOrDefault(it) }.orEmpty()
        val title = field("title")
        val author = field("authorName")
        if (title.isBlank()) error("Sound not found on this TikTok page")
        if (originalSound.matches(title)) error("This is an original sound (voice or creator audio), it is not a song on YouTube Music")
        "TikTok" to listOf(placeholder(title, author))
    }

    fun isSpotifyLink(text: String) = spotifyRegex.containsMatchIn(text)

    /** Links that can be read without any login. */
    fun isDirectLink(text: String) =
        tiktokRegex.containsMatchIn(text) || spotifyRegex.containsMatchIn(text) || deezerRegex.containsMatchIn(text) ||
            appleRegex.containsMatchIn(text) || tidalRegex.containsMatchIn(text) ||
            text.contains("deezer.page.link") || text.contains("link.deezer.com")

    /** Links that need the in-app web view (Amazon Music, full or private Spotify). */
    fun webViewUrl(text: String): String? =
        amazonRegex.find(text)?.value
            ?: spotifyRegex.find(text)?.let { "https://open.spotify.com/${it.groupValues[1]}/${it.groupValues[2]}" }

    suspend fun fetchLink(text: String): Pair<String, List<Song>> {
        if (tiktokRegex.containsMatchIn(text)) return fetchTikTok(text)
        var t = text
        if (!deezerRegex.containsMatchIn(t) && (t.contains("deezer.page.link") || t.contains("link.deezer.com"))) {
            val short = Regex("""https?://[^\s]*(?:deezer\.page\.link|link\.deezer\.com)[^\s]*""").find(t)!!.value
            t = withContext(Dispatchers.IO) {
                val c = URL(short).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA)
                c.instanceFollowRedirects = true
                c.inputStream.close()
                c.url.toString()
            }
        }
        return when {
            deezerRegex.containsMatchIn(t) -> fetchDeezer(t)
            appleRegex.containsMatchIn(t) -> fetchApple(appleRegex.find(t)!!.value)
            tidalRegex.containsMatchIn(t) -> fetchTidal(t)
            else -> fetchSpotify(t)
        }
    }

    private fun httpGet(url: String, vararg headers: Pair<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        headers.forEach { c.setRequestProperty(it.first, it.second) }
        c.connectTimeout = 15000
        c.readTimeout = 20000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private suspend fun fetchDeezer(text: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val (kind, id) = deezerRegex.find(text)!!.destructured
        val head = JSONObject(httpGet("https://api.deezer.com/$kind/$id"))
        if (head.has("error")) error("Deezer: playlist not public or not found")
        val songs = ArrayList<Song>()
        var index = 0
        while (index < 5000) {
            val page = JSONObject(httpGet("https://api.deezer.com/$kind/$id/tracks?limit=100&index=$index"))
            val data = page.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i)
                val title = t.optString("title").trim()
                if (title.isNotEmpty()) songs.add(placeholder(title, t.optJSONObject("artist")?.optString("name").orEmpty()))
            }
            index += data.length()
            if (!page.has("next")) break
        }
        head.optString("title", "Deezer import") to songs
    }

    private suspend fun fetchApple(url: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val html = httpGet(url)
        val json = Regex("""id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Apple Music page not readable (is the playlist public?)")
        val songs = ArrayList<Song>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    if (n.optJSONObject("contentDescriptor")?.optString("kind") == "song") {
                        val title = n.optString("title").trim()
                        if (title.isNotEmpty()) songs.add(placeholder(title, n.optString("artistName").trim()))
                    } else for (k in n.keys()) walk(n.get(k))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.get(i))
            }
        }
        walk(JSONObject(json))
        val name = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?.substringBefore(" by ")?.trim().orEmpty().ifEmpty { "Apple Music import" }
        name to songs
    }

    private suspend fun fetchTidal(text: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val (kind, id) = tidalRegex.find(text)!!.destructured
        val token = "x-tidal-token" to "CzET4vdadNUFQ5JU"
        val base = if (kind == "playlist") "https://api.tidal.com/v1/playlists/$id" else "https://api.tidal.com/v1/albums/$id"
        val head = JSONObject(httpGet("$base?countryCode=US", token))
        val songs = ArrayList<Song>()
        var offset = 0
        while (offset < 5000) {
            val page = JSONObject(httpGet("$base/items?limit=100&offset=$offset&countryCode=US", token))
            val items = page.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i).optJSONObject("item") ?: continue
                val title = t.optString("title").trim()
                if (title.isNotEmpty()) songs.add(placeholder(title, t.optJSONObject("artist")?.optString("name").orEmpty()))
            }
            offset += items.length()
            if (offset >= page.optInt("totalNumberOfItems", 0)) break
        }
        head.optString("title", "Tidal import") to songs
    }

    /** Rows scraped by the in-app web view: "title\u0001artist" per line. */
    fun parseScraped(rows: List<String>): List<Song> =
        rows.mapNotNull { r ->
            val title = r.substringBefore('\u0001').trim()
            if (title.isEmpty()) null else placeholder(title, r.substringAfter('\u0001', "").trim())
        }.distinctBy { it.song.title + "|" + it.artists.joinToString { a -> a.name } }

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

package com.auramusic.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.auramusic.app.LocalDatabase
import com.auramusic.app.R
import com.auramusic.app.dj.DjAnalysis
import com.auramusic.app.dj.DjAnalyzer
import com.auramusic.app.models.toMediaMetadata
import com.auramusic.innertube.YouTube
import com.auramusic.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Search a song to mix into [fromId]; results already analysed show their BPM and how well they match. */
@kotlin.OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DjPickScreen(navController: NavController, fromId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val from = remember(fromId) { DjAnalyzer.load(context.filesDir, fromId) }
    val fromSong by database.song(fromId).collectAsState(initial = null)

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<SongItem>() }

    // Start with the artist of the current song so there is something useful to pick right away.
    LaunchedEffect(fromSong) {
        if (query.isEmpty()) fromSong?.artists?.firstOrNull()?.name?.let { query = it }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            searching = true
            message = null
            YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
                .onSuccess { page ->
                    results.clear()
                    results.addAll(page.items.filterIsInstance<SongItem>().filter { it.id != fromId }.distinctBy { it.id })
                    if (results.isEmpty()) message = "Aucun résultat."
                }
                .onFailure { message = "Recherche impossible : ${it.message}" }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Chanson de transition") },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildString {
                    append("Après « ${fromSong?.song?.title ?: ""} »")
                    if (from != null) append(" · ${"%.1f".format(from.bpm)} BPM")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Chercher une chanson") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            if (searching) CircularProgressIndicator(Modifier.size(24.dp))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(results, key = { it.id }) { item ->
                val cached = remember(item.id) { DjAnalyzer.load(context.filesDir, item.id) }
                PickRow(item, cached, from) {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { database.insert(item.toMediaMetadata()) } }
                        navController.navigate("dj_mix/$fromId/${item.id}")
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(item: SongItem, cached: DjAnalysis?, from: DjAnalysis?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Text(
                item.artists.joinToString { it.name },
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cached != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text("%.0f BPM".format(cached.bpm), style = MaterialTheme.typography.titleSmall)
                if (from != null) {
                    val label = matchLabel(from.bpm, cached.bpm)
                    Text(label.first, style = MaterialTheme.typography.bodySmall, color = label.second())
                }
            }
        }
    }
}

/** How well two tempos mix: within 4 % the speed change is inaudible, up to 8 % still fine. */
internal fun matchLabel(fromBpm: Float, toBpm: Float): Pair<String, @Composable () -> androidx.compose.ui.graphics.Color> {
    val diff = abs(toBpm / fromBpm - 1f)
    // Half / double time also mixes well.
    val half = minOf(abs(toBpm * 2f / fromBpm - 1f), abs(toBpm / 2f / fromBpm - 1f))
    val d = minOf(diff, half)
    return when {
        d <= 0.04f -> "Très compatible" to { androidx.compose.ui.graphics.Color(0xFF4CAF50) }
        d <= 0.08f -> "Compatible" to { androidx.compose.ui.graphics.Color(0xFFFFB300) }
        else -> "Écart de rythme" to { MaterialTheme.colorScheme.error }
    }
}

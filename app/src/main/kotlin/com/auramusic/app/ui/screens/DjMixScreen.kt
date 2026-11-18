package com.auramusic.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.auramusic.app.LocalDatabase
import com.auramusic.app.LocalPlayerConnection
import com.auramusic.app.R
import com.auramusic.app.dj.DjAnalysis
import kotlin.math.abs

/** Transition between two songs: both tempos analysed and compared (the editor comes next). */
@kotlin.OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DjMixScreen(navController: NavController, fromId: String, toId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val fromSong by database.song(fromId).collectAsState(initial = null)
    val toSong by database.song(toId).collectAsState(initial = null)

    var from by remember { mutableStateOf<DjAnalysis?>(null) }
    var to by remember { mutableStateOf<DjAnalysis?>(null) }
    var status by remember { mutableStateOf("Analyse…") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(fromId, toId, attempt) {
        error = null
        try {
            from = loadDjAnalysis(context, playerConnection, fromId) { status = "Chanson 1 : $it" }
            to = loadDjAnalysis(context, playerConnection, toId) { status = "Chanson 2 : $it" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = "Analyse impossible : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Transition") },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SongLine("1", fromSong?.song?.title, fromSong?.artists?.joinToString { it.name }, fromSong?.song?.thumbnailUrl, from)
            SongLine("2", toSong?.song?.title, toSong?.artists?.joinToString { it.name }, toSong?.song?.thumbnailUrl, to)
            val a = from
            val b = to
            when {
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { attempt++ }) { Text("Réessayer") }
                }
                a == null || b == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(status)
                }
                else -> {
                    val label = matchLabel(a.bpm, b.bpm)
                    Text(label.first, style = MaterialTheme.typography.headlineSmall, color = label.second())
                    val ratio = bestRatio(a.bpm, b.bpm)
                    Text(
                        "Pour caler les rythmes, la chanson 2 sera jouée à ${"%+.1f".format((ratio - 1f) * 100f)} % de sa vitesse " +
                            "(${"%.1f".format(b.bpm)} → ${"%.1f".format(b.bpm * ratio)} BPM).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "L'éditeur de transition (ondes, durée en barres, courbes de volume) arrive à l'étape suivante.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Speed factor for the incoming song so its tempo matches the outgoing one (half / double time included). */
internal fun bestRatio(fromBpm: Float, toBpm: Float): Float =
    listOf(fromBpm / toBpm, fromBpm / (toBpm * 2f), fromBpm / (toBpm / 2f)).minBy { abs(it - 1f) }

@Composable
private fun SongLine(n: String, title: String?, artist: String?, thumb: String?, a: DjAnalysis?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$n · ${title ?: ""}", maxLines = 1, style = MaterialTheme.typography.titleSmall)
            Text(artist ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (a != null) Text("%.1f BPM".format(a.bpm), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

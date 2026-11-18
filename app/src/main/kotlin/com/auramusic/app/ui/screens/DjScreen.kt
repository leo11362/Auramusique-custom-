package com.auramusic.app.ui.screens


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.auramusic.app.LocalDatabase
import com.auramusic.app.LocalPlayerConnection
import com.auramusic.app.R
import com.auramusic.app.dj.DjAnalysis
import com.auramusic.app.dj.DjAnalyzer
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WINDOW_SEC = 20f

/** Cached analysis, or a fresh one made through the player's data source. */
@OptIn(UnstableApi::class)
internal suspend fun loadDjAnalysis(
    context: android.content.Context,
    playerConnection: com.auramusic.app.playback.PlayerConnection?,
    songId: String,
    onStatus: (String) -> Unit = {},
): DjAnalysis {
    DjAnalyzer.load(context.filesDir, songId)?.let { return it }
    val service = playerConnection?.service
        ?: error("Le lecteur n'est pas prêt. Lance une chanson puis réessaie.")
    return DjAnalyzer.analyze(songId, context.filesDir, service.createAnalysisDataSource(), onStatus)
}

@kotlin.OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DjScreen(navController: NavController, songId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val song by database.song(songId).collectAsState(initial = null)

    var analysis by remember { mutableStateOf<DjAnalysis?>(null) }
    var status by remember { mutableStateOf("Lecture de la chanson…") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(songId, attempt) {
        error = null
        analysis = DjAnalyzer.load(context.filesDir, songId)
        if (analysis == null) {
            val service = playerConnection?.service
            if (service == null) {
                error = "Le lecteur n'est pas prêt. Lance une chanson puis réessaie."
                return@LaunchedEffect
            }
            try {
                analysis = DjAnalyzer.analyze(songId, context.filesDir, service.createAnalysisDataSource()) { status = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Analyse impossible : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // Position of the playhead when this very song is playing (seconds), else null.
    var playhead by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(songId) {
        while (true) {
            val p = playerConnection?.player
            playhead = if (p?.currentMediaItem?.mediaId == songId) p.currentPosition / 1000f else null
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Mode DJ") },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = song?.song?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song?.song?.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        song?.artists?.joinToString { it.name } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            val a = analysis
            when {
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { attempt++ }) { Text("Réessayer") }
                }
                a == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(status)
                }
                else -> DjAnalysisView(a, playhead, onPick = { navController.navigate("dj_pick/$songId") }) { fixed ->
                    DjAnalyzer.cacheFile(context.filesDir, songId).writeText(fixed.toJson())
                    analysis = fixed
                }
            }
        }
    }
}

@Composable
private fun DjAnalysisView(a: DjAnalysis, playhead: Float?, onPick: () -> Unit, onChange: (DjAnalysis) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.1f".format(a.bpm),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("BPM", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(a.copy(bpm = a.bpm / 2f)) }, enabled = a.bpm / 2f >= 40f) { Text("÷ 2") }
            OutlinedButton(onClick = { onChange(a.copy(bpm = a.bpm * 2f)) }, enabled = a.bpm * 2f <= 240f) { Text("× 2") }
        }
        Text(
            "Durée ${formatTime(a.durationSec)} · premier temps à ${"%.2f".format(a.firstBeatSec)} s · 1 mesure = ${"%.1f".format(a.beatSec * 4)} s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        var start by remember(a.songId) { mutableFloatStateOf(0f) }
        val maxStart = max(0f, a.durationSec - WINDOW_SEC)
        // Follow the playhead while the song plays.
        LaunchedEffect(playhead) {
            if (playhead != null && (playhead < start || playhead > start + WINDOW_SEC * 0.8f)) {
                start = (playhead - WINDOW_SEC * 0.2f).coerceIn(0f, maxStart)
            }
        }
        WaveformZoom(a, start, playhead) { start = (start + it).coerceIn(0f, maxStart) }
        WaveformOverview(a, start, playhead) { start = it.coerceIn(0f, maxStart) }
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choisir la chanson de transition") }
        Text(
            "Glisse la grande onde pour naviguer. Les traits fins = temps, les traits épais = début de mesure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaveformZoom(a: DjAnalysis, start: Float, playhead: Float?, onDragSec: (Float) -> Unit) {
    val wave = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface
    val head = MaterialTheme.colorScheme.tertiary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .pointerInput(a.songId) {
                detectHorizontalDragGestures { _, drag -> onDragSec(-drag / size.width * WINDOW_SEC) }
            },
    ) {
        val w = size.width
        val h = size.height
        val perSec = a.waveform.size / a.durationSec
        val pxPerSec = w / WINDOW_SEC
        val mid = h / 2f
        var x = 0f
        while (x < w) {
            val t = start + x / pxPerSec
            val idx = (t * perSec).toInt().coerceIn(0, a.waveform.size - 1)
            val amp = a.waveform[idx] * (h / 2f - 6f)
            drawLine(wave, Offset(x, mid - amp), Offset(x, mid + amp), strokeWidth = 2f)
            x += 3f
        }
        // Beat grid.
        val beat = a.beatSec
        var n = floor((start - a.firstBeatSec) / beat).toInt()
        while (true) {
            val t = a.firstBeatSec + n * beat
            if (t > start + WINDOW_SEC) break
            if (t >= start && n >= 0) {
                val px = (t - start) * pxPerSec
                val bar = n % 4 == 0
                drawLine(
                    grid.copy(alpha = if (bar) 0.9f else 0.35f),
                    Offset(px, if (bar) 0f else h * 0.25f),
                    Offset(px, if (bar) h else h * 0.75f),
                    strokeWidth = if (bar) 3f else 1.5f,
                )
            }
            n++
        }
        if (playhead != null && playhead in start..(start + WINDOW_SEC)) {
            val px = (playhead - start) * pxPerSec
            drawLine(head, Offset(px, 0f), Offset(px, h), strokeWidth = 4f)
        }
    }
}

@Composable
private fun WaveformOverview(a: DjAnalysis, start: Float, playhead: Float?, onSeek: (Float) -> Unit) {
    val wave = MaterialTheme.colorScheme.onSurfaceVariant
    val box = MaterialTheme.colorScheme.primary
    val head = MaterialTheme.colorScheme.tertiary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .pointerInput(a.songId) {
                detectHorizontalDragGestures { change, _ ->
                    onSeek(change.position.x / size.width * a.durationSec - WINDOW_SEC / 2f)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val cols = min(w.toInt() / 2, a.waveform.size)
        for (i in 0 until cols) {
            val v = a.waveform[(i.toFloat() / cols * a.waveform.size).toInt().coerceIn(0, a.waveform.size - 1)]
            val amp = v * (h / 2f - 3f)
            val x = i.toFloat() / cols * w
            drawLine(wave, Offset(x, h / 2f - amp), Offset(x, h / 2f + amp), strokeWidth = 1.5f)
        }
        val x0 = start / a.durationSec * w
        drawRect(box.copy(alpha = 0.25f), Offset(x0, 0f), Size(WINDOW_SEC / a.durationSec * w, h))
        if (playhead != null) {
            val px = playhead / a.durationSec * w
            drawLine(head, Offset(px, 0f), Offset(px, h), strokeWidth = 3f)
        }
    }
}

private fun formatTime(sec: Float): String {
    val s = sec.roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}

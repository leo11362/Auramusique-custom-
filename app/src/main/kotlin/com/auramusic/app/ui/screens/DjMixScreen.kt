package com.auramusic.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.auramusic.app.LocalDatabase
import com.auramusic.app.LocalPlayerConnection
import com.auramusic.app.R
import com.auramusic.app.dj.DjAnalysis
import com.auramusic.app.dj.DjTransition
import com.auramusic.app.extensions.toMediaItem
import com.auramusic.app.playback.queues.ListQueue
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val LineBlue = Color(0xFF29B6F6)
private val PlayGreen = Color(0xFF3DDC84)

/** Transition editor: two waveforms, volume lines you drag, length in bars, presets, live preview. */
@kotlin.OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DjMixScreen(navController: NavController, fromId: String, toId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val fromSong by database.song(fromId).collectAsState(initial = null)
    val toSong by database.song(toId).collectAsState(initial = null)

    var a by remember { mutableStateOf<DjAnalysis?>(null) }
    var b by remember { mutableStateOf<DjAnalysis?>(null) }
    var t by remember { mutableStateOf<DjTransition?>(null) }
    val original = remember(fromId, toId) { DjTransition.load(context.filesDir, fromId, toId) }
    var status by remember { mutableStateOf("Analyse…") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(fromId, toId, attempt) {
        error = null
        try {
            val x = loadDjAnalysis(context, playerConnection, fromId) { status = "Chanson 1 : $it" }
            a = x
            val y = loadDjAnalysis(context, playerConnection, toId) { status = "Chanson 2 : $it" }
            b = y
            if (t == null) t = original ?: DjTransition.auto(x, y)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = "Analyse impossible : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Playhead (seconds into the transition) while the preview or the real playback runs.
    var playhead by remember { mutableStateOf<Float?>(null) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(fromId, toId) {
        while (true) {
            val p = playerConnection?.player
            val cur = t
            playing = p?.isPlaying == true
            playhead = when {
                cur == null || p == null -> null
                p.currentMediaItem?.mediaId == fromId -> p.currentPosition / 1000f - cur.aOutSec
                p.currentMediaItem?.mediaId == toId -> (p.currentPosition / 1000f - cur.bInSec) / cur.speed
                else -> null
            }
            delay(50)
        }
    }

    fun restoreOriginal() {
        if (original != null) DjTransition.save(context.filesDir, original) else DjTransition.delete(context.filesDir, fromId, toId)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Modifier la transition") },
            navigationIcon = {
                TextButton(onClick = { restoreOriginal(); navController.navigateUp() }) { Text("Annuler") }
            },
            actions = {
                TextButton(
                    enabled = t != null,
                    onClick = { t?.let { DjTransition.save(context.filesDir, it) }; navController.navigateUp() },
                ) { Text("Enregistrer") }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SongLine(fromSong?.song?.title, fromSong?.artists?.joinToString { it.name }, fromSong?.song?.thumbnailUrl, a)
            val aa = a
            val bb = b
            val tt = t
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempt++ }) { Text("Réessayer") }
            } else if (aa == null || bb == null || tt == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(status)
                }
            } else {
                Box {
                    TransitionCanvas(aa, bb, tt, playhead) { t = it }
                    FilledIconButton(
                        onClick = {
                            if (playing) {
                                playerConnection?.player?.pause()
                            } else {
                                // Save the draft first: the player reads the saved transition.
                                DjTransition.save(context.filesDir, tt)
                                val items = listOfNotNull(fromSong?.toMediaItem(), toSong?.toMediaItem())
                                if (items.size == 2) {
                                    playerConnection?.playQueue(
                                        ListQueue(title = "Transition", items = items, position = max(0L, (tt.aOutSec * 1000f).toLong() - 6000L)),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.Center).size(56.dp),
                    ) {
                        Icon(painterResource(if (playing) R.drawable.pause else R.drawable.play), contentDescription = null)
                    }
                }
                BarsPicker(tt.bars) { t = tt.copy(bars = it, preset = "Custom") }
                SongLine(toSong?.song?.title, toSong?.artists?.joinToString { it.name }, toSong?.song?.thumbnailUrl, bb)
                Text("Préréglages", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DjTransition.PRESETS.forEach { name ->
                        FilterChip(
                            selected = tt.preset == name,
                            onClick = {
                                t = when (name) {
                                    "Custom" -> tt.copy(preset = "Custom")
                                    "Auto" -> DjTransition.auto(aa, bb)
                                    else -> {
                                        val l = DjTransition.presetLines(name)
                                        tt.copy(preset = name, outStart = l[0], outEnd = l[1], inStart = l[2], inEnd = l[3])
                                    }
                                }
                            },
                            label = { Text(name) },
                        )
                    }
                }
                Text(
                    "Glisse l'onde du haut ou du bas pour choisir où la transition commence. " +
                        "Tire les ronds bleus pour régler les volumes. " +
                        "Rythme : la chanson 2 est jouée à ${"%+.1f".format((tt.speed - 1f) * 100f)} % de sa vitesse pour caler les BPM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BarsPicker(bars: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(onClick = { open = true }) { Text("$bars bars  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(1, 2, 4, 8, 16, 32).forEach {
                DropdownMenuItem(text = { Text("$it bars") }, onClick = { open = false; onPick(it) })
            }
        }
    }
}

@Composable
private fun TransitionCanvas(a: DjAnalysis, b: DjAnalysis, t: DjTransition, playhead: Float?, onChange: (DjTransition) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val waveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurface
    val dur = t.durationSec
    // Window: half a transition before and after it.
    val winStart = -0.5f * dur
    val winLen = 2f * dur
    var current by remember { mutableStateOf(t) }
    current = t

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .pointerInput(a.songId, b.songId) {
                var mode = 0 // 1..4 handles, 5 = A wave, 6 = B wave
                val grab = with(density) { 40.dp.toPx() }
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val c = current
                        val d = c.durationSec
                        fun xOf(f: Float) = ((f * d - winStart) / (2f * d)) * w
                        val half = h / 2f
                        val handles = listOf(
                            1 to Offset(xOf(c.outStart), 8f),
                            2 to Offset(xOf(c.outEnd), half - 8f),
                            3 to Offset(xOf(c.inStart), half + 8f),
                            4 to Offset(xOf(c.inEnd), h - 8f),
                        )
                        val near = handles.minBy { (_, o) -> (o - pos).getDistance() }
                        mode = if ((near.second - pos).getDistance() <= grab) near.first else if (pos.y < half) 5 else 6
                    },
                    onDragEnd = {
                        val c = current
                        val bar = a.beatSec * 4f
                        val barB = b.beatSec * 4f
                        val aOut = a.firstBeatSec + ((c.aOutSec - a.firstBeatSec) / bar).roundToInt() * bar
                        val bIn = b.firstBeatSec + ((c.bInSec - b.firstBeatSec) / barB).roundToInt() * barB
                        onChange(c.copy(aOutSec = aOut.coerceAtLeast(0f), bInSec = bIn.coerceAtLeast(0f)))
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val c = current
                        val w = size.width.toFloat()
                        val d = c.durationSec
                        val dxSec = drag.x / w * 2f * d
                        val gap = 0.05f
                        val df = dxSec / d
                        val n = when (mode) {
                            1 -> c.copy(outStart = (c.outStart + df).coerceIn(0f, c.outEnd - gap), preset = "Custom")
                            2 -> c.copy(outEnd = (c.outEnd + df).coerceIn(c.outStart + gap, 1f), preset = "Custom")
                            3 -> c.copy(inStart = (c.inStart + df).coerceIn(0f, c.inEnd - gap), preset = "Custom")
                            4 -> c.copy(inEnd = (c.inEnd + df).coerceIn(c.inStart + gap, 1f), preset = "Custom")
                            5 -> c.copy(aOutSec = (c.aOutSec - dxSec).coerceIn(0f, a.durationSec), preset = "Custom")
                            6 -> c.copy(bInSec = (c.bInSec - dxSec * c.speed).coerceIn(0f, b.durationSec), preset = "Custom")
                            else -> c
                        }
                        current = n
                        onChange(n)
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val half = h / 2f
        val pxPerSec = w / winLen
        fun xOfTau(tau: Float) = (tau - winStart) * pxPerSec

        fun wave(an: DjAnalysis, originSec: Float, rate: Float, top: Float) {
            val perSec = an.waveform.size / an.durationSec
            var x = 0f
            while (x < w) {
                val tau = winStart + x / pxPerSec
                val sec = originSec + tau * rate
                if (sec >= 0f && sec < an.durationSec) {
                    val v = an.waveform[(sec * perSec).toInt().coerceIn(0, an.waveform.size - 1)]
                    val amp = v * (half / 2f - 10f)
                    val inside = tau in 0f..dur
                    val col = waveColor.copy(alpha = if (inside) 0.95f else 0.4f)
                    val mid = top + half / 2f
                    drawLine(col, Offset(x, mid - amp), Offset(x, mid + amp), strokeWidth = 2.5f)
                }
                x += 3f
            }
            // Bar lines of this song's grid.
            val bar = an.beatSec * 4f
            val firstTau = winStart
            var k = kotlin.math.ceil(((originSec + firstTau * rate) - an.firstBeatSec) / bar).toInt()
            while (true) {
                val sec = an.firstBeatSec + k * bar
                val tau = (sec - originSec) / rate
                if (tau > winStart + winLen) break
                if (sec >= 0f) drawLine(gridColor.copy(alpha = 0.25f), Offset(xOfTau(tau), top), Offset(xOfTau(tau), top + half), strokeWidth = 1.5f)
                k++
            }
        }
        wave(a, t.aOutSec, 1f, 0f)
        wave(b, t.bInSec, t.speed, half)

        // Transition region.
        drawRect(LineBlue.copy(alpha = 0.10f), Offset(xOfTau(0f), 0f), Size(xOfTau(dur) - xOfTau(0f), h))
        drawRect(LineBlue.copy(alpha = 0.6f), Offset(xOfTau(0f), 0f), Size(xOfTau(dur) - xOfTau(0f), h), style = Stroke(width = 2f))

        // Volume lines: A in the top half, B in the bottom half (full height of the half = full volume).
        val pad = 8f
        fun yA(g: Float) = (half - pad) - g * (half - 2 * pad)
        fun yB(g: Float) = h - pad - g * (half - 2 * pad)
        val xa0 = xOfTau(t.outStart * dur); val xa1 = xOfTau(t.outEnd * dur)
        val xb0 = xOfTau(t.inStart * dur); val xb1 = xOfTau(t.inEnd * dur)
        val sw = 5f
        drawLine(LineBlue, Offset(0f, yA(1f)), Offset(xa0, yA(1f)), strokeWidth = sw)
        drawLine(LineBlue, Offset(xa0, yA(1f)), Offset(xa1, yA(0f)), strokeWidth = sw)
        drawLine(LineBlue, Offset(xa1, yA(0f)), Offset(w, yA(0f)), strokeWidth = sw)
        drawLine(LineBlue, Offset(0f, yB(0f)), Offset(xb0, yB(0f)), strokeWidth = sw)
        drawLine(LineBlue, Offset(xb0, yB(0f)), Offset(xb1, yB(1f)), strokeWidth = sw)
        drawLine(LineBlue, Offset(xb1, yB(1f)), Offset(w, yB(1f)), strokeWidth = sw)
        listOf(Offset(xa0, yA(1f)), Offset(xa1, yA(0f)), Offset(xb0, yB(0f)), Offset(xb1, yB(1f))).forEach {
            drawCircle(Color.White, 15f, it)
            drawCircle(LineBlue, 11f, it)
        }
        drawLine(gridColor.copy(alpha = 0.3f), Offset(0f, half), Offset(w, half), strokeWidth = 1f)

        if (playhead != null) {
            val x = xOfTau(playhead)
            if (x in 0f..w) drawLine(PlayGreen, Offset(x, 0f), Offset(x, h), strokeWidth = 6f)
        }
    }
}

@Composable
private fun SongLine(title: String?, artist: String?, thumb: String?, a: DjAnalysis?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title ?: "", maxLines = 1, style = MaterialTheme.typography.titleSmall)
            Text(artist ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (a != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text("${a.bpm.roundToInt()} bpm", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "%d:%02d".format(a.durationSec.roundToInt() / 60, a.durationSec.roundToInt() % 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

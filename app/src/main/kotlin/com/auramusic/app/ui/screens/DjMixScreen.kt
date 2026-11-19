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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.material3.OutlinedButton
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
import com.auramusic.app.dj.DjCut
import com.auramusic.app.dj.DjCuts
import com.auramusic.app.dj.DjTransition
import com.auramusic.app.extensions.toMediaItem
import com.auramusic.app.playback.queues.ListQueue
import kotlinx.coroutines.async
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
    val originalCutsA = remember(fromId) { DjCuts.load(context.filesDir, fromId) }
    val originalCutsB = remember(toId) { DjCuts.load(context.filesDir, toId) }
    var cutsA by remember { mutableStateOf(originalCutsA) }
    var cutsB by remember { mutableStateOf(originalCutsB) }
    fun saveCuts() {
        DjCuts.save(context.filesDir, fromId, cutsA)
        DjCuts.save(context.filesDir, toId, cutsB)
    }

    LaunchedEffect(fromId, toId, attempt) {
        error = null
        try {
            val (x, y) = kotlinx.coroutines.coroutineScope {
                val dx = async { loadDjAnalysis(context, playerConnection, fromId) { status = "Chanson 1 : $it" } }
                val dy = async { loadDjAnalysis(context, playerConnection, toId) { status = "Chanson 2 : $it" } }
                dx.await() to dy.await()
            }
            a = x
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
        DjCuts.save(context.filesDir, fromId, originalCutsA)
        DjCuts.save(context.filesDir, toId, originalCutsB)
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
                    onClick = { t?.let { DjTransition.save(context.filesDir, it) }; saveCuts(); navController.navigateUp() },
                ) { Text("Enregistrer") }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SongLine(fromSong?.song?.title, fromSong?.artists?.joinToString { it.name }, fromSong?.song?.thumbnailUrl, a, cutsA)
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
                                saveCuts()
                                val items = listOfNotNull(fromSong?.toMediaItem(), toSong?.toMediaItem())
                                if (items.size == 2) {
                                    // Repeat-one replays the song instead of moving on, so no transition could ever start.
                                    playerConnection?.player?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
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
                SongOverview(
                    "Chanson 1 en entier", aa, cutsA, tt.aOutSec, tt.aOutSec + tt.durationSec, "Sort à",
                    onMove = { sec -> t = tt.copy(aOutSec = snapBar(aa, sec), preset = "Custom") },
                    onCuts = { cutsA = it },
                )
                SongOverview(
                    "Chanson 2 en entier", bb, cutsB, tt.bInSec, tt.bInSec + tt.durationSec * tt.speed, "Entre à",
                    onMove = { sec -> t = tt.copy(bInSec = snapBar(bb, sec), preset = "Custom") },
                    onCuts = { cutsB = it },
                )
                SongLine(toSong?.song?.title, toSong?.artists?.joinToString { it.name }, toSong?.song?.thumbnailUrl, bb, cutsB)
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
                    "Les deux bandes « en entier » montrent toute la chanson avec ses mesures et ses minutes : touche ou glisse pour placer la transition, " +
                        "ou active « Couper » puis glisse pour retirer un bout (il ne sera plus joué). " +
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
private fun SongLine(title: String?, artist: String?, thumb: String?, a: DjAnalysis?, cuts: List<DjCut> = emptyList()) {
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
                    if (cuts.isEmpty()) fmtTime(a.durationSec)
                    else "${fmtTime(a.durationSec)} → ${fmtTime(a.durationSec - DjCuts.removedSec(cuts))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fmtTime(sec: Float): String {
    val s = sec.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun snapBar(an: DjAnalysis, sec: Float): Float {
    val bar = an.beatSec * 4f
    val v = an.firstBeatSec + ((sec - an.firstBeatSec) / bar).roundToInt() * bar
    return v.coerceIn(0f, an.durationSec)
}

/**
 * Whole song: waveform, bar lines, a time axis, the part used by the transition (blue) and the
 * removed parts (red). Touch or drag moves the transition; in cut mode a drag removes a part.
 */
@Composable
private fun SongOverview(
    label: String,
    an: DjAnalysis,
    cuts: List<DjCut>,
    useStart: Float,
    useEnd: Float,
    verb: String,
    onMove: (Float) -> Unit,
    onCuts: (List<DjCut>) -> Unit,
) {
    var cutMode by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DjCut?>(null) }
    val measurer = rememberTextMeasurer()
    val waveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = textColor.copy(alpha = 0.8f))
    val cutRed = Color(0xFFE5484D)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "$verb ${fmtTime(useStart)} · fin ${fmtTime(useEnd)}",
                style = MaterialTheme.typography.labelMedium, color = LineBlue,
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = cutMode, onClick = { cutMode = !cutMode }, label = { Text("✂ Couper") })
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .pointerInput(an.songId, cutMode, cuts) {
                    fun secOf(x: Float) = (x / size.width * an.durationSec).coerceIn(0f, an.durationSec)
                    if (cutMode) {
                        var from = 0f
                        detectDragGestures(
                            onDragStart = { from = secOf(it.x); drag = DjCut(from, from) },
                            onDrag = { change, _ ->
                                change.consume()
                                val x = secOf(change.position.x)
                                drag = DjCut(min(from, x), max(from, x))
                            },
                            onDragEnd = { drag?.let { onCuts(DjCuts.merge(cuts + it)) }; drag = null },
                            onDragCancel = { drag = null },
                        )
                    } else {
                        detectDragGestures(
                            onDragStart = { onMove(secOf(it.x)) },
                            onDrag = { change, _ -> change.consume(); onMove(secOf(change.position.x)) },
                        )
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val axis = 16f
            val body = h - axis
            fun xOf(sec: Float) = sec / an.durationSec * w
            // Used part by the transition.
            drawRect(LineBlue.copy(alpha = 0.22f), Offset(xOf(useStart), 0f), Size(max(3f, xOf(min(useEnd, an.durationSec)) - xOf(useStart)), body))
            // Waveform.
            val perPx = an.waveform.size.toFloat() / w
            var x = 0f
            while (x < w) {
                val v = an.waveform[(x * perPx).toInt().coerceIn(0, an.waveform.size - 1)]
                val amp = v * (body / 2f - 4f)
                drawLine(waveColor.copy(alpha = 0.85f), Offset(x, body / 2f - amp), Offset(x, body / 2f + amp), strokeWidth = 2f)
                x += 3f
            }
            // Bar lines: every 4 bars, thicker every 16.
            val bar = an.beatSec * 4f
            var k = kotlin.math.ceil((0f - an.firstBeatSec) / bar).toInt()
            while (true) {
                val sec = an.firstBeatSec + k * bar
                if (sec > an.durationSec) break
                if (k % 4 == 0) {
                    drawLine(textColor.copy(alpha = if (k % 16 == 0) 0.35f else 0.15f), Offset(xOf(sec), 0f), Offset(xOf(sec), body), strokeWidth = 1f)
                }
                k++
            }
            // Time axis.
            val step = listOf(10, 15, 20, 30, 60, 120).firstOrNull { xOf(it.toFloat()) >= 64f } ?: 120
            var ts = 0
            while (ts <= an.durationSec) {
                drawLine(textColor.copy(alpha = 0.5f), Offset(xOf(ts.toFloat()), body), Offset(xOf(ts.toFloat()), body + 4f), strokeWidth = 1.5f)
                val tx = measurer.measure(fmtTime(ts.toFloat()), textStyle)
                drawText(tx, topLeft = Offset((xOf(ts.toFloat()) + 2f).coerceAtMost(w - tx.size.width), body + 2f))
                ts += step
            }
            // Cuts.
            (cuts + listOfNotNull(drag)).forEach {
                drawRect(cutRed.copy(alpha = 0.45f), Offset(xOf(it.start), 0f), Size(max(2f, xOf(it.end) - xOf(it.start)), body))
                drawLine(cutRed, Offset(xOf(it.start), 0f), Offset(xOf(it.start), body), strokeWidth = 3f)
                drawLine(cutRed, Offset(xOf(it.end), 0f), Offset(xOf(it.end), body), strokeWidth = 3f)
            }
            // Transition start marker.
            drawLine(LineBlue, Offset(xOf(useStart), 0f), Offset(xOf(useStart), body), strokeWidth = 5f)
        }
        if (cutMode && cuts.isEmpty()) {
            Text("Glisse le doigt sur la bande pour choisir le bout à retirer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (cuts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cuts.forEach { c ->
                    OutlinedButton(onClick = { onCuts(cuts - c) }) {
                        Text("${fmtTime(c.start)}–${fmtTime(c.end)}  ✕", color = cutRed)
                    }
                }
            }
        }
    }
}

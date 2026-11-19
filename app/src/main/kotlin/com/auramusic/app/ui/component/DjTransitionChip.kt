package com.auramusic.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auramusic.app.R
import com.auramusic.app.dj.DjTransition

/** Small pill between two songs of a playlist: shows the saved transition, tap to edit or add one. */
@Composable
fun DjTransitionChip(fromId: String, toId: String, onClick: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) version++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val t = remember(fromId, toId, version) { DjTransition.load(context.filesDir, fromId, toId) }
    // Zero-height box: the pill floats over the boundary between the two songs, so they stay stuck together.
    Box(
        Modifier.fillMaxWidth().height(0.dp).wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
            .padding(end = 56.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = if (t != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (t != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            border = if (t == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.dj_disc), contentDescription = null, modifier = Modifier.size(12.dp))
                Text(
                    if (t != null) "${t.bars} bars · ${t.preset}" else "Transition",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

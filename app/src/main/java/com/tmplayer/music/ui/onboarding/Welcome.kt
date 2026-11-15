package com.tmplayer.music.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.music.R
import com.tmplayer.music.i18n.L

/** The first screen, once: what the app is, and that it is unofficial. Nothing is read yet. */
@Composable
fun Welcome(onContinue: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Centred when it fits, scrolls when it does not: a phone on its side is about 390 dp tall and
        // squeezed Continue to a 5 dp sliver when the column only centred.
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppIcon(96.dp)
                Text(L.appName, style = MaterialTheme.typography.headlineMedium)
                Text(
                    L.welcomeBody,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
                Text(
                    L.appUnofficial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onContinue, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) { Text(L.commonContinue) }
            }
        }
    }
}

/**
 * The launcher icon as a round launcher draws it: the adaptive icon's two layers, the tile colour
 * and the foreground mark, cropped to a disc showing the middle 72 of the layers' 108 units (the
 * part a launcher mask keeps). Drawn from the same drawables, so it never drifts from the icon.
 */
@Composable
private fun AppIcon(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_tile)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * LAYER / VISIBLE),
        )
    }
}

private const val LAYER = 108f
private const val VISIBLE = 72f

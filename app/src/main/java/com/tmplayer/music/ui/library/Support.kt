package com.tmplayer.music.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.SupportChecks
import com.tmplayer.music.data.SupportReminder
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.About
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.common.BmcBrand
import com.tmplayer.music.ui.common.BmcCupMark
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.theme.MusicTheme
import kotlinx.coroutines.delay

/** Opens [url] in the browser bare; a phone with no browser shows the address instead. */
private fun android.content.Context.openSupportLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
}

/** Star on GitHub: the repository, and no star is asked for again. */
private fun android.content.Context.openStar() {
    runCatching { SupportChecks.store(applicationContext).markStarred() }
    openSupportLink(About.SOURCE)
}

/**
 * Settings, after About: Support TMPlayer Music. Buy Me a Coffee first, in its own yellow, with
 * GitHub Sponsors and Star on GitHub as the quieter ways beside it, and the "Sponsor reminders"
 * switch (off until turned on). All of it voluntary: nothing in the app is locked behind it.
 * After "Don't show again" the switch goes, as the asks have ended for good; the links stay.
 */
@Composable
fun SupportSection() {
    if (!SupportReminder.enabled) return
    val context = LocalContext.current
    val store = remember { SupportChecks.store(context) }
    var remindersOn by remember { mutableStateOf(store.remindersOn) }
    val finished = remember { store.counters().finished }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        L.supportTitle,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp),
    )
    Text(
        L.supportNote,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    CoffeeButton(
        onClick = { context.openSupportLink(About.COFFEE) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 420.dp).fillMaxWidth(),
    )
    QuietWays(Modifier.padding(horizontal = 8.dp))
    if (!finished) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = remindersOn, role = Role.Switch) { on ->
                    remindersOn = on
                    store.remindersOn = on
                    SupportChecks.schedule(context)
                    // The switch asks for the notification permission when it is turned on, never before.
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L.supportReminders, style = MaterialTheme.typography.bodyLarge)
                Text(L.supportRemindersDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = remindersOn, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** GitHub Sponsors and Star on GitHub, side by side. */
@Composable
private fun QuietWays(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { context.openSupportLink(About.SPONSORS) }) {
            Icon(MusicIcons.HeartOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(L.supportSponsorGithub)
        }
        TextButton(onClick = { context.openStar() }) {
            Icon(MusicIcons.Star, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(L.supportStarGithub)
        }
    }
}

/** Buy Me a Coffee's own button: their yellow, their near-black words and their cup ([BmcBrand]). */
@Composable
fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BmcBrand.Yellow, contentColor = BmcBrand.Ink),
    ) {
        BmcCupMark(height = 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(L.supportBuyCoffee, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The Buy Me a Coffee window, the ask itself (see [SupportReminder] for when). Buy Me a Coffee
 * comes first in its own colours; GitHub Sponsors and the star are the quieter ways under it.
 * "Don't show again" ends the asks and the notification for good. Back, a tap outside and Maybe
 * later all count as asked, which the window already did when it appeared.
 */
@Composable
fun CoffeeDialog(completedTracks: Int, onLater: () -> Unit, onNever: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onLater) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(L.supportAskTitle, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    L.supportAskText(completedTracks.coerceAtLeast(1)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                CoffeeButton(onClick = { context.openSupportLink(About.COFFEE) }, modifier = Modifier.fillMaxWidth())
                QuietWays()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onNever, modifier = Modifier.weight(1f)) { Text(L.supportDontShow, textAlign = TextAlign.Center) }
                    OutlinedButton(onClick = onLater, modifier = Modifier.weight(1f)) { Text(L.supportMaybeLater, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/**
 * Where the window appears by itself, mounted once beside the app's root. It waits for all of:
 * past onboarding (signed in, the library open, the picker finished once), nothing playing, and
 * either a press on the notification or a fresh moment (a track played to its end in the last
 * [SupportReminder.MOMENT_LIFETIME_MS]) with [SupportReminder.claim] agreeing. A moment that comes
 * while music plays waits; music starting again within [ASK_DELAY_MS] calls it off. Nothing here
 * runs on a timer while idle: the effect wakes only when one of those values changes.
 */
@Composable
fun SupportAskHost() {
    if (!SupportReminder.enabled) return
    val context = LocalContext.current
    val store = remember { SupportChecks.store(context) }
    val prefs = remember { Prefs(context) }
    val auth by Td.auth.collectAsStateWithLifecycle()
    val ready by Library.ready.collectAsStateWithLifecycle()
    val player by PlayerConnection.state.collectAsStateWithLifecycle()
    val moment by SupportReminder.moment.collectAsStateWithLifecycle()
    val openRequested by SupportReminder.openRequested.collectAsStateWithLifecycle()
    var showing by rememberSaveable { mutableStateOf(false) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    val inLibrary = auth is AuthState.Ready && ready && prefs.pickerDone
    val busy = player.isPlaying

    LaunchedEffect(inLibrary) {
        if (inLibrary) runCatching { store.noteFirstSeen(System.currentTimeMillis()) }
    }

    LaunchedEffect(moment, openRequested, inLibrary, busy, showing) {
        if (!inLibrary || busy || showing) return@LaunchedEffect
        if (openRequested) {
            delay(ASK_DELAY_MS / 4)
            if (!SupportReminder.takeOpenRequest()) return@LaunchedEffect
            val now = System.currentTimeMillis()
            completed = runCatching { store.counters().completedTracks }.getOrDefault(0)
            if (!store.counters().finished) {
                runCatching { store.recordAsk(now) }
                SupportReminder.sessionShown = true
                showing = true
            }
            return@LaunchedEffect
        }
        if (moment == 0L && !SupportReminder.forced) return@LaunchedEffect
        delay(ASK_DELAY_MS)
        val now = System.currentTimeMillis()
        val fresh = SupportReminder.takeMoment(now) || SupportReminder.forced
        if (!fresh) return@LaunchedEffect
        if (runCatching { SupportReminder.claim(store, now) }.getOrDefault(false)) {
            completed = runCatching { store.counters().completedTracks }.getOrDefault(0)
            showing = true
        }
    }

    if (showing) {
        CoffeeDialog(
            completedTracks = completed,
            onLater = { showing = false },
            onNever = {
                runCatching { store.markNever() }
                SupportChecks.schedule(context)
                showing = false
            },
        )
    }
}

/** How long the library has to stay quiet before the window appears. */
private const val ASK_DELAY_MS = 1_500L

@Preview(name = "Coffee window, light", widthDp = 400, heightDp = 640)
@Preview(name = "Coffee window, dark", widthDp = 400, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CoffeeDialogPreview() {
    MusicTheme { CoffeeDialog(completedTracks = 42, onLater = {}, onNever = {}) }
}

@Preview(name = "Support section, light", widthDp = 400)
@Preview(name = "Support section, dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SupportSectionPreview() {
    MusicTheme { Surface { Column { SupportSection() } } }
}

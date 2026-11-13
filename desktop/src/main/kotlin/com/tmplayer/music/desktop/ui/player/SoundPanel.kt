// The sound panel follows the Android app's EffectsSheet and SleepTimer UI (app/.../ui/player): the
// same choices from :core (AudioEffectsChoices, EqPresets, PlaybackSpeed), applied through mpv. A
// control libmpv cannot honour is not shown (EngineCapabilities), never a switch that does nothing.
package com.tmplayer.music.desktop.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.music.desktop.player.EngineCapabilities
import com.tmplayer.music.desktop.player.FilterProbe
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.SleepSetting
import com.tmplayer.music.desktop.player.SoundGraph
import com.tmplayer.music.desktop.ui.common.BackHandler
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.i18n.L
import com.tmplayer.music.player.AudioEffectsSetting
import com.tmplayer.music.player.EqPresets
import com.tmplayer.music.player.PlaybackSpeed
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

private val SOUND_PANEL_WIDTH = 400.dp

/** The minutes the sleep timer offers, as the phone's sheet does. */
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)

/** What the engine can do: the real answer once an engine exists, else what the library's files say. */
@Composable
fun rememberCapabilities(player: PlayerController): EngineCapabilities {
    val known by player.capabilities.collectAsState()
    return known ?: EngineCapabilities(
        speed = true,
        gain = true,
        equalizer = FilterProbe.has("equalizer"),
        skipSilence = FilterProbe.has("silenceremove"),
    )
}

/**
 * Sound effects over the page's right edge: playback speed with Keep pitch, the equalizer with its
 * presets, Skip silence and Normalise loudness (each only where libmpv can do it), then the sleep
 * timer. Everything applies at once and is remembered across restarts. Escape closes it.
 */
@Composable
fun SoundPanel(player: PlayerController, onClose: () -> Unit, modifier: Modifier = Modifier, capabilities: EngineCapabilities? = null) {
    val caps = capabilities ?: rememberCapabilities(player)
    BackHandler(onBack = onClose)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        modifier = modifier.width(SOUND_PANEL_WIDTH).fillMaxHeight(),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L.effectsTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(MusicIcons.Close, contentDescription = L.playerClose) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                val fx = AudioEffectsSetting.choices
                if (caps.speed) SpeedSection()
                if (caps.equalizer) EqualizerSection()
                if (caps.skipSilence) {
                    val on by fx.skipSilence.collectAsState()
                    SwitchRow(L.effectsSkipSilence, L.effectsSkipSilenceDetail, on) { fx.setSkipSilence(it) }
                }
                if (caps.gain) {
                    val on by fx.normalise.collectAsState()
                    SwitchRow(L.effectsNormalise, L.effectsNormaliseDetail, on) { fx.setNormalise(it) }
                }
                SleepSection(player)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}

// Speed

@Composable
private fun SpeedSection() {
    val fx = AudioEffectsSetting.choices
    val speed by fx.speed.collectAsState()
    val keep by fx.keepPitch.collectAsState()
    SectionLabel(L.effectsSpeed)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(PlaybackSpeed.label(speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp))
        Slider(
            value = speed,
            onValueChange = { fx.setSpeed(it) },
            valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
            modifier = Modifier.weight(1f).semantics { contentDescription = L.effectsSpeed },
        )
    }
    for (row in PlaybackSpeed.QUICK.chunked(3)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (quick in row) {
                FilterChip(selected = PlaybackSpeed.snap(speed) == quick, onClick = { fx.setSpeed(quick) }, label = { Text(PlaybackSpeed.label(quick), maxLines = 1) })
            }
        }
    }
    SwitchRow(L.effectsKeepPitch, L.effectsKeepPitchDetail, keep) { fx.setKeepPitch(it) }
}

// Equalizer

@Composable
private fun EqualizerSection() {
    val fx = AudioEffectsSetting.choices
    val on by fx.equalizerOn.collectAsState()
    val preset by fx.preset.collectAsState()
    val saved by fx.levels.collectAsState()
    val bands = SoundGraph.EQ_BANDS_HZ
    val range = SoundGraph.EQ_RANGE_MB
    val presets = androidx.compose.runtime.remember { EqPresets.builtIn(bands, -range, range) }
    val levels = EqPresets.effective(saved, preset, presets, bands.size, -range, range)
    SwitchRow(L.effectsEqualizer, null, on) { fx.setEqualizerOn(it) }
    if (!on) return
    SectionLabel(L.effectsPresets)
    val chips = presets.map { it.id } + if (preset == EqPresets.CUSTOM) listOf(EqPresets.CUSTOM) else emptyList()
    for (row in chips.chunked(3)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (id in row) {
                val p = presets.firstOrNull { it.id == id }
                FilterChip(
                    selected = preset == id,
                    onClick = { if (p != null) fx.choosePreset(p.id, EqPresets.fit(p.levels, bands.size, -range, range)) },
                    label = { Text(presetName(id), maxLines = 1) },
                )
            }
        }
    }
    for ((i, hz) in bands.withIndex()) {
        val level = levels.getOrElse(i) { 0 }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(EqPresets.frequencyLabel(hz), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
            Slider(
                value = level.toFloat(),
                onValueChange = { v ->
                    val next = levels.toMutableList().also { it[i] = ((v / 50f).roundToInt() * 50).coerceIn(-range, range) }
                    fx.setLevels(next, EqPresets.match(next, presets))
                },
                valueRange = -range.toFloat()..range.toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = L.effectsBandDescription(EqPresets.frequencyLabel(hz), EqPresets.levelLabel(level)) },
            )
            Text(EqPresets.levelLabel(level), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        }
    }
    TextButton(onClick = { fx.choosePreset(EqPresets.FLAT, List(bands.size) { 0 }) }, modifier = Modifier.padding(start = 12.dp)) { Text(L.effectsReset) }
}

private fun presetName(id: String): String = when (id) {
    EqPresets.FLAT -> L.effectsFlat
    EqPresets.BASS -> L.effectsBass
    EqPresets.TREBLE -> L.effectsTreble
    EqPresets.VOCAL -> L.effectsVocal
    else -> L.effectsCustom
}

// Sleep timer

/**
 * The sleep timer: pause in a number of minutes (the last seconds fade the volume down) or at the
 * end of the track. It turns itself off once it has paused.
 */
@Composable
private fun SleepSection(player: PlayerController) {
    val sleep by player.sleep.collectAsState()
    SectionLabel(L.sleepTitle)
    val left by produceState(0L, sleep) {
        val s = sleep
        if (s is SleepSetting.At) {
            while (true) {
                value = (s.endsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
                delay(1_000)
            }
        } else {
            value = 0
        }
    }
    when (sleep) {
        is SleepSetting.At -> Text(L.sleepLeft(L.unitMinutes(ceil(left / 60_000.0).toInt().coerceAtLeast(1))), modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyLarge)
        SleepSetting.EndOfTrack -> Text(L.sleepEndOfTrack, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyLarge)
        SleepSetting.Off -> Unit
    }
    // Wrapped by hand: a flow row needs the experimental layout API on this Compose version.
    for (row in SLEEP_MINUTES.chunked(3)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in row) {
                FilterChip(selected = false, onClick = { player.sleepAfter(m) }, label = { Text(L.sleepMinutes(m)) })
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = sleep == SleepSetting.EndOfTrack, onClick = { player.sleepAtEndOfTrack() }, label = { Text(L.sleepEndOfTrackShort) })
        if (sleep != SleepSetting.Off) FilterChip(selected = false, onClick = { player.sleepOff() }, label = { Text(L.sleepOff) })
    }
    Box(Modifier.padding(bottom = 12.dp))
}

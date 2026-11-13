package com.tmplayer.music.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.i18n.L
import com.tmplayer.music.player.AudioEffectsChoices
import com.tmplayer.music.player.AudioEffectsSetting
import com.tmplayer.music.player.AudioEffectsState
import com.tmplayer.music.player.EqPreset
import com.tmplayer.music.player.EqPresets
import com.tmplayer.music.player.EqualizerInfo
import com.tmplayer.music.player.PlaybackSpeed
import com.tmplayer.music.ui.tv.LocalTv
import com.tmplayer.music.ui.tv.requestFocusSoon
import com.tmplayer.music.ui.tv.tvFocusRing

/**
 * Sound effects: playback speed (0.5x to 2x) with a keep pitch switch, skip silence, normalise
 * loudness and the equalizer. Every change applies at once and is remembered. The equalizer is left
 * out when the phone or TV has no audio effects or the effect would not start. On a TV the D-pad
 * moves between the rows, left and right change a slider and the first row has focus on opening.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EffectsSheet(onDismiss: () -> Unit) {
    val choices = AudioEffectsSetting.choices
    val speed by choices.speed.collectAsStateWithLifecycle()
    val keepPitch by choices.keepPitch.collectAsStateWithLifecycle()
    val skipSilence by choices.skipSilence.collectAsStateWithLifecycle()
    val normalise by choices.normalise.collectAsStateWithLifecycle()
    val equalizer by AudioEffectsState.equalizer.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val tv = LocalTv.current
    LaunchedEffect(Unit) {
        if (tv) requestFocusSoon(first)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(L.effectsTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L.effectsSpeed, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(PlaybackSpeed.label(speed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = speed,
                onValueChange = { choices.setSpeed(it) },
                valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                steps = ((PlaybackSpeed.MAX - PlaybackSpeed.MIN) / PlaybackSpeed.STEP).toInt() - 1,
                modifier = Modifier.padding(horizontal = 20.dp).focusRequester(first).tvFocusRing(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = L.effectsSpeed },
            )
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (quick in PlaybackSpeed.QUICK) {
                    FilterChip(
                        selected = speed == quick,
                        onClick = { choices.setSpeed(quick) },
                        label = { Text(PlaybackSpeed.label(quick)) },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
                    )
                }
            }
            SwitchRow(L.effectsKeepPitch, L.effectsKeepPitchDetail, keepPitch) { choices.setKeepPitch(it) }
            SwitchRow(L.effectsSkipSilence, L.effectsSkipSilenceDetail, skipSilence) { choices.setSkipSilence(it) }
            SwitchRow(L.effectsNormalise, L.effectsNormaliseDetail, normalise) { choices.setNormalise(it) }

            equalizer?.let { EqualizerPanel(it, choices) }
        }
    }
}

/** The equalizer: the on switch, the presets and one slider per band the device has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerPanel(info: EqualizerInfo, choices: AudioEffectsChoices) {
    val on by choices.equalizerOn.collectAsStateWithLifecycle()
    val preset by choices.preset.collectAsStateWithLifecycle()
    val saved by choices.levels.collectAsStateWithLifecycle()
    val bands = info.centerHz.size
    val levels = EqPresets.effective(saved, preset, info.presets, bands, info.minMb, info.maxMb)

    // Choosing anything turns the equalizer on, so the sliders never move without a sound change.
    fun choose(id: String, next: List<Int>) {
        choices.choosePreset(id, next)
        if (!choices.equalizerOn.value) choices.setEqualizerOn(true)
    }

    SwitchRow(L.effectsEqualizer, null, on) { choices.setEqualizerOn(it) }
    Text(L.effectsPresets, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in info.presets) {
            FilterChip(
                selected = preset == item.id,
                onClick = { choose(item.id, item.levels) },
                label = { Text(presetName(item)) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
            )
        }
        if (preset == EqPresets.CUSTOM) {
            FilterChip(selected = true, onClick = {}, label = { Text(L.effectsCustom) }, modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)))
        }
    }
    val steps = ((info.maxMb - info.minMb) / 100 - 1).coerceAtLeast(0)
    for (band in 0 until bands) {
        val level = levels.getOrElse(band) { 0 }
        val label = L.effectsBandDescription(EqPresets.frequencyLabel(info.centerHz[band]), EqPresets.levelLabel(level))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(EqPresets.frequencyLabel(info.centerHz[band]), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(EqPresets.levelLabel(level), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = level.toFloat(),
                onValueChange = {
                    val next = levels.toMutableList().also { list -> list[band] = (it / 10f).toInt() * 10 }
                    choose(EqPresets.match(next, info.presets), next)
                },
                valueRange = info.minMb.toFloat()..info.maxMb.toFloat(),
                steps = steps,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(12.dp)).semantics { contentDescription = label },
            )
        }
    }
    TextButton(
        onClick = { info.presets.firstOrNull { it.id == EqPresets.FLAT }?.let { choose(it.id, it.levels) } },
        modifier = Modifier.padding(horizontal = 12.dp).tvFocusRing(RoundedCornerShape(20.dp)),
    ) { Text(L.effectsReset) }
}

@Composable
private fun presetName(preset: EqPreset): String = when (preset.id) {
    EqPresets.FLAT -> L.effectsFlat
    EqPresets.BASS -> L.effectsBass
    EqPresets.TREBLE -> L.effectsTreble
    EqPresets.VOCAL -> L.effectsVocal
    else -> preset.name ?: L.effectsCustom
}

/** A setting that is on or off with a line saying what it does; the whole row toggles it and takes the D-pad focus. */
@Composable
private fun SwitchRow(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** Three sliders, the icon of the sound effects button; drawn like the other icons (1.5 wide round strokes on a 24 dp grid). */
val EffectsIcon: ImageVector = ImageVector.Builder(
    name = "Effects",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)
    .addPath(
        pathData = addPathNodes(
            "M5 4V9 M5 13V20 M12 4V14 M12 18V20 M19 4V6 M19 10V20 " +
                "M3.5 11a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0 " +
                "M10.5 16a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0 " +
                "M17.5 8a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0",
        ),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    .build()

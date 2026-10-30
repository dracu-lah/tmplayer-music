package com.tmplayer.music.ui.auth

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.QrPhase
import com.tmplayer.music.ui.theme.MusicTheme

// Previews of the QR plate's states on the black theme: a fresh code, one about to change, scanned,
// expired, and being replaced. The link is a placeholder that signs nobody in.

private const val SAMPLE_LINK = "tg://login?token=VE1QbGF5ZXJNdXNpYyBwcmV2aWV3"

@Composable
private fun PlateSample(dark: Boolean, phase: QrPhase, remaining: Float, scanned: Boolean = false, renewing: Boolean = false) {
    MusicTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(24.dp)) {
                QrPlate(SAMPLE_LINK, scanned = scanned, renewing = renewing, life = QrLife(phase, remaining), onRefresh = {})
            }
        }
    }
}

@Preview(name = "QR, dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrFreshDark() = PlateSample(dark = true, phase = QrPhase.Fresh, remaining = 0.7f)

@Preview(name = "QR expiring soon, dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrSoonDark() = PlateSample(dark = true, phase = QrPhase.ExpiringSoon, remaining = 0.15f)

@Preview(name = "QR scanned, dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrScannedDark() = PlateSample(dark = true, phase = QrPhase.Fresh, remaining = 0.5f, scanned = true)

@Preview(name = "QR expired, dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrExpiredDark() = PlateSample(dark = true, phase = QrPhase.Expired, remaining = 0f)

@Preview(name = "QR being replaced, dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrRenewingDark() = PlateSample(dark = true, phase = QrPhase.Renewing, remaining = 0f, renewing = true)

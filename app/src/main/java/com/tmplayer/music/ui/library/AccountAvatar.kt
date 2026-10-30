package com.tmplayer.music.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.music.data.Account
import com.tmplayer.music.data.AccountNames
import com.tmplayer.music.data.AccountProfile
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.ui.common.ImageCheck
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The signed-in user's picture: Telegram's small profile photo (160 px at most, from TDLib's own
 * file cache), or the name's initials on a neutral tile while it loads, when there is none or when
 * it could not be read. Never a Telegram logo. Decorative: the button around it carries the label.
 */
@Composable
internal fun AccountAvatar(profile: AccountProfile?, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    var image by remember(profile) { mutableStateOf(profile?.let { AvatarCache.get(it) }) }
    LaunchedEffect(profile) {
        if (profile == null || image != null || profile.photoFileId == 0) return@LaunchedEffect
        image = AvatarCache.load(profile, maxOf(px, AvatarCache.DECODE_PX))
    }
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        val initials = AccountNames.initials(profile?.name.orEmpty())
        when {
            shown != null -> Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            initials.isNotEmpty() -> Text(
                initials,
                color = colors.onSurface,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            else -> Icon(MusicIcons.Person, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/**
 * One decoded avatar for the whole app, for the profile it belongs to. Small (decoded down to
 * [DECODE_PX]), never polled, and forgotten with the profile at Log out (a new profile, or none,
 * no longer matches). A failed download or decode stays quiet: the initials show.
 */
internal object AvatarCache {
    const val DECODE_PX = 160
    private const val TAG = "AccountAvatar"

    @Volatile
    private var slot: Pair<AccountProfile, ImageBitmap>? = null

    /** Log out: the picture goes with the account. */
    fun clear() {
        slot = null
    }

    fun get(profile: AccountProfile): ImageBitmap? {
        val held = slot ?: return null
        if (Account.profile.value == null) slot = null
        return held.second.takeIf { held.first == profile }
    }

    suspend fun load(profile: AccountProfile, sizePx: Int): ImageBitmap? {
        get(profile)?.let { return it }
        val path = Account.photoPath(profile) ?: return null
        val decoded = decode(File(path), sizePx.coerceAtMost(DECODE_PX * 2)) ?: return null
        // Only kept while it is still the signed-in profile (not logged out while it downloaded).
        if (Account.profile.value == profile) slot = profile to decoded
        return decoded
    }

    private suspend fun decode(file: File, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !ImageCheck.isWhole(file)) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "could not decode the profile photo", e)
            null
        }
    }
}

package com.tmplayer.music.cast

import android.content.Context

/**
 * A store build's casting: none. It has no Cast framework, no MediaRouter and no local audio server
 * (AGENTS.md: they belong in the regular APK only), so Settings says Cast is not in this version and
 * no screen draws a Cast button. The regular build's [CastBuild] is in app/src/cast.
 */
internal object CastBuild {
    val noCastReason: CastAvailability = CastAvailability.NotInThisBuild

    /** Nothing to start. */
    fun initialize(context: Context) = Unit

    fun support(context: Context): CastSupport = NoCastSupport

    /** The session plays with the local player alone. */
    fun host(parts: CastHostParts): CastSetup = CastSetup(parts.local, NoCastHost)
}

package com.tmplayer.music.auto

/**
 * Who may connect to the media session at all, and so browse the library or hand it a queue. The
 * service is exported so the system's media controls, Bluetooth and car displays can reach the
 * session, but the queue and the library tree list the user's own titles and a controller can start
 * playback, so only the app itself, a system trusted caller and the known car, assistant and media
 * hosts get in (the connection is refused for any other app, and each browse or queue call checks
 * the same rule again).
 */
object BrowserAccess {
    /** The hosts that browse a media app for a car or by voice. */
    val CAR_HOSTS: Set<String> = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.autosimulator",
        "com.google.android.carassistant",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.automotive.media",
        "com.android.car.media",
        "com.android.systemui",
        "com.google.android.wearable.app",
        // The media keys of a headset and the system's own media dispatch.
        "com.android.bluetooth",
        "android",
    )

    fun allows(callerPackage: String?, ownPackage: String, trusted: Boolean): Boolean {
        if (callerPackage.isNullOrEmpty()) return false
        return callerPackage == ownPackage || trusted || callerPackage in CAR_HOSTS
    }
}

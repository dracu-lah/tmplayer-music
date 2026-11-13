package com.tmplayer.music.auto

/**
 * Who may browse the library through the media session. The service is exported so the system's
 * media controls, Bluetooth and car displays can reach the session, but the library tree lists the
 * user's own titles, so only the app itself, a system trusted caller and the known car and
 * assistant hosts may browse it. Any other app can still see and control playback as before; it
 * just gets no tree.
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
    )

    fun allows(callerPackage: String?, ownPackage: String, trusted: Boolean): Boolean {
        if (callerPackage.isNullOrEmpty()) return false
        return callerPackage == ownPackage || trusted || callerPackage in CAR_HOSTS
    }
}

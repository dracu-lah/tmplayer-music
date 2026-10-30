package com.tmplayer.music.data

import com.tmplayer.music.i18n.L

/** What the library is doing, in the order the banner and Settings name it. */
enum class LibraryPhase {
    Scanning,
    Reading,
    Paused,
    Waiting,

    /** Track details wait for Wi-Fi (mobile data without the user's yes); no banner, Settings says so. */
    WaitingWifi,
    UpToDate,
}

/**
 * What the banner and the Settings state line show. [line] is the banner's sentence, [sub] a quieter
 * second line or null, [progress] a fraction for a still (determinate) bar or null for none.
 */
data class LibraryView(
    val phase: LibraryPhase,
    val line: String,
    val sub: String?,
    val progress: Float?,
    /** Chats the last scan could not read, once it has ended: something the user has to look at. */
    val failedChats: Int = 0,
) {
    /** The banner is hidden once everything is read and nothing runs. */
    val showBanner: Boolean get() = phase != LibraryPhase.UpToDate && phase != LibraryPhase.WaitingWifi

    /** The short state for Settings. */
    val state: String
        get() = when (phase) {
            LibraryPhase.Scanning -> L.settingsStateScanning
            LibraryPhase.Reading -> L.settingsStateReading
            LibraryPhase.Paused -> L.settingsStatePaused
            LibraryPhase.Waiting -> L.settingsStateWaiting
            LibraryPhase.WaitingWifi -> L.chatfirstStateWaitingWifi
            LibraryPhase.UpToDate -> L.settingsStateUpToDate
        }
}

/**
 * Decides the words from the scanner's and the tag reader's status: plain logic, so it is tested
 * without a screen. [paused] is the user's Pause, [online] whether Telegram is connected, and
 * [heldForWifi] whether the background reading waits for Wi-Fi. Reading only what is on screen or
 * about to play ([EnrichStatus.background] false) is too small to announce.
 */
object LibraryStatus {
    fun view(scan: ScanStatus, tags: EnrichStatus, paused: Boolean, online: Boolean, heldForWifi: Boolean = false): LibraryView =
        progressView(scan, tags, paused, online, heldForWifi).copy(failedChats = if (scan.running) 0 else scan.failed.size)

    /**
     * What the banner above the library shows. With the progress banner on ([showProgress]), the
     * progress while something runs, else the chats that could not be read, else nothing. With it
     * hidden (Hide on the banner), only what needs the user: chats that could not be read. The mobile
     * data question is its own dialog and is asked either way. A scan that ends and a new one that
     * starts keep the choice: hidden stays hidden until the Settings switch brings it back.
     */
    fun banner(view: LibraryView?, showProgress: Boolean): Banner = when {
        view == null -> Banner.None
        showProgress && view.showBanner -> Banner.Progress
        view.failedChats > 0 -> Banner.Attention
        else -> Banner.None
    }

    /** The banner's three looks. */
    enum class Banner { None, Progress, Attention }

    private fun progressView(scan: ScanStatus, tags: EnrichStatus, paused: Boolean, online: Boolean, heldForWifi: Boolean): LibraryView {
        val counts = tags.counts
        val reading = tags.running && tags.background
        val busy = scan.running || reading
        val tagProgress = if (counts.total > 0) counts.finished.toFloat() / counts.total else null
        return when {
            paused && (busy || counts.pending > 0) -> LibraryView(
                LibraryPhase.Paused,
                if (counts.total > 0) L.scanPausedProgress(counts.finished, counts.total) else L.scanPaused,
                L.scanPausedSub,
                tagProgress,
            )
            scan.running && scan.waitingSeconds > 0 -> LibraryView(LibraryPhase.Waiting, L.scanWaiting(scan.waitingSeconds), null, null)
            busy && (!online || tags.waiting && !scan.running) -> LibraryView(LibraryPhase.Waiting, L.scanOffline, L.scanOfflineSub, tagProgress)
            scan.running -> {
                val total = scan.chatsTotal.coerceAtLeast(1)
                LibraryView(
                    LibraryPhase.Scanning,
                    L.scanRunning(minOf(scan.chatsDone + 1, total), total, scan.found),
                    L.scanKeepListening,
                    scan.chatsDone.toFloat() / total,
                )
            }
            reading -> LibraryView(
                LibraryPhase.Reading,
                L.scanReading(counts.finished, counts.total),
                L.scanKeepListening,
                tagProgress,
            )
            heldForWifi && counts.pending > 0 -> LibraryView(
                LibraryPhase.WaitingWifi,
                L.chatfirstHeldForWifi(counts.pending),
                null,
                tagProgress,
            )
            else -> LibraryView(LibraryPhase.UpToDate, L.settingsStateUpToDate, null, null)
        }
    }
}

package com.tmplayer.music.data

import com.tmplayer.music.i18n.L

/** What the library is doing, in the order the banner and Settings name it. */
enum class LibraryPhase { Scanning, Reading, Paused, Waiting, UpToDate }

/**
 * What the banner and the Settings state line show. [line] is the banner's sentence, [sub] a quieter
 * second line or null, [progress] a fraction for a still (determinate) bar or null for none.
 */
data class LibraryView(
    val phase: LibraryPhase,
    val line: String,
    val sub: String?,
    val progress: Float?,
) {
    /** The banner is hidden once everything is read and nothing runs. */
    val showBanner: Boolean get() = phase != LibraryPhase.UpToDate

    /** The short state for Settings. */
    val state: String
        get() = when (phase) {
            LibraryPhase.Scanning -> L.settingsStateScanning
            LibraryPhase.Reading -> L.settingsStateReading
            LibraryPhase.Paused -> L.settingsStatePaused
            LibraryPhase.Waiting -> L.settingsStateWaiting
            LibraryPhase.UpToDate -> L.settingsStateUpToDate
        }
}

/**
 * Decides the words from the scanner's and the tag reader's status: plain logic, so it is tested
 * without a screen. [paused] is the user's Pause, [online] whether Telegram is connected.
 */
object LibraryStatus {
    fun view(scan: ScanStatus, tags: EnrichStatus, paused: Boolean, online: Boolean): LibraryView {
        val counts = tags.counts
        val busy = scan.running || tags.running
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
            tags.running -> LibraryView(
                LibraryPhase.Reading,
                L.scanReading(counts.finished, counts.total),
                L.scanKeepListening,
                tagProgress,
            )
            else -> LibraryView(LibraryPhase.UpToDate, L.settingsStateUpToDate, null, null)
        }
    }
}

package com.tmplayer.music.library

/** Why a version plays by default, for the note on the Versions sheet. */
enum class PickReason {
    /** The user chose it for this song. */
    Preferred,

    /** It is the only version this build can play; the others are in a format it cannot decode. */
    OnlyPlayable,

    /** It is already on this device, so it plays at once and costs no data. */
    OnDevice,

    /** It is lossless and the next best is not. */
    Lossless,

    /** Save mobile data is on, and it is the smallest. */
    SmallerForData,

    /** It has the higher bit rate. */
    HigherQuality,

    /** Its tags are fuller (album, year, track number, cover). */
    FullerTags,

    /** Nothing else told them apart, so the first one posted wins. */
    Oldest,
}

/**
 * Which version of a song plays by default. The order is: the user's own choice,
 * then one this build can play, then one already on the device, then lossless over lossy (or, with Save
 * mobile data, the smaller file), then the higher bit rate, then the fuller tags, then the oldest post.
 * Every step is a plain comparison, so the pick is the same whatever order the versions come in.
 */
object TrackVersions {

    /** The version that plays by default, or null when there are none. */
    fun pick(
        versions: List<Track>,
        onDevice: Set<Int> = emptySet(),
        preferredUniqueId: String? = null,
        saveData: Boolean = false,
    ): Track? = ranked(versions, onDevice, preferredUniqueId, saveData).firstOrNull()

    /**
     * Why [pick] chose what it chose: the first rule that set it apart from the runner up. Null when
     * there is nothing to choose between (no versions, or only one).
     */
    fun reason(
        versions: List<Track>,
        onDevice: Set<Int> = emptySet(),
        preferredUniqueId: String? = null,
        saveData: Boolean = false,
    ): PickReason? {
        val ranked = ranked(versions, onDevice, preferredUniqueId, saveData)
        val best = ranked.getOrNull(0) ?: return null
        val next = ranked.getOrNull(1) ?: return null
        val steps = steps(onDevice, preferredUniqueId, saveData)
        return steps.firstOrNull { it.second.compare(best, next) != 0 }?.first ?: PickReason.Oldest
    }

    /** The best format among the versions, for the badge on the row: lossless first, then bit rate. */
    fun bestFormat(versions: List<Track>): AudioFormat =
        versions.minWithOrNull(
            compareByDescending<Track> { AudioKinds.playable(it.format) }
                .thenByDescending { it.format.lossless }
                .thenByDescending { bitrateKbps(it) }
                .thenBy { it.format.ordinal },
        )?.format ?: AudioFormat.OTHER

    /**
     * The bit rate the track reports, or one estimated from its size and length when it reports none.
     * 0 when neither is known.
     */
    internal fun bitrateKbps(track: Track): Long {
        if (track.bitrateKbps > 0) return track.bitrateKbps.toLong()
        if (track.sizeBytes <= 0 || track.durationMs <= 0) return 0
        // bits / milliseconds is kilobits per second; the size is capped so the product cannot overflow.
        return minOf(track.sizeBytes, Long.MAX_VALUE / 8) * 8 / track.durationMs
    }

    /** How many of the optional tags are filled, so a better tagged copy shows and plays first. */
    internal fun tagCount(track: Track): Int {
        var count = 0
        if (track.album.isNotBlank()) count++
        if (track.albumArtist.isNotBlank()) count++
        if (track.year > 0) count++
        if (track.trackNumber > 0) count++
        if (track.coverKey.isNotBlank() || track.thumbFileId != 0) count++
        return count
    }

    private fun ranked(
        versions: List<Track>,
        onDevice: Set<Int>,
        preferredUniqueId: String?,
        saveData: Boolean,
    ): List<Track> {
        if (versions.isEmpty()) return emptyList()
        val order = steps(onDevice, preferredUniqueId, saveData)
            .map { it.second }
            .reduce { a, b -> a.then(b) }
            .then(GroupKey.OLDEST_FIRST)
        return versions.sortedWith(order)
    }

    /** Each rule with the reason it gives, best first; a comparator puts the better track first. */
    private fun steps(
        onDevice: Set<Int>,
        preferredUniqueId: String?,
        saveData: Boolean,
    ): List<Pair<PickReason, Comparator<Track>>> {
        val preferred = preferredUniqueId?.takeIf { it.isNotBlank() }
        val steps = mutableListOf<Pair<PickReason, Comparator<Track>>>(
            PickReason.Preferred to compareByDescending { preferred != null && it.uniqueId == preferred },
            PickReason.OnlyPlayable to compareByDescending { AudioKinds.playable(it.format) },
            PickReason.OnDevice to compareByDescending { it.fileId in onDevice },
        )
        if (saveData) {
            // An unknown size sorts last: it may well be the biggest.
            steps += PickReason.SmallerForData to compareBy { if (it.sizeBytes > 0) it.sizeBytes else Long.MAX_VALUE }
        } else {
            steps += PickReason.Lossless to compareByDescending { it.format.lossless }
        }
        steps += PickReason.HigherQuality to compareByDescending { bitrateKbps(it) }
        steps += PickReason.FullerTags to compareByDescending { tagCount(it) }
        steps += PickReason.Oldest to GroupKey.OLDEST_FIRST
        return steps
    }
}

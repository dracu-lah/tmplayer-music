// Adapted from TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/ReleaseVersion.kt.
package com.tmplayer.music.data

/**
 * The release numbering, the same as `.github/workflows/release.yml` derives from a tag: vX.Y.Z is
 * a release, vX.Y.Z-beta.N a test version (a GitHub pre-release), and the versionCode is
 * major*1000000 + minor*10000 + patch*100 + stage, where a test version N is stage N and the
 * release 99, so a test version sits between the release before it and its own release.
 */
object ReleaseVersion {

    /** The stage of a release, after all of its test versions. */
    const val FINAL_STAGE = 99

    /** The highest N a `-beta.N` may have. */
    const val MAX_BETA = 98

    /** A version split into its numbers; [stage] as described on the object. */
    data class Parts(val major: Int, val minor: Int, val patch: Int, val stage: Int) {
        val isTest: Boolean get() = stage in 1..MAX_BETA
    }

    private val BETA = Regex("""beta\.(\d{1,3})""")

    /** Any version string ("0.2.0", "v0.2.0-beta.3"), or null when its three fields are not numbers. */
    fun parse(version: String): Parts? {
        val clean = version.trim().take(64).removePrefix("v").removePrefix("V").substringBefore('+')
        val core = clean.substringBefore('-')
        val suffix = clean.substringAfter('-', "")
        val fields = core.split('.')
        if (fields.size != 3) return null
        val numbers = fields.map { field ->
            field.takeIf { it.isNotEmpty() && it.length <= 6 && it.all { c -> c in '0'..'9' } }?.toIntOrNull() ?: return null
        }
        val stage = when {
            suffix.isEmpty() -> FINAL_STAGE
            else -> BETA.matchEntire(suffix)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..MAX_BETA } ?: 0
        }
        return Parts(numbers[0], numbers[1], numbers[2], stage)
    }

    /** Whether [version] is a test version (`-beta.N`), the kind a GitHub pre-release carries. */
    fun isTest(version: String): Boolean = parse(version)?.isTest == true

    /** The versionCode the release workflow gives [version], or null when it does not fit. */
    fun androidCode(version: String): Int? {
        val p = parse(version) ?: return null
        if (p.minor > 99 || p.patch > 99) return null
        val code = p.major.toLong() * 1_000_000 + p.minor * 10_000L + p.patch * 100L + p.stage
        return code.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
}

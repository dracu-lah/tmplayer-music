package com.tmplayer.music.platform

/** What Settings, About shows and links to, and how the version reads there and in Send logs. */
object About {

    /** The legal page: terms, privacy and takedown requests. */
    const val LEGAL_URL = "https://tmplayer.org/legal"

    /**
     * Where feedback goes: the project's GitHub issues, from the repository's `origin` remote
     * (`git@github.com:dracu-lah/tmplayer-music.git`). Opened bare: nothing from the library is
     * put in the link.
     */
    const val FEEDBACK_URL = "https://github.com/dracu-lah/tmplayer-music/issues"

    /** The licence text and the OpenSSL exception beside it, in the source repository. */
    const val LICENCE_URL = "https://github.com/dracu-lah/tmplayer-music/blob/main/LICENSE"
    const val OPENSSL_EXCEPTION_URL = "https://github.com/dracu-lah/tmplayer-music/blob/main/LICENSE-OPENSSL-EXCEPTION.md"

    /** The licences of the work the app ships from others, such as the Hugeicons icons (MIT). */
    const val THIRD_PARTY_URL = "https://github.com/dracu-lah/tmplayer-music/blob/main/THIRD_PARTY_NOTICES.md"

    /** The source repository: Star on GitHub opens it. */
    const val SOURCE = "https://github.com/dracu-lah/tmplayer-music"

    /**
     * The two ways to chip in, the same pages TMPlayer and `.github/FUNDING.yml` link. nevil.dev is
     * the right Buy Me a Coffee page (buymeacoffee.com/nevil is somebody else's).
     */
    const val COFFEE = "https://buymeacoffee.com/nevil.dev"
    const val SPONSORS = "https://github.com/sponsors/dracu-lah"

    /**
     * The version as one short text: name, code and build type, `0.1.0 (1), release`. A blank
     * name reads as `?`, a negative code and a blank type are left out, so a broken build still
     * says what it can.
     */
    fun version(name: String, code: Long, buildType: String): String {
        val shownName = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).ifEmpty { "?" }
        val withCode = if (code >= 0) "$shownName ($code)" else shownName
        val type = buildType.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_NAME)
        return if (type.isEmpty()) withCode else "$withCode, $type"
    }

    private const val MAX_NAME = 40
}

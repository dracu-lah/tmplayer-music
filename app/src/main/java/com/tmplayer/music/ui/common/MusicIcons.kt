package com.tmplayer.music.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Every icon the app's screens draw, in one place. The shapes are the free Hugeicons "Stroke Rounded"
 * set (hugeicons.com, MIT licence, see THIRD_PARTY_NOTICES.md), copied as path data from the SVGs in
 * github.com/hugeicons/hugeicons and drawn as 1.5 wide round strokes on a 24 dp grid, as in the
 * originals. The colour is black here and replaced by the `Icon` tint (LocalContentColor by default).
 * The name in each comment is the Hugeicons icon it comes from.
 *
 * Icon shapes: Copyright (c) 2025 Hugeicons, MIT License. The full notice is in THIRD_PARTY_NOTICES.md.
 */
object MusicIcons {
    /** play */
    val Play = icon(
        "Play",
        "M18.8906 12.846C18.5371 14.189 16.8667 15.138 13.5257 17.0361C10.296 18.8709 8.6812 19.7884 7.37983 19.4196" +
            "C6.8418 19.2671 6.35159 18.9776 5.95624 18.5787C5 17.6139 5 15.7426 5 12C5 8.2574 5 6.3861 5.95624 5.42132" +
            "C6.35159 5.02245 6.8418 4.73288 7.37983 4.58042C8.6812 4.21165 10.296 5.12907 13.5257 6.96393" +
            "C16.8667 8.86197 18.5371 9.811 18.8906 11.154C19.0365 11.7084 19.0365 12.2916 18.8906 12.846Z",
    )

    /** pause */
    val Pause = icon(
        "Pause",
        "M4 7C4 5.58579 4 4.87868 4.43934 4.43934C4.87868 4 5.58579 4 7 4C8.41421 4 9.12132 4 9.56066 4.43934" +
            "C10 4.87868 10 5.58579 10 7V17C10 18.4142 10 19.1213 9.56066 19.5607C9.12132 20 8.41421 20 7 20" +
            "C5.58579 20 4.87868 20 4.43934 19.5607C4 19.1213 4 18.4142 4 17V7Z" +
            "M14 7C14 5.58579 14 4.87868 14.4393 4.43934C14.8787 4 15.5858 4 17 4C18.4142 4 19.1213 4 19.5607 4.43934" +
            "C20 4.87868 20 5.58579 20 7V17C20 18.4142 20 19.1213 19.5607 19.5607C19.1213 20 18.4142 20 17 20" +
            "C15.5858 20 14.8787 20 14.4393 19.5607C14 19.1213 14 18.4142 14 17V7Z",
    )

    /** next */
    val Next = icon(
        "Next",
        "M15.9351 12.6258C15.6807 13.8374 14.327 14.7077 11.6198 16.4481C8.67528 18.3411 7.20303 19.2876 6.01052 18.9229" +
            "C5.60662 18.7994 5.23463 18.5823 4.92227 18.2876C4 17.4178 4 15.6118 4 12C4 8.38816 4 6.58224 4.92227 5.71235" +
            "C5.23463 5.41773 5.60662 5.20057 6.01052 5.07707C7.20304 4.71243 8.67528 5.6589 11.6198 7.55186" +
            "C14.327 9.29233 15.6807 10.1626 15.9351 11.3742C16.0216 11.7865 16.0216 12.2135 15.9351 12.6258Z" +
            "M20 5V19",
    )

    /** previous */
    val Previous = icon(
        "Previous",
        "M8.06492 12.6258C8.31931 13.8374 9.67295 14.7077 12.3802 16.4481C15.3247 18.3411 16.797 19.2876 17.9895 18.9229" +
            "C18.3934 18.7994 18.7654 18.5823 19.0777 18.2876C20 17.4178 20 15.6118 20 12C20 8.38816 20 6.58224 19.0777 5.71235" +
            "C18.7654 5.41773 18.3934 5.20057 17.9895 5.07707C16.797 4.71243 15.3247 5.6589 12.3802 7.55186" +
            "C9.67295 9.29233 8.31931 10.1626 8.06492 11.3742C7.97836 11.7865 7.97836 12.2135 8.06492 12.6258Z" +
            "M4 4L4 20",
    )

    /** shuffle */
    val Shuffle = icon(
        "Shuffle",
        "M19.5576 4L20.4551 4.97574C20.8561 5.41165 21.0566 5.62961 20.9861 5.81481C20.9155 6 20.632 6 20.0649 6" +
            "C18.7956 6 17.2771 5.79493 16.1111 6.4733C15.3903 6.89272 14.8883 7.62517 14.0392 9" +
            "M3 18H4.58082C6.50873 18 7.47269 18 8.2862 17.5267C9.00708 17.1073 9.50904 16.3748 10.3582 15" +
            "M19.5576 20L20.4551 19.0243C20.8561 18.5883 21.0566 18.3704 20.9861 18.1852C20.9155 18 20.632 18 20.0649 18" +
            "C18.7956 18 17.2771 18.2051 16.1111 17.5267C15.2976 17.0534 14.7629 16.1815 13.6935 14.4376L10.7038 9.5624" +
            "C9.63441 7.81853 9.0997 6.9466 8.2862 6.4733C7.47269 6 6.50873 6 4.58082 6H3",
    )

    private const val REPEAT_ARROWS =
        "M16.3884 3L17.3913 3.97574C17.8393 4.41165 18.0633 4.62961 17.9844 4.81481C17.9056 5 17.5888 5 16.9552 5" +
            "H9.19422C5.22096 5 2 8.13401 2 12C2 13.4872 2.47668 14.8662 3.2895 16" +
            "M7.61156 21L6.60875 20.0243C6.16074 19.5883 5.93673 19.3704 6.01557 19.1852C6.09441 19 6.4112 19 7.04478 19" +
            "H14.8058C18.779 19 22 15.866 22 12C22 10.5128 21.5233 9.13383 20.7105 8"

    /** repeat */
    val Repeat = icon("Repeat", REPEAT_ARROWS)

    /** repeat-one-01 */
    val RepeatOne = icon("RepeatOne", REPEAT_ARROWS + "M13 15L13 9.31633C13 9.05613 12.7178 8.90761 12.52 9.06373L11 10.2636")

    /** list-music */
    val Queue = icon(
        "Queue",
        "M2 5H14M2 12H9M2 19H9" +
            "M18 16V5C18 5 19 8.5 22 8.5M18 16C18 17.6569 16.6569 19 15 19C13.3431 19 12 17.6569 12 16" +
            "C12 14.3431 13.3431 13 15 13C16.6569 13 18 14.3431 18 16Z",
    )

    /** drag-drop-horizontal: the six dots of a drag handle. */
    val Drag = icon(
        "Drag",
        dot(6f, 9f) + dot(12f, 9f) + dot(18f, 9f) + dot(6f, 15f) + dot(12f, 15f) + dot(18f, 15f),
    )

    /** arrow-up-01 */
    val Up = icon("Up", "M17.9998 15C17.9998 15 13.5809 9.00001 11.9998 9C10.4187 8.99999 5.99985 15 5.99985 15")

    /** arrow-down-01 */
    val Down = icon("Down", "M18 9.00005C18 9.00005 13.5811 15 12 15C10.4188 15 6 9 6 9")

    /** arrow-down-01, for the opener of a drop down list. */
    val DropDown = Down

    /** arrow-left-01, mirrored in right to left layouts. */
    val Back = icon("Back", "M15 6C15 6 9.00001 10.4189 9 12C8.99999 13.5812 15 18 15 18", autoMirror = true)

    private const val HEART =
        "M10.4107 19.9677C7.58942 17.858 2 13.0348 2 8.69444C2 5.82563 4.10526 3.5 7 3.5C8.5 3.5 10 4 12 6" +
            "C14 4 15.5 3.5 17 3.5C19.8947 3.5 22 5.82563 22 8.69444C22 13.0348 16.4106 17.858 13.5893 19.9677" +
            "C12.6399 20.6776 11.3601 20.6776 10.4107 19.9677Z"

    /** favourite, filled: the free set has strokes only, so the same outline is filled to show "on". */
    val Heart = icon("Heart", HEART, filled = true)

    /** favourite */
    val HeartOutline = icon("HeartOutline", HEART)

    /** search-01 */
    val Search = icon(
        "Search",
        "M17 17L21 21M19 11C19 6.58172 15.4183 3 11 3C6.58172 3 3 6.58172 3 11C3 15.4183 6.58172 19 11 19" +
            "C15.4183 19 19 15.4183 19 11Z",
    )

    /** more-vertical */
    val More = icon(
        "More",
        "M11.9967 12.5V12M11.9967 6.5V6M11.9967 18.5V18" +
            "M12.9967 12.5C12.9967 11.9477 12.549 11.5 11.9967 11.5C11.4444 11.5 10.9967 11.9477 10.9967 12.5" +
            "C10.9967 13.0523 11.4444 13.5 11.9967 13.5C12.549 13.5 12.9967 13.0523 12.9967 12.5Z" +
            "M12.9967 6.5C12.9967 5.94772 12.549 5.5 11.9967 5.5C11.4444 5.5 10.9967 5.94772 10.9967 6.5" +
            "C10.9967 7.05228 11.4444 7.5 11.9967 7.5C12.549 7.5 12.9967 7.05228 12.9967 6.5Z" +
            "M12.9967 18.5C12.9967 17.9477 12.549 17.5 11.9967 17.5C11.4444 17.5 10.9967 17.9477 10.9967 18.5" +
            "C10.9967 19.0523 11.4444 19.5 11.9967 19.5C12.549 19.5 12.9967 19.0523 12.9967 18.5Z",
    )

    /** audio-wave-01: the playing song in a list. Never animated. */
    val Equalizer = icon("Equalizer", "M9 3V21M6 7V17M12 6V18M15 9L15 15M18 7L18 17M21 11L21 13M3 11L3 13")

    /** audio-wave-01 with every line cut to the shortest: the current song, paused. */
    val EqualizerPaused = icon(
        "EqualizerPaused",
        "M3 11L3 13M6 11L6 13M9 11L9 13M12 11L12 13M15 11L15 13M18 11L18 13M21 11L21 13",
    )

    /** cancel-01 */
    val Close = icon("Close", "M18 6L6.00081 17.9992M17.9992 18L6 6.00085")

    /** text-align-left: lines of text, for the lyrics toggle. */
    val Lyrics = icon("Lyrics", "M3 3H21M3 9H11M3 15H21M3 21H11")

    /** moon-02: a crescent moon, for the sleep timer. */
    val Sleep = icon(
        "Sleep",
        "M21.5 14.0784C20.3003 14.7189 18.9301 15.0821 17.4751 15.0821C12.7491 15.0821 8.91792 11.2509 8.91792 6.52485" +
            "C8.91792 5.06986 9.28105 3.69968 9.92163 2.5C5.66765 3.49698 2.5 7.31513 2.5 11.8731" +
            "C2.5 17.1899 6.8101 21.5 12.1269 21.5C16.6849 21.5 20.503 18.3324 21.5 14.0784Z",
    )

    /** A ring of radius 1 at ([x], [y]): the drag-drop-horizontal dots, written once instead of six times. */
    private fun dot(x: Float, y: Float): String =
        "M${x + 1} ${y}C${x + 1} ${y + 0.5523f} ${x + 0.5523f} ${y + 1} $x ${y + 1}" +
            "C${x - 0.5523f} ${y + 1} ${x - 1} ${y + 0.5523f} ${x - 1} ${y}" +
            "C${x - 1} ${y - 0.5523f} ${x - 0.5523f} ${y - 1} $x ${y - 1}" +
            "C${x + 0.5523f} ${y - 1} ${x + 1} ${y - 0.5523f} ${x + 1} ${y}Z"

    private fun icon(name: String, path: String, filled: Boolean = false, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        )
            .addPath(
                pathData = addPathNodes(path),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}

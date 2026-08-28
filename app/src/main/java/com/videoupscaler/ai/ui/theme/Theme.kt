package com.videoupscaler.ai.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorContext
import androidx.compose.material3.dynamicLightColorContext
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for API 24-30. Dynamic colour needs API 31+.
private val FallbackDark =
    darkColorScheme(
        primary = Color(0xFF9CCBFF),
        onPrimary = Color(0xFF003355),
        primaryContainer = Color(0xFF004A78),
        onPrimaryContainer = Color(0xFFD0E4FF),
        secondary = Color(0xFFBBC7D9),
        onSecondary = Color(0xFF25313F),
        background = Color(0xFF0F1113),
        onBackground = Color(0xFFDFE2E6),
        surface = Color(0xFF0F1113),
        onSurface = Color(0xFFDFE2E6),
    )

private val FallbackLight =
    lightColorScheme(
        primary = Color(0xFF00629D),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD0E4FF),
        onPrimaryContainer = Color(0xFF001D34),
        secondary = Color(0xFF526070),
        onSecondary = Color(0xFFFFFFFF),
    )

/**
 * Material 3 with dynamic colour where available.
 *
 * Dark is the default rather than following the system, per DESIGN.md 7.1: a video app's UI should
 * recede so the content reads.
 *
 * The preview surface itself is always neutral black in both themes — device-manufactured accent
 * colours must not tint the canvas the user is judging colour accuracy on. See [PreviewSurfaceColor].
 */
@Composable
fun UpScalerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = true

    val scheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorContext(context) else dynamicLightColorContext(context)

            else -> if (dark) FallbackDark else FallbackLight
        }

    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Fixed neutral backdrop behind video, independent of the colour scheme.
 *
 * Not `Color.Black` because a pure-black surround can make dark footage look lifted by contrast;
 * a near-black that is not the scheme's surface colour keeps the frame edge readable without
 * competing with the image.
 */
val PreviewSurfaceColor: Color = Color(0xFF000000)

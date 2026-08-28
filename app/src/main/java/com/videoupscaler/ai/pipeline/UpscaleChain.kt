package com.videoupscaler.ai.pipeline

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.LanczosResample

/**
 * Builds the shared effect chain.
 *
 * This list is the single definition of "enhanced". It is fed to `ExoPlayer.setVideoEffects` for
 * preview and to `EditedMediaItem.Builder.setEffects` for export, so the two paths cannot drift
 * visually (PLAN.md 3.1).
 *
 * Phase 1 implements Tier 0 only: a Lanczos resample. There is no ML model in this repository, so
 * nothing here runs inference. The AI tier is inserted into this same list in Phase 2 (PLAN.md 3.2)
 * — that is the whole point of having one list rather than two code paths.
 */
@UnstableApi
object UpscaleChain {

    /** Output resolutions offered to the user, as (long edge, short edge). */
    enum class Target(val label: String, val longEdge: Int, val shortEdge: Int) {
        HD("720p", 1280, 720),
        FULL_HD("1080p", 1920, 1080),
        QHD("1440p", 2560, 1440),
    }

    /**
     * Returns the effect chain for [target].
     *
     * Uses `LanczosResample.scaleToFitWithFlexibleOrientation` rather than `scaleToFit` so that
     * portrait phone video is upscaled to 1080x1920 instead of being letterboxed into 1920x1080.
     *
     * Note: `LanczosResample` has NO public constructor. It is created only through the static
     * factories `scaleToFit(w, h)` and `scaleToFitWithFlexibleOrientation(a, b)`.
     */
    fun build(target: Target): List<Effect> =
        listOf(
            LanczosResample.scaleToFitWithFlexibleOrientation(target.longEdge, target.shortEdge)
        )
}

package com.videoupscaler.ai.pipeline

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Effect
import androidx.media3.effect.LanczosResample
import com.videoupscaler.ai.ai.AiUpscaleEffect

/**
 * The single source of truth for the effect chain.
 *
 * Both hosts consume this: `ExoPlayer.setVideoEffects` for preview and
 * `EditedMediaItem.setEffects` for export. That shared-chain property is the whole reason preview
 * cannot silently drift from export (DESIGN.md §2) — there is only one list, so there is nothing to
 * drift.
 */
@UnstableApi
object UpscaleChain {

    /** Output long edge. Named by what the user gets, not by the scale factor. */
    enum class Target(val label: String, val longEdge: Int, val shortEdge: Int) {
        HD("720p", 1280, 720),
        FULL_HD("1080p", 1920, 1080),
        QHD("1440p", 2560, 1440),
    }

    /**
     * Ordered by temporal determinism, per PLAN.md §4. Deterministic stages first, learned stages
     * after, GAN-style stages last and opt-in.
     */
    enum class Tier(val labelRes: Int) {
        /** Tier 0: Lanczos resample. 1-3 ms/frame, no model, no device variance. */
        SMOOTH(com.videoupscaler.ai.R.string.tier_smooth),

        /** Tier 2: ESPCN sub-pixel CNN. Adds real detail; costs one inference per frame. */
        ENHANCED(com.videoupscaler.ai.R.string.tier_enhanced),
    }

    /** The ESPCN model's static input. Fixed shape is what the GPU delegate wants, and it is the
     *  geometry the Phase 0 benchmark in PLAN.md §7 specifies. */
    const val MODEL_LONG_EDGE = 640
    const val MODEL_SHORT_EDGE = 360
    const val MODEL_SCALE = 2

    /**
     * Builds the chain for a target and tier.
     *
     * For [Tier.ENHANCED] the frame is first normalised to the model's exact input size, then
     * upscaled 2x by the network, then resampled to the requested target. Two consequences:
     *
     *  - The model is sub-pixel, so it is integer-scale only. Non-integer targets are reached by
     *    upscaling 2x and resampling down — never by asking the network for a fractional factor.
     *  - The model input is landscape (640x360). Portrait sources cannot be fed to it without a
     *    rotation stage or a second set of weights. [AiUpscaleEffect] rejects the mismatch with a
     *    `VideoFrameProcessingException` rather than producing a distorted frame.
     */
    fun build(target: Target, tier: Tier = Tier.SMOOTH): List<Effect> =
        when (tier) {
            Tier.SMOOTH ->
                listOf(
                    LanczosResample.scaleToFitWithFlexibleOrientation(
                        target.longEdge,
                        target.shortEdge,
                    )
                )

            Tier.ENHANCED ->
                listOf(
                    // Normalise to the model's input. Done on the GPU, before the readback, so the
                    // CPU-side copy stays as small as possible.
                    LanczosResample.scaleToFitWithFlexibleOrientation(
                        MODEL_LONG_EDGE,
                        MODEL_SHORT_EDGE,
                    ),
                    AiUpscaleEffect(assetName = "espcn_x2.tflite", scale = MODEL_SCALE),
                    LanczosResample.scaleToFitWithFlexibleOrientation(
                        target.longEdge,
                        target.shortEdge,
                    ),
                )
        }

    /** Whether [tier] can run on a frame of this shape. Cheap pre-flight so the UI can say no with
     *  a reason instead of the export failing a minute in. */
    fun isSupported(tier: Tier, width: Int, height: Int): Boolean =
        when (tier) {
            Tier.SMOOTH -> true
            Tier.ENHANCED -> width >= height  // model input is landscape
        }
}

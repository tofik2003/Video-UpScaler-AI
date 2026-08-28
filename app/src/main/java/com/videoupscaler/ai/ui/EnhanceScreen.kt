package com.videoupscaler.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videoupscaler.ai.R
import com.videoupscaler.ai.pipeline.UpscaleChain
import com.videoupscaler.ai.pipeline.VideoExporter
import com.videoupscaler.ai.ui.theme.PreviewSurfaceColor

/**
 * The workspace: preview, tune, export.
 *
 * Layout follows DESIGN.md 5.1 — quality and resolution are the only two settings most users will
 * ever touch, so nothing else is on this screen.
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EnhanceScreen(viewModel: EnhanceViewModel, onPickVideo: () -> Unit) {
    val context = LocalContext.current
    val uri = viewModel.inputUri

    val player =
        remember(uri) {
            uri?.let {
                ExoPlayer.Builder(context)
                    .build()
                    .apply {
                        setMediaItem(MediaItem.fromUri(it))
                        prepare()
                        playWhenReady = true
                    }
            }
        }

    DisposableEffect(uri) {
        val current = player
        onDispose { current?.release() }
    }

    // Re-applies when the target or the comparison toggle changes. Without this the preview keeps
    // the old chain while the export uses the new one.
    // viewModel.tier must be both a key and an argument. Omitting it from the call silently
    // defaults to SMOOTH, so the preview shows Smooth while the export uses the selected tier —
    // exactly the preview/export divergence DESIGN.md 2 exists to prevent.
    LaunchedEffect(player, viewModel.showEnhanced, viewModel.target, viewModel.tier) {
        player?.setVideoEffects(
            if (viewModel.showEnhanced) {
                UpscaleChain.build(viewModel.target, viewModel.tier)
            } else {
                emptyList()
            }
        )
    }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // errorCodeName is a function, not a Kotlin property — Media3 overloads
                    // getErrorCodeName(int) statically, which suppresses property synthesis.
                    viewModel.reportPlaybackError(error.getErrorCodeName())
                }
            }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
        ) {
            PreviewSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                player = player,
                showEnhanced = viewModel.showEnhanced,
                enabled = uri != null,
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExportStateSection(viewModel)

                StatusSection(viewModel, onPickVideo)

                TierSection(
                    tier = viewModel.tier,
                    enabled = uri != null && !viewModel.isRunning,
                    onChange = viewModel::setTier,
                )

                viewModel.tierWarning?.let {
                    Text(
                        text = stringResource(R.string.error_orientation),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                CompareToggle(
                    showEnhanced = viewModel.showEnhanced,
                    enabled = uri != null,
                    onChange = { viewModel.showEnhanced = it },
                )

                ResolutionSection(
                    target = viewModel.target,
                    enabled = uri != null && !viewModel.isRunning,
                    onChange = { viewModel.target = it },
                )

                Spacer(Modifier.height(4.dp))

                if (viewModel.isRunning) {
                    OutlinedButton(
                        onClick = viewModel::cancelExport,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_cancel)) }
                } else {
                    Button(
                        onClick = viewModel::startExport,
                        enabled = uri != null && viewModel.playbackError == null && viewModel.tierWarning == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_enhance)) }
                }
            }
        }
    }
}

@Composable
private fun PreviewSurface(
    modifier: Modifier = Modifier,
    player: ExoPlayer?,
    showEnhanced: Boolean,
    enabled: Boolean,
) {
    // DESIGN.md 7.1: always neutral black, never the theme surface. Dynamic colour would tint the
    // canvas the user is judging colour accuracy on.
    Box(
        modifier = modifier.background(PreviewSurfaceColor),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled && player != null) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx) },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.status_no_video),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // The mode badge is load-bearing: it says which pipeline is actually on screen.
        if (enabled) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(
                    text =
                        stringResource(
                            if (showEnhanced) R.string.badge_enhanced else R.string.badge_original
                        ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun CompareToggle(showEnhanced: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.label_compare),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showEnhanced,
                onClick = { onChange(false) },
                enabled = enabled,
                label = { Text(stringResource(R.string.compare_original)) },
            )
            FilterChip(
                selected = showEnhanced,
                onClick = { onChange(true) },
                enabled = enabled,
                label = { Text(stringResource(R.string.compare_enhanced)) },
            )
        }
    }
}

@Composable
private fun TierSection(
    tier: UpscaleChain.Tier,
    enabled: Boolean,
    onChange: (UpscaleChain.Tier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.label_quality), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpscaleChain.Tier.entries.forEach { option ->
                FilterChip(
                    selected = option == tier,
                    onClick = { onChange(option) },
                    enabled = enabled,
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
        if (tier == UpscaleChain.Tier.ENHANCED) {
            Text(
                text = stringResource(R.string.tier_enhanced_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResolutionSection(
    target: UpscaleChain.Target,
    enabled: Boolean,
    onChange: (UpscaleChain.Target) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.label_resolution),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpscaleChain.Target.entries.forEach { option ->
                FilterChip(
                    selected = option == target,
                    onClick = { onChange(option) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun StatusSection(viewModel: EnhanceViewModel, onPickVideo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPickVideo, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_pick_video))
        }

        viewModel.playbackError?.let { code ->
            Text(
                text = stringResource(R.string.error_playback, code),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text =
                stringResource(
                    R.string.label_free_space,
                    if (viewModel.freeSpaceMb >= 0) viewModel.freeSpaceMb.toString() else "—"
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportStateSection(viewModel: EnhanceViewModel) {
    when (val state = viewModel.exportState) {
        is VideoExporter.State.Running ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.status_exporting, state.percent),
                        fontWeight = FontWeight.Medium,
                    )
                }
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

        is VideoExporter.State.Completed ->
            Text(
                text = stringResource(R.string.status_done, state.outputPath),
                style = MaterialTheme.typography.bodyMedium,
            )

        is VideoExporter.State.Failed ->
            Text(
                text = stringResource(R.string.status_failed, state.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

        is VideoExporter.State.Notice ->
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        VideoExporter.State.Cancelled ->
            Text(
                text = stringResource(R.string.status_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        VideoExporter.State.Idle -> Unit
    }
}

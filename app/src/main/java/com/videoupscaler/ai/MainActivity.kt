package com.videoupscaler.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videoupscaler.ai.pipeline.UpscaleChain
import com.videoupscaler.ai.pipeline.VideoExporter

/**
 * Phase 1 workspace: pick a video, preview it through the effect chain, export it.
 *
 * The UI is plain Views rather than Compose. That is a deliberate deferral, not an oversight:
 * Compose needs the Kotlin Compose compiler plugin, and its interaction with AGP 9's built-in
 * Kotlin support is not something that could be verified before writing this. Getting one thing
 * compiling comes first. The pipeline classes in `pipeline/` are UI-agnostic precisely so the
 * Compose rewrite described in DESIGN.md does not have to touch them.
 *
 * Views are built in code to keep the skeleton free of resource files.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var pickButton: Button
    private lateinit var enhanceButton: Button
    private lateinit var playerView: PlayerView

    private var player: ExoPlayer? = null
    private var inputUri: Uri? = null
    private val exporter by lazy { VideoExporter(this) }

    /** SAF picker. Grants read access without any storage permission. */
    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            // Persist the grant so a queued export survives process death and reboot.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            inputUri = uri
            loadPreview(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }

        statusText = TextView(this).apply { text = getString(R.string.status_no_video) }
        root.addView(statusText)

        playerView = PlayerView(this)
        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                /* weight = */ 1f,
            ),
        )

        resolutionSpinner =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        UpscaleChain.Target.entries.map { it.label },
                    )
                setSelection(UpscaleChain.Target.FULL_HD.ordinal)
            }
        root.addView(resolutionSpinner)

        pickButton =
            Button(this).apply {
                text = getString(R.string.action_pick_video)
                setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
            }
        root.addView(pickButton)

        enhanceButton =
            Button(this).apply {
                text = getString(R.string.action_enhance)
                isEnabled = false
                setOnClickListener { startExport() }
            }
        root.addView(enhanceButton)

        setContentView(root)
    }

    private fun selectedTarget(): UpscaleChain.Target =
        UpscaleChain.Target.entries[resolutionSpinner.selectedItemPosition]

    private fun loadPreview(uri: Uri) {
        player?.release()

        // setVideoEffects must be called before prepare(). The same chain instance semantics
        // apply to export, which is what keeps preview and output consistent.
        val instance = ExoPlayer.Builder(this).build()
        instance.setVideoEffects(UpscaleChain.build(selectedTarget()))
        instance.setMediaItem(MediaItem.fromUri(uri))
        instance.prepare()
        instance.playWhenReady = true

        playerView.player = instance
        player = instance

        statusText.text = getString(R.string.status_loaded)
        enhanceButton.isEnabled = true
    }

    private fun startExport() {
        val uri = inputUri ?: return
        enhanceButton.isEnabled = false
        statusText.text = getString(R.string.status_exporting, 0)

        exporter.start(
            uri,
            selectedTarget(),
        ) { state ->
            when (state) {
                is VideoExporter.State.Running ->
                    statusText.text = getString(R.string.status_exporting, state.percent)

                is VideoExporter.State.Completed -> {
                    statusText.text = getString(R.string.status_done, state.outputPath)
                    enhanceButton.isEnabled = true
                }

                is VideoExporter.State.Failed -> {
                    statusText.text = getString(R.string.status_failed, state.message)
                    enhanceButton.isEnabled = true
                }

                VideoExporter.State.Idle -> {
                    statusText.text = getString(R.string.status_cancelled)
                    enhanceButton.isEnabled = true
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        exporter.release()
        player?.release()
        player = null
        super.onDestroy()
    }
}

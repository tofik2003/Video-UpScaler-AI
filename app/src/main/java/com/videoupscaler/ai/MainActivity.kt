package com.videoupscaler.ai

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Phase 1 skeleton.
 *
 * Deliberately minimal: it proves the toolchain, that the Media3 and LiteRT coordinates resolve,
 * and that a PlayerView can be hosted. No effect chain, no export, no model yet — see
 * docs/PLAN.md and docs/BUILD_AND_RUN.md for what comes next.
 *
 * Views are built in code rather than XML to keep the skeleton to a single source file.
 */
@OptIn(UnstableApi::class) // PlayerView is @UnstableApi
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 18f
                setPadding(32, 32, 32, 16)
            }
        )

        playerView = PlayerView(this)
        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
    }
}

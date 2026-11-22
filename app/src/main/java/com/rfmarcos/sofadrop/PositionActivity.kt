package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Placing the info block anywhere: the settings preview's title drawn once, the block moved over its backdrop with
 * the arrows (no redrawing, so it follows the remote at once), OK keeps it as the custom position (or adds it to the
 * rotation, [EXTRA_ROTATION]), Back leaves everything as it was.
 */
class PositionActivity : AppCompatActivity() {

    private lateinit var background: ImageView
    private lateinit var haloView: ImageView
    private lateinit var blockView: ImageView
    private lateinit var outline: View
    private lateinit var hint: TextView
    private var parts: WallpaperFactory.PlacingParts? = null
    private val profile get() = Profile.of(intent.getStringExtra(EXTRA_PROFILE))
    private val forRotation get() = intent.getBooleanExtra(EXTRA_ROTATION, false)
    private var x = 0
    private var y = 0
    /** The background on screen: with the side shading of either side, or none (in the middle). */
    private var shown: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.position_activity)
        background = findViewById(R.id.position_background)
        haloView = findViewById(R.id.position_halo)
        blockView = findViewById(R.id.position_block)
        outline = findViewById(R.id.position_outline)
        hint = findViewById(R.id.position_hint)
        // A new one for the rotation starts in the middle; the custom position where the block is now
        Settings.pointOf(if (forRotation) Settings.customPosition(50, 50) else profile.position).let { (px, py) -> x = px; y = py }
        // Moved in the image's pixels, scaled to the view once it has a size
        blockView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        val context = applicationContext
        val profile = profile
        Thread {
            val result = runCatching {
                val factory = WallpaperFactory(context, PreviewSettingsFragment.PREVIEW_WIDTH, profile)
                (PreviewSettingsFragment.shownInfo(profile) ?: PreviewSettingsFragment.previewInfo(context, profile, factory))
                    ?.let { factory.renderParts(it) }
            }
            runOnUiThread { show(result) }
        }.start()
    }

    private fun show(result: Result<WallpaperFactory.PlacingParts?>) {
        if (isFinishing || isDestroyed) {
            result.getOrNull()?.recycle()
            return
        }
        findViewById<ProgressBar>(R.id.position_progress).visibility = View.GONE
        val loaded = result.getOrNull()
        if (loaded == null) {
            val error = result.exceptionOrNull()
            hint.text = if (error != null) getString(R.string.preview_error, error.message ?: error.javaClass.simpleName)
            else getString(R.string.preview_no_titles)
            return
        }
        parts = loaded
        blockView.setImageBitmap(loaded.block.image)
        haloView.setImageBitmap(loaded.block.halo)
        outline.visibility = View.VISIBLE
        place()
    }

    /** The block at ([x], [y]) with the shading the wallpaper will have, its outline and where it is. */
    private fun place() {
        val parts = parts ?: return
        val position = Settings.customPosition(x, y)
        val centred = Settings.centred(position)
        val backdrop = when {
            centred -> parts.middle
            Settings.onRight(position) -> parts.right
            else -> parts.left
        }
        if (backdrop !== shown) background.setImageBitmap(backdrop)
        shown = backdrop
        haloView.visibility = if (centred) View.VISIBLE else View.INVISIBLE
        val block = parts.block
        val (dx, dy) = block.offset(x, y)
        val scale = blockView.width.toFloat() / block.image.width
        blockView.translationX = dx * scale
        blockView.translationY = dy * scale
        resize(haloView, block.halo.width * scale, block.halo.height * scale)
        haloView.translationX = (block.haloLeft + dx) * scale
        haloView.translationY = (block.haloTop + dy) * scale
        val pad = resources.displayMetrics.density * OUTLINE_PAD_DP
        // Kept on screen: against an edge the block has no room left for the gap
        val left = ((block.box.left + dx) * scale - pad).coerceAtLeast(0f)
        val top = ((block.box.top + dy) * scale - pad).coerceAtLeast(0f)
        val right = ((block.box.right + dx) * scale + pad).coerceAtMost(blockView.width.toFloat())
        val bottom = ((block.box.bottom + dy) * scale + pad).coerceAtMost(blockView.height.toFloat())
        resize(outline, right - left, bottom - top)
        outline.translationX = left
        outline.translationY = top
        hint.text = getString(R.string.position_custom_keys, x, y)
        // Out of the block's way: at the top while it is in the lower half
        val gravity = (if (y > 50) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        val params = hint.layoutParams as FrameLayout.LayoutParams
        if (params.gravity != gravity) hint.layoutParams = params.apply { this.gravity = gravity }
    }

    /** Set once: a new size lays the screen out again. */
    private fun resize(view: View, width: Float, height: Float) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        val w = width.roundToInt()
        val h = height.roundToInt()
        if (params.width != w || params.height != h) view.layoutParams = params.apply { this.width = w; this.height = h }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (parts == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x = (x - STEP).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> x = (x + STEP).coerceAtMost(100)
            KeyEvent.KEYCODE_DPAD_UP -> y = (y - STEP).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> y = (y + STEP).coerceAtMost(100)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                save()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        place()
        return true
    }

    private fun save() {
        val position = Settings.customPosition(x, y)
        if (forRotation) {
            val rotation = profile.rotation
            if (position !in rotation) profile.rotation = rotation + position
        } else {
            profile.position = position
        }
        Toast.makeText(this, if (forRotation) R.string.position_custom_added else R.string.position_custom_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        parts?.recycle()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        /** Add the position to [Profile.rotation] instead of making it the one position. */
        const val EXTRA_ROTATION = "rotation"
        /** % of the room per press: holding an arrow crosses the screen in about two seconds. */
        private const val STEP = 2
        private const val OUTLINE_PAD_DP = 12
    }
}

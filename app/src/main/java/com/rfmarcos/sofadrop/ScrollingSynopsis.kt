package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a wallpaper's scrolling synopsis is (see [ScrollingSynopsis]), in the pixels of its JPEG. */
@Serializable
data class ScrollBox(
    val patch: String,
    val strip: String,
    val imageWidth: Int,
    val left: Int,
    val top: Int,
    val lineHeight: Float,
    val window: Int,
    val lines: Int,
)

/**
 * A synopsis longer than its [window] lines ([Settings.OVERVIEW_SCROLL]): [patch] is the wallpaper under
 * the synopsis box as it would be without it, at ([left], [top]); [strip] is the whole synopsis with its
 * shadow, as wide as the patch, starting at the same height. Drawn with an offset, the wallpaper's JPEG
 * (whose synopsis is cut at [window] lines) gets the synopsis scrolled that far: it holds, moves a line
 * every [LINE_MILLIS] and holds again at the end. Sofascreen draws it live ([ScrollingWallpaper]),
 * Projectivy gets a video of it ([ScrollVideo]).
 */
class ScrollingSynopsis(
    val patch: Bitmap,
    val strip: Bitmap,
    val left: Int,
    val top: Int,
    val lineHeight: Float,
    val window: Int,
    val lines: Int,
) {
    /** How far the strip moves up. */
    val overflow: Float get() = (lines - window) * lineHeight
    val scrollMillis: Long get() = (lines - window) * LINE_MILLIS
    val durationMillis: Long get() = START_HOLD_MILLIS + scrollMillis + END_HOLD_MILLIS
    /** How far into the box the text fades at an edge it goes past. */
    val fade = min(lineHeight * 0.7f, patch.height / 2f)

    private val clip = Paint(Paint.FILTER_BITMAP_FLAG)
    private val topFade = fadePaint(top.toFloat(), top + fade, fromOpaque = true)
    private val bottomFade = fadePaint(top + patch.height - fade, top + patch.height.toFloat(), fromOpaque = false)

    private fun fadePaint(from: Float, to: Float, fromOpaque: Boolean) = Paint().apply {
        val (a, b) = if (fromOpaque) 0xFF000000.toInt() to 0 else 0 to 0xFF000000.toInt()
        shader = LinearGradient(0f, from, 0f, to, a, b, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /** How far the strip has moved [millis] after it started. */
    fun offsetAt(millis: Long): Float =
        if (millis <= START_HOLD_MILLIS) 0f else min(overflow, (millis - START_HOLD_MILLIS) * lineHeight / LINE_MILLIS)

    /** How strong the top fade is at [offset]: none at the start, full once the text has moved [fade] up. */
    fun topFadeAt(offset: Float) = min(1f, offset / fade)

    /** How strong the bottom fade is at [offset]: full while there's text below, none at the end. */
    fun bottomFadeAt(offset: Float) = min(1f, (overflow - offset) / fade)

    /** Over a canvas holding the wallpaper (in its pixels): the synopsis box, scrolled [offset] pixels. */
    fun draw(canvas: Canvas, offset: Float) {
        val right = (left + patch.width).toFloat()
        val bottom = (top + patch.height).toFloat()
        canvas.drawBitmap(patch, left.toFloat(), top.toFloat(), null)
        canvas.save()
        canvas.clipRect(left.toFloat(), top.toFloat(), right, bottom)
        val layer = canvas.saveLayer(left.toFloat(), top.toFloat(), right, bottom, null)
        canvas.drawBitmap(strip, left.toFloat(), top - offset, clip)
        topFade.alpha = (255 * topFadeAt(offset)).roundToInt()
        bottomFade.alpha = (255 * bottomFadeAt(offset)).roundToInt()
        if (topFade.alpha > 0) canvas.drawRect(left.toFloat(), top.toFloat(), right, top + fade, topFade)
        if (bottomFade.alpha > 0) canvas.drawRect(left.toFloat(), bottom - fade, right, bottom, bottomFade)
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    fun recycle() {
        patch.recycle()
        strip.recycle()
    }

    /** The patch and the strip next to the wallpaper [name] ("movie-603") in [dir], for Sofascreen and the video. */
    fun save(dir: File, name: String, imageWidth: Int): ScrollBox {
        val box = ScrollBox("$name-patch.jpg", "$name-strip.png", imageWidth, left, top, lineHeight, window, lines)
        FileOutputStream(File(dir, box.patch)).use { patch.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        FileOutputStream(File(dir, box.strip)).use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return box
    }

    companion object {
        /** Lines shown at once. */
        const val WINDOW = 4
        const val START_HOLD_MILLIS = 7_000L
        const val LINE_MILLIS = 3_000L
        const val END_HOLD_MILLIS = 4_000L

        /** The synopsis of [box] from [dir], decoded at full size; null when its files are gone or damaged. */
        fun load(dir: File, box: ScrollBox): ScrollingSynopsis? {
            val patch = BitmapFactory.decodeFile(File(dir, box.patch).path) ?: return null
            val strip = BitmapFactory.decodeFile(File(dir, box.strip).path) ?: return null.also { patch.recycle() }
            return ScrollingSynopsis(patch, strip, box.left, box.top, box.lineHeight, box.window, box.lines)
        }
    }
}

/**
 * Sofascreen's wallpaper with its synopsis scrolling live: [image] (maybe subsampled) and [synopsis] in
 * the pixels of the full [imageWidth] JPEG. It holds at the top until [start]. Sofascreen's slide lasts the
 * scroll; the previews [loop] it, as Projectivy loops the video.
 */
class ScrollingWallpaper(
    private val image: Bitmap, val synopsis: ScrollingSynopsis, private val imageWidth: Int, private val loop: Boolean = false,
) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var startedAt = -1L
    private val wake = Runnable { invalidateSelf() }

    fun start() {
        startedAt = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(image, null, bounds, paint)
        var elapsed = if (startedAt < 0) 0L else SystemClock.uptimeMillis() - startedAt
        val duration = ScrollingSynopsis.START_HOLD_MILLIS + synopsis.scrollMillis + ScrollingSynopsis.END_HOLD_MILLIS
        if (loop && elapsed >= duration) {
            startedAt += elapsed / duration * duration
            elapsed %= duration
        }
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val scale = bounds.width() / imageWidth.toFloat()
        canvas.scale(scale, scale)
        synopsis.draw(canvas, synopsis.offsetAt(elapsed))
        canvas.restore()
        if (startedAt < 0) return
        // Still during the first hold, a frame each frame while it moves
        when {
            elapsed < ScrollingSynopsis.START_HOLD_MILLIS -> scheduleSelf(wake, startedAt + ScrollingSynopsis.START_HOLD_MILLIS)
            elapsed < ScrollingSynopsis.START_HOLD_MILLIS + synopsis.scrollMillis -> invalidateSelf()
            loop -> scheduleSelf(wake, startedAt + duration)
        }
    }

    override fun getIntrinsicWidth() = image.width
    override fun getIntrinsicHeight() = image.height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

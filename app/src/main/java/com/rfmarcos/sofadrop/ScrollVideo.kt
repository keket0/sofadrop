package com.rfmarcos.sofadrop

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MP4s of wallpapers whose synopsis scrolls (see [ScrollingSynopsis]), for Projectivy. The frames are
 * drawn with OpenGL on the encoder's input surface, which is how a frame gets its own time: a hold is a
 * few frames instead of hundreds, so the video is only as big as its movement.
 */
object ScrollVideo {
    private const val FPS = 30
    /** A frame this often while the text holds: a long first gap is lost by Android's MP4 writer. */
    private const val HOLD_STEP_MILLIS = 250L
    /** Enough for sharp text: 3 Mbps looked the same at three times the size. */
    private const val BIT_RATE = 1_000_000
    /** What TV encoders take (the Chromecast with Google TV's, up to 1920x1088). */
    private const val VIDEO_WIDTH = 1920
    private const val DRAIN_TIMEOUT_MILLIS = 30_000L
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    /** Each frame's scroll offset and time (ms): every [HOLD_STEP_MILLIS] while it holds, [FPS] while it moves, one at the very end. */
    fun frames(synopsis: ScrollingSynopsis): List<Pair<Float, Long>> = frames(synopsis.scrollMillis, synopsis::offsetAt)

    fun frames(scrollMillis: Long, offsetAt: (Long) -> Float): List<Pair<Float, Long>> {
        val start = ScrollingSynopsis.START_HOLD_MILLIS
        val end = start + scrollMillis
        val duration = end + ScrollingSynopsis.END_HOLD_MILLIS
        val times = (0L until start step HOLD_STEP_MILLIS) +
            generateSequence(0L) { it + 1 }.map { start + it * 1000 / FPS }.takeWhile { it < end } +
            (end until duration step HOLD_STEP_MILLIS) + duration
        return times.map { offsetAt(it) to it }
    }

    private fun avcEncoders() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
        // The device's own (hardware) encoders before Android's software ones
        .sortedBy { it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") }

    /**
     * An H.264 encoder and the size it takes nearest [width] by [height]: the Chromecast with Google TV's
     * hardware encoder only takes multiples of 16 (1920x1072 for 1080p, a squeeze nobody sees), which
     * `findEncoderForFormat` doesn't round to.
     */
    private fun encoderFor(width: Int, height: Int): Triple<String, Int, Int> {
        for (info in avcEncoders()) {
            val video = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: continue
            val w = width / video.widthAlignment * video.widthAlignment
            val h = height / video.heightAlignment * video.heightAlignment
            if (video.isSizeSupported(w, h)) return Triple(info.name, w, h)
        }
        error("No H.264 encoder for ${width}x$height")
    }

    private fun vbr(name: String) = avcEncoders().first { it.name == name }.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        .encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

    /** [synopsis] scrolling over [wallpaper] (its JPEG, optionally subsampled from [sourceWidth]) into [file]; returns the encoder's name. */
    fun encode(synopsis: ScrollingSynopsis, wallpaper: Bitmap, file: File, sourceWidth: Int = wallpaper.width): String {
        val scale = min(1f, VIDEO_WIDTH / wallpaper.width.toFloat())
        val (name, width, height) = encoderFor((wallpaper.width * scale).roundToInt(), (wallpaper.height * scale).roundToInt())
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            if (vbr(name)) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val codec = MediaCodec.createByCodecName(name)
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            var drainError: Throwable? = null
            val abort = AtomicBoolean(false)
            val drain = Thread {
                try {
                    drain(codec, muxer) { abort.get() }
                } catch (e: Throwable) {
                    drainError = e
                }
            }.apply { start() }
            var eosSignalled = false
            try {
                Frames(input, width, height, wallpaper, synopsis, sourceWidth).use { gl ->
                    for ((offset, millis) in frames(synopsis)) gl.draw(offset, millis * 1_000_000)
                }
                codec.signalEndOfInputStream()
                eosSignalled = true
            } finally {
                if (!eosSignalled) abort.set(true)
                drain.join(DRAIN_TIMEOUT_MILLIS)
                input.release()
            }
            drainError?.let { throw it }
            if (drain.isAlive) error("The encoder didn't finish")
            codec.stop()
            muxer.stop()
        } finally {
            runCatching { codec.release() }
            runCatching { muxer?.release() }
        }
        return name
    }

    private fun drain(codec: MediaCodec, muxer: MediaMuxer, aborted: () -> Boolean) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        while (!aborted()) {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    /**
     * OpenGL on [surface]: the wallpaper, the patch and the strip as textures, drawn as [ScrollingSynopsis.draw]
     * does (the fades in the shader). Coordinates are in [sourceWidth] pixels, scaled to the video.
     */
    private class Frames(
        surface: Surface, private val width: Int, private val height: Int, wallpaper: Bitmap,
        private val synopsis: ScrollingSynopsis, sourceWidth: Int,
    ) : AutoCloseable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
        private var program: Int = 0
        private val textures = IntArray(3)
        private val imageWidth = sourceWidth.toFloat()
        private val imageHeight = if (sourceWidth == wallpaper.width) wallpaper.height.toFloat()
            else wallpaper.height.toFloat() * sourceWidth / wallpaper.width
        private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            try {
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
                val attributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL config" }
                val config = configs[0]!!
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
                eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

                program = program(VERTEX, FRAGMENT)
                GLES20.glGenTextures(3, textures, 0)
                // The wallpaper at the video's size; the patch and the strip as they are (they're small)
                val scaled = if (wallpaper.width > width) Bitmap.createScaledBitmap(wallpaper, width, height, true) else wallpaper
                try {
                    upload(textures[0], scaled)
                } finally {
                    if (scaled !== wallpaper) scaled.recycle()
                }
                upload(textures[1], synopsis.patch)
                upload(textures[2], synopsis.strip)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glEnable(GLES20.GL_BLEND)
                // Android's bitmaps are premultiplied
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUseProgram(program)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uViewHeight"), height.toFloat())
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        private fun upload(texture: Int, bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            checkGl("texImage2D ${bitmap.width}x${bitmap.height}")
        }

        /** One frame, the strip [offset] pixels up, stamped [nanos] into the video. */
        fun draw(offset: Float, nanos: Long) {
            val sx = width / imageWidth
            val sy = height / imageHeight
            val left = synopsis.left.toFloat()
            val top = synopsis.top.toFloat()
            val right = left + synopsis.patch.width
            val bottom = top + synopsis.patch.height
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            fades(0f, 0f, top, bottom, sy)
            quad(textures[0], 0f, 0f, imageWidth, imageHeight)
            quad(textures[1], left, top, right, bottom)
            // The strip, inside the box only
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            val scissorBottom = floor(height - bottom * sy).toInt()
            GLES20.glScissor(floor(left * sx).toInt(), scissorBottom, ceil(right * sx).toInt() - floor(left * sx).toInt(), ceil(height - top * sy).toInt() - scissorBottom)
            fades(synopsis.topFadeAt(offset), synopsis.bottomFadeAt(offset), top, bottom, sy)
            quad(textures[2], left, top - offset, left + synopsis.strip.width, top - offset + synopsis.strip.height)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            checkGl("draw")
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers" }
        }

        /** The fades' strength at the box's edges ([top] and [bottom], wallpaper pixels): none for the still parts. */
        private fun fades(topStrength: Float, bottomStrength: Float, top: Float, bottom: Float, sy: Float) {
            val fade = synopsis.fade
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uFade"), top * sy, (top + fade) * sy, (bottom - fade) * sy, bottom * sy)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uStrength"), topStrength, bottomStrength)
        }

        /** [texture] over the rectangle from ([x0], [y0]) to ([x1], [y1]) of the wallpaper. */
        private fun quad(texture: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            fun cx(x: Float) = x / imageWidth * 2 - 1
            fun cy(y: Float) = 1 - y / imageHeight * 2
            quad.clear()
            // Triangle strip: top left, bottom left, top right, bottom right, each with its texture corner
            quad.put(floatArrayOf(cx(x0), cy(y0), 0f, 0f, cx(x0), cy(y1), 0f, 1f, cx(x1), cy(y0), 1f, 0f, cx(x1), cy(y1), 1f, 1f))
            val position = GLES20.glGetAttribLocation(program, "aPos")
            val corner = GLES20.glGetAttribLocation(program, "aTex")
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(position)
            quad.position(2)
            GLES20.glVertexAttribPointer(corner, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(corner)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun close() {
            if (textures.any { it != 0 }) GLES20.glDeleteTextures(3, textures, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            }
            // No eglTerminate: the display is the app's, and its UI may be drawing with it
            EGL14.eglReleaseThread()
        }

        private fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "Shader: ${GLES20.glGetShaderInfoLog(shader)}" }
            }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Program: ${GLES20.glGetProgramInfoLog(program)}" }
            return program
        }

        private fun checkGl(what: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "$what: GL error $error" }
        }
    }

    private const val VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vTex = aTex;
        }
    """

    /** As the canvas' DST_OUT gradients: the text fades towards the box's edges, by each fade's strength. */
    private const val FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform float uViewHeight;
        uniform vec4 uFade;
        uniform vec2 uStrength;
        void main() {
            vec4 color = texture2D(uTex, vTex);
            float y = uViewHeight - gl_FragCoord.y;
            float top = 1.0 - uStrength.x * (1.0 - clamp((y - uFade.x) / (uFade.y - uFade.x), 0.0, 1.0));
            float bottom = 1.0 - uStrength.y * clamp((y - uFade.z) / (uFade.w - uFade.z), 0.0, 1.0);
            gl_FragColor = color * top * bottom;
        }
    """
}

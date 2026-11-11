package com.lumina.tv.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTex
import io.github.peerless2012.ass.AssTrack
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/** Transparent libass output composited above PlayerView. */
class AssSubtitleLayer(
    context: Context,
    private val player: Player,
    initialSubtitleOffsetMs: Long,
    private val onNativeAssActiveChanged: (Boolean) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener, Player.Listener {
    @Volatile
    var subtitleOffsetMs: Long = initialSubtitleOffsetMs

    @Volatile
    var lastApiFailure: Throwable? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("ass-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    @Volatile private var released = false
    @Volatile private var disabled = false
    @Volatile private var playbackPositionMs = 0L
    @Volatile private var frameWidth = 1
    @Volatile private var frameHeight = 1
    @Volatile private var videoWidth = max(player.videoSize.width, 1)
    @Volatile private var videoHeight = max(player.videoSize.height, 1)

    private var ass: Ass? = null
    private var assRender: AssRender? = null
    private var assTrack: AssTrack? = null
    private var assLock: ReentrantLock? = null
    private var renderFrameMethod: Method? = null
    private var bitmapRenderMode: Any? = null
    private var textureRenderMode: Any? = null
    private var renderMode: Any? = null
    private var currentRequest: TrackRequest? = null
    private var currentTrackData: ByteArray? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var colorHandle = -1
    private var bitmapTextures = IntArray(0)
    private var renderLoopRunning = false
    private var preferNativeTextures = false
    private var lastFailureAtMs = 0L

    private val positionTicker = object : Runnable {
        override fun run() {
            if (released) return
            playbackPositionMs = runCatching { player.currentPosition }.getOrDefault(playbackPositionMs)
            mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private val renderTick = object : Runnable {
        override fun run() {
            if (!renderLoopRunning || released || disabled) return
            val startedAt = SystemClock.elapsedRealtime()
            try {
                drawFrame(playbackPositionMs - subtitleOffsetMs)
            } catch (error: Throwable) {
                disableAfterNativeFailure(unwrap(error))
                return
            }
            val delay = (FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            renderHandler.postDelayed(this, delay)
        }
    }

    init {
        isOpaque = false
        alpha = 0f
        surfaceTextureListener = this
        player.addListener(this)
        mainHandler.post(positionTicker)
        onTracksChanged(player.currentTracks)
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (released || disabled) return
        val selected = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter { group.isTrackSelected(it) }
                    .map { index -> Triple(group, index, group.getTrackFormat(index)) }
            }
            .firstOrNull { (_, _, format) -> format.sampleMimeType == MimeTypes.TEXT_SSA }

        val request = selected?.let { (group, index, format) ->
            TrackRequest(
                key = "${group.mediaTrackGroup.id}:$index:${format.id.orEmpty()}",
                uri = findSubtitleConfiguration(format)?.uri,
                initializationData = format.initializationData.map { it.copyOf() },
            )
        }
        renderHandler.post { selectTrack(request) }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        videoWidth = max(videoSize.width, 1)
        videoHeight = max(videoSize.height, 1)
        renderHandler.post { updateRendererSizes() }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        frameWidth = max(width, 1)
        frameHeight = max(height, 1)
        renderHandler.post {
            if (released || disabled) return@post
            try {
                createEgl(surface)
                initializeNative()
                currentTrackData?.let(::installTrack)
                startRenderLoop()
            } catch (error: Throwable) {
                disableAfterNativeFailure(unwrap(error))
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        frameWidth = max(width, 1)
        frameHeight = max(height, 1)
        renderHandler.post {
            if (!released && !disabled) {
                GLES20.glViewport(0, 0, frameWidth, frameHeight)
                updateRendererSizes()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (released) return true
        renderHandler.post {
            stopRenderLoop()
            releaseNative()
            releaseGl()
            surface.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        if (released) return
        released = true
        player.removeListener(this)
        mainHandler.removeCallbacks(positionTicker)
        surfaceTextureListener = null
        setLayerActive(false)
        renderHandler.removeCallbacksAndMessages(null)
        renderHandler.postAtFrontOfQueue {
            stopRenderLoop()
            releaseNative()
            releaseGl()
            currentTrackData = null
            currentRequest = null
            renderThread.quitSafely()
        }
    }

    private fun findSubtitleConfiguration(format: Format): MediaItem.SubtitleConfiguration? {
        val configurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty()
        return configurations.firstOrNull {
            it.mimeType == MimeTypes.TEXT_SSA &&
                ((format.label != null && it.label == format.label) ||
                    (format.language != null && it.language == format.language))
        } ?: configurations.firstOrNull { it.mimeType == MimeTypes.TEXT_SSA }
    }

    private fun selectTrack(request: TrackRequest?) {
        if (released || disabled) return
        if (request == null) {
            currentRequest = null
            currentTrackData = null
            clearTrack()
            return
        }
        if (request.key == currentRequest?.key && currentTrackData != null) return
        currentRequest = request
        val data = runCatching { readTrackData(request) }
            .onFailure(::reportFailure)
            .getOrNull()
        if (request.key != currentRequest?.key) return
        if (data == null || data.isEmpty()) {
            currentTrackData = null
            clearTrack()
            return
        }
        currentTrackData = data
        if (assRender != null) installTrack(data)
    }

    private fun readTrackData(request: TrackRequest): ByteArray? {
        request.uri?.let { uri ->
            runCatching { return readUri(uri) }.onFailure(::reportFailure)
        }
        if (request.initializationData.isEmpty()) return null
        return ByteArrayOutputStream().use { output ->
            request.initializationData.forEachIndexed { index, bytes ->
                output.write(bytes)
                if (index != request.initializationData.lastIndex && bytes.lastOrNull() != '\n'.code.toByte()) {
                    output.write('\n'.code)
                }
            }
            output.toByteArray()
        }
    }

    private fun readUri(uri: Uri): ByteArray {
        val input = when (uri.scheme?.lowercase()) {
            "http", "https" -> URL(uri.toString()).openConnection().apply {
                connectTimeout = 12_000
                readTimeout = 30_000
            }.getInputStream()
            else -> context.contentResolver.openInputStream(uri)
                ?: error("Unable to open ASS subtitle: $uri")
        }
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_SUBTITLE_BYTES) { "ASS subtitle exceeds $MAX_SUBTITLE_BYTES bytes" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun createEgl(surfaceTexture: SurfaceTexture) {
        releaseGl()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to acquire EGL display" }
        check(EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) { "Unable to initialize EGL" }

        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0)) {
            "Unable to choose EGL config"
        }
        val config = configs[0] ?: error("No RGBA EGL config")
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surfaceTexture,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL surface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Unable to bind EGL context"
        }

        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        preferNativeTextures = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            ?.contains("GL_EXT_unpack_subimage") == true
        createProgram()
    }

    private fun initializeNative() {
        if (ass != null || disabled || released) return
        val instance = Ass()
        val lock = instance.lock
        try {
            lock.withLock {
                val renderer = instance.createRender()
                renderer.setStorageSize(videoWidth, videoHeight)
                renderer.setFrameSize(frameWidth, frameHeight)
                renderer.setCacheLimit(10_000, 128)
                val method = renderer.javaClass.methods.firstOrNull {
                    it.name == "renderFrame" && it.parameterTypes.size == 2
                } ?: error("libass renderFrame API unavailable")
                val modes = method.parameterTypes[1].enumConstants
                    ?: error("libass render mode API unavailable")
                val bitmapMode = modes.firstOrNull { it.toString().contains("bitmap", true) }
                    ?: modes.firstOrNull()
                    ?: error("libass render mode unavailable")
                val textureMode = modes.firstOrNull {
                    val name = it.toString()
                    name.contains("texture", true) || name.contains("tex", true) || name.contains("gl", true)
                }
                ass = instance
                assLock = lock
                assRender = renderer
                renderFrameMethod = method
                bitmapRenderMode = bitmapMode
                textureRenderMode = textureMode
                renderMode = if (preferNativeTextures) textureMode ?: bitmapMode else bitmapMode
            }
        } catch (error: Throwable) {
            runCatching { instance.release() }
            throw error
        }
    }

    private fun installTrack(data: ByteArray) {
        val instance = ass ?: return
        val renderer = assRender ?: return
        val lock = assLock ?: return
        try {
            lock.withLock {
                val newTrack = instance.createTrack()
                try {
                    newTrack.readBuffer(data, 0, data.size)
                    if (newTrack.getEvents().isNullOrEmpty()) {
                        newTrack.release()
                        clearTrackLocked(renderer)
                        return
                    }
                    renderer.setTrack(newTrack)
                    assTrack?.release()
                    assTrack = newTrack
                    renderer.setStorageSize(videoWidth, videoHeight)
                    renderer.setFrameSize(frameWidth, frameHeight)
                } catch (error: Throwable) {
                    runCatching { newTrack.release() }
                    throw error
                }
            }
            setLayerActive(true)
        } catch (error: Throwable) {
            disableAfterNativeFailure(unwrap(error))
        }
    }

    private fun updateRendererSizes() {
        val renderer = assRender ?: return
        val lock = assLock ?: return
        try {
            lock.withLock {
                renderer.setStorageSize(videoWidth, videoHeight)
                renderer.setFrameSize(frameWidth, frameHeight)
            }
        } catch (error: Throwable) {
            disableAfterNativeFailure(unwrap(error))
        }
    }

    private fun drawFrame(positionMs: Long) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val renderer = assRender ?: return clearSurface()
        val lock = assLock ?: return clearSurface()
        val method = renderFrameMethod ?: return clearSurface()
        val mode = renderMode ?: return clearSurface()
        val frame = lock.withLock { method.invoke(renderer, positionMs, mode) as? AssFrame }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        frame?.images.orEmpty().forEachIndexed { index, image -> drawImage(image, index) }
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
    }

    private fun drawImage(image: AssTex, index: Int) {
        val nativeTexture = preferNativeTextures && renderMode === textureRenderMode && image.tex > 0
        val texture = if (nativeTexture) {
            image.tex
        } else {
            val bitmap = image.bitmap ?: return
            ensureBitmapTexture(index)
            bitmapTextures[index].also { textureId ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }
        }
        GLES20.glUseProgram(program)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val left = image.x * 2f / frameWidth - 1f
        val right = (image.x + image.w) * 2f / frameWidth - 1f
        val top = 1f - image.y * 2f / frameHeight
        val bottom = 1f - (image.y + image.h) * 2f / frameHeight
        val vertices = floatBuffer(floatArrayOf(left, bottom, right, bottom, left, top, right, top))
        val texCoords = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        val color = image.color
        GLES20.glUniform4f(
            colorHandle,
            ((color ushr 24) and 0xff) / 255f,
            ((color ushr 16) and 0xff) / 255f,
            ((color ushr 8) and 0xff) / 255f,
            (255 - (color and 0xff)) / 255f,
        )
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun floatBuffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(values).apply { position(0) }

    private fun ensureBitmapTexture(index: Int) {
        if (index < bitmapTextures.size) return
        val expanded = IntArray(index + 1)
        bitmapTextures.copyInto(expanded)
        val added = IntArray(expanded.size - bitmapTextures.size)
        GLES20.glGenTextures(added.size, added, 0)
        added.copyInto(expanded, bitmapTextures.size)
        bitmapTextures = expanded
    }

    private fun createProgram() {
        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;" +
                "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }",
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; uniform sampler2D uTexture; uniform vec4 uColor;" +
                "varying vec2 vTexCoord; void main(){ float mask=texture2D(uTexture,vTexCoord).a;" +
                "gl_FragColor=vec4(uColor.rgb,uColor.a*mask); }",
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "ASS shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "ASS shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun clearSurface() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun clearTrack() {
        val renderer = assRender
        val lock = assLock
        if (renderer != null && lock != null) {
            try {
                lock.withLock { clearTrackLocked(renderer) }
            } catch (error: Throwable) {
                disableAfterNativeFailure(unwrap(error))
                return
            }
        }
        setLayerActive(false)
    }

    private fun clearTrackLocked(renderer: AssRender) {
        renderer.setTrack(null)
        assTrack?.release()
        assTrack = null
        setLayerActive(false)
    }

    private fun startRenderLoop() {
        if (renderLoopRunning || assRender == null || disabled || released) return
        renderLoopRunning = true
        renderHandler.post(renderTick)
    }

    private fun stopRenderLoop() {
        renderLoopRunning = false
        renderHandler.removeCallbacks(renderTick)
    }

    private fun releaseNative() {
        val lock = assLock
        if (lock != null) {
            lock.withLock {
                runCatching<Unit> { assRender?.setTrack(null) }.onFailure(::reportFailure)
                runCatching { assRender?.release() }.onFailure(::reportFailure)
                assRender = null
                runCatching { assTrack?.release() }.onFailure(::reportFailure)
                assTrack = null
                runCatching { ass?.release() }.onFailure(::reportFailure)
                ass = null
                assLock = null
                renderFrameMethod = null
                renderMode = null
                bitmapRenderMode = null
                textureRenderMode = null
            }
        } else {
            assRender = null
            assTrack = null
            ass = null
        }
        setLayerActive(false)
    }

    private fun releaseGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (bitmapTextures.isNotEmpty()) GLES20.glDeleteTextures(bitmapTextures.size, bitmapTextures, 0)
        bitmapTextures = IntArray(0)
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun disableAfterNativeFailure(error: Throwable) {
        if (disabled) return
        disabled = true
        reportFailure(error)
        stopRenderLoop()
        releaseNative()
        releaseGl()
        setLayerActive(false)
    }

    private fun reportFailure(error: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureAtMs < FAILURE_LOG_INTERVAL_MS) return
        lastFailureAtMs = now
        lastApiFailure = error
        Log.e(TAG, "Native ASS subtitle layer failed; using Media3 text subtitles", error)
    }

    private fun setLayerActive(active: Boolean) {
        post {
            if (released && active) return@post
            alpha = if (active) 1f else 0f
            onNativeAssActiveChanged(active)
        }
    }

    private fun unwrap(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

    private data class TrackRequest(
        val key: String,
        val uri: Uri?,
        val initializationData: List<ByteArray>,
    )

    private companion object {
        const val TAG = "AssSubtitleLayer"
        const val FRAME_INTERVAL_MS = 16L
        const val FAILURE_LOG_INTERVAL_MS = 5_000L
        const val MAX_SUBTITLE_BYTES = 32 * 1024 * 1024
    }
}

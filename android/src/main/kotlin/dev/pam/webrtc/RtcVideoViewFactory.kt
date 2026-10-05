package dev.pam.webrtc

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.views.NativeViewFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** PAM view `webrtc.video`: renders a session's local or remote video track. */
class RtcVideoViewFactory(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : NativeViewFactory {
    override fun create(context: Context, emit: (ByteArray) -> Unit): View = RtcVideoContainer(context)

    override fun update(view: View, properties: Map<String, WireValue>) {
        val container = view as? RtcVideoContainer ?: return
        container.renderer.configure(
            fit = properties.integer("fit", FIT_COVER.toLong()).toInt(),
            mirror = properties.flag("mirror"),
        )
        container.attach(
            properties.text("sessionId", ""),
            properties.integer("track", WebRtcRuntime.TRACK_REMOTE.toLong()).toInt(),
        )
    }

    override fun release(view: View) {
        (view as? RtcVideoContainer)?.dispose()
    }

    internal companion object {
        const val FIT_COVER = 1
        const val FIT_CONTAIN = 2
    }
}

/** Background-capable host; TextureView itself cannot draw a background drawable. */
class RtcVideoContainer(context: Context) : FrameLayout(context) {
    val renderer = RtcTextureRenderer(context)
    private var binding: AutoCloseable? = null
    private var sessionId = ""
    private var track = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun attach(sessionId: String, track: Int) {
        if (sessionId == this.sessionId && track == this.track) return
        binding?.close()
        renderer.bind(null)
        this.sessionId = sessionId
        this.track = track
        binding = if (sessionId.isEmpty()) null else WebRtcRuntime.observe(sessionId, track) { renderer.bind(it) }
    }

    fun dispose() {
        binding?.close()
        binding = null
        renderer.release()
    }
}

/** EGL renderer drawing into a TextureView so video composes with declarative overlays. */
class RtcTextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val egl = EglRenderer("PamRtcVideo")
    private var bound: VideoTrack? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var fit = RtcVideoViewFactory.FIT_COVER
    private var released = false

    @Volatile
    var framesRendered = 0L
        private set

    init {
        egl.init(WebRtcRuntime.egl.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        isOpaque = false
    }

    fun boundTrack(): VideoTrack? = bound

    fun configure(fit: Int, mirror: Boolean) {
        this.fit = fit
        egl.setMirror(mirror)
        applyLayout()
    }

    fun bind(track: VideoTrack?) {
        if (bound === track || released) return
        bound?.let { runCatching { it.removeSink(this) } }
        bound = track
        if (track == null) {
            egl.clearImage()
        } else {
            track.addSink(this)
        }
    }

    fun release() {
        if (released) return
        bind(null)
        released = true
        egl.release()
    }

    override fun onFrame(frame: VideoFrame) {
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        if (width != frameWidth || height != frameHeight) {
            frameWidth = width
            frameHeight = height
            post(::applyLayout)
        }
        framesRendered++
        egl.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        egl.createEglSurface(surface)
        applyLayout()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = applyLayout()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val latch = CountDownLatch(1)
        egl.releaseEglSurface(latch::countDown)
        latch.await(1, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun applyLayout() {
        if (released || width == 0 || height == 0 || frameWidth == 0 || frameHeight == 0) return
        val viewAspect = width.toFloat() / height
        val frameAspect = frameWidth.toFloat() / frameHeight
        if (fit == RtcVideoViewFactory.FIT_CONTAIN) {
            egl.setLayoutAspectRatio(frameAspect)
            val matrix = Matrix()
            if (frameAspect > viewAspect) {
                matrix.setScale(1f, viewAspect / frameAspect, width / 2f, height / 2f)
            } else {
                matrix.setScale(frameAspect / viewAspect, 1f, width / 2f, height / 2f)
            }
            setTransform(matrix)
        } else {
            egl.setLayoutAspectRatio(viewAspect)
            setTransform(null)
        }
    }
}

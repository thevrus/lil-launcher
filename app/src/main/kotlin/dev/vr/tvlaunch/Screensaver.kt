package dev.vr.tvlaunch

import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/** Full-screen Bing photos with a slow drift, the time, and the place caption; any key wakes it. */
class Screensaver : DreamService() {
    private lateinit var photos: Photos
    private lateinit var photo: ImageView
    private lateinit var caption: TextView
    private var from = 1f; private var to = 1f; private var startedAt = 0L
    /**
     * The whole drift is a few dozen pixels over an hour, well under a pixel per frame, so it steps once a second on a
     * hardware layer instead of animating every frame.
     */
    private val drift = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - startedAt).toFloat() / Photos.ROTATE_MS).coerceAtMost(1f)
            val s = from + (to - from) * t
            photo.scaleX = s; photo.scaleY = s
            if (t < 1f) photo.postDelayed(this, DRIFT_TICK_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream)
        photo = findViewById(R.id.photo)
        caption = findViewById(R.id.caption)
        photo.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        photos = Photos(photo, fadeMs = FADE_MS, saturation = 0.85f, targetLuma = 95.0, onShow = ::shown)
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); photos.start() }
    override fun onDreamingStopped() { super.onDreamingStopped(); photos.stop(); photo.removeCallbacks(drift) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); photos.close() }

    /** Each photo drifts the opposite way from the last, so the crossfade never jumps; a full drift spans one photo. */
    private fun shown(text: String?) {
        photo.removeCallbacks(drift)
        from = photo.scaleX; to = if (to == MAX_ZOOM) 1f else MAX_ZOOM
        startedAt = SystemClock.uptimeMillis()
        drift.run()
        caption.animate().alpha(0f).setStartDelay(0).setDuration(400).withEndAction {
            caption.text = text
            if (text != null) caption.animate().alpha(1f).setStartDelay(FADE_MS.toLong()).setDuration(1200).start()
        }.start()
    }

    private companion object {
        const val DRIFT_TICK_MS = 1000L
        const val FADE_MS = 2500
        const val MAX_ZOOM = 1.07f
    }
}

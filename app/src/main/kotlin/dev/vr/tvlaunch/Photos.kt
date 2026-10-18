package dev.vr.tvlaunch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bing's editor-picked daily photos: fetched once a day into the cache. The photo is picked from the clock, one per
 * [ROTATE_MS] slot, so Home and the screensaver always show the same one and change together.
 * [onShow] gets the place caption of each photo as it fades in.
 */
class Photos(
    private val view: ImageView,
    private val fadeMs: Int = FADE_MS,
    private val saturation: Float = SATURATION,
    private val targetLuma: Double = TARGET_LUMA,
    private val onShow: (caption: String?) -> Unit = {},
) {
    private val dir = File(view.context.cacheDir, "bing").apply { mkdirs() }
    private val stamp = File(dir, ".fetched")
    private val io = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var lastAttempt = 0L
    private var current: Drawable = ColorDrawable(0)
    private var shown: File? = null
    private val rotate = object : Runnable { override fun run() { showNext(); view.postDelayed(this, untilNextSlot()) } }
    private fun untilNextSlot() = ROTATE_MS - System.currentTimeMillis() % ROTATE_MS

    init { look(1f) }

    /** Desaturate, and scale brightness by [dim] so bright and dark photos sit at the same level. */
    private fun look(dim: Float) {
        val m = ColorMatrix().apply { setSaturation(saturation) }
        m.postConcat(ColorMatrix().apply { setScale(dim, dim, dim, 1f) })
        view.colorFilter = ColorMatrixColorFilter(m)
    }

    fun start() {
        view.removeCallbacks(rotate)
        view.post(rotate)
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_MS || fetching.getAndSet(true)) return
        lastAttempt = now
        submit {
            // Home and the screensaver each own a Photos; they share the files, so only one may download at a time.
            val fetched = synchronized(LOCK) {
                ((now - stamp.lastModified() > REFRESH_MS || scan().any { !captionFile(it).exists() }) && fetch()).also { if (it) stamp.writeText("") }
            }
            fetching.set(false)
            if (fetched) view.post { showNext() }
        }
    }
    fun stop() = view.removeCallbacks(rotate)
    fun close() = io.shutdown()
    /** A post can outlive close(); the work is simply dropped then. */
    private fun submit(task: () -> Unit) { try { io.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { } }

    private fun captionFile(jpg: File) = File(dir, jpg.name.removeSuffix(".jpg") + ".txt")
    private fun scan() = dir.listFiles { f -> f.name.endsWith(".jpg") && f.length() > 0 }?.sortedBy { it.name } ?: emptyList()

    private fun showNext() {
        submit {
            // Rescanned every time: the other instance may have replaced the set. Eight files cost nothing.
            val fs = scan(); if (fs.isEmpty()) return@submit
            val next = fs[(System.currentTimeMillis() / ROTATE_MS).mod(fs.size)]
            if (next == shown) return@submit
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmp = BitmapFactory.decodeFile(next.path, opts) ?: run { next.delete(); view.post { showNext() }; return@submit }
            val dim = dimFor(bmp)
            val caption = captionFile(next).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
            view.post {
                look(dim)
                val d = BitmapDrawable(view.resources, bmp)
                val t = TransitionDrawable(arrayOf(current, d)).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(t); t.startTransition(fadeMs)
                current = d; shown = next
                onShow(caption)
            }
        }
    }

    /** Brightness factor that brings a photo's mean luminance down to TARGET_LUMA. */
    private fun dimFor(bmp: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bmp, 32, 18, true)
        val px = IntArray(32 * 18); small.getPixels(px, 0, 32, 0, 0, 32, 18); small.recycle()
        var sum = 0.0
        for (p in px) sum += 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
        val luma = sum / px.size
        return (targetLuma / luma).coerceIn(0.45, 1.0).toFloat()
    }

    /** Returns true when the cache is complete for today's set. */
    private fun fetch(): Boolean = try {
        val json = get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=$COUNT&mkt=en-US").toString(Charsets.UTF_8)
        val imgs = JSONObject(json).getJSONArray("images")
        val keep = HashSet<String>()
        for (i in 0 until imgs.length()) {
            val o = imgs.getJSONObject(i)
            val name = o.getString("startdate") + ".jpg"
            keep += name
            val f = File(dir, name)
            // "Place, Country (© Photographer/Agency)": keep only the place.
            captionFile(f).writeText(o.optString("copyright").substringBefore(" (©").trim())
            if (f.exists() && f.length() > 0) continue
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(get("https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg"))
            tmp.renameTo(f)
        }
        scan().filter { it.name !in keep }.forEach { it.delete(); captionFile(it).delete() }
        true
    } catch (_: Exception) { false }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (TV) TVLaunch")
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    companion object {
        private val LOCK = Any()
        val ROTATE_MS = TimeUnit.HOURS.toMillis(1)
        private val REFRESH_MS = TimeUnit.HOURS.toMillis(20)
        private val RETRY_MS = TimeUnit.MINUTES.toMillis(10)
        private const val FADE_MS = 900
        private const val COUNT = 8
        private const val SATURATION = 0.7f
        private const val TARGET_LUMA = 70.0
    }
}

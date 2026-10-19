package dev.vr.tvlaunch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.text.format.DateFormat
import android.widget.TextView
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var grid: RecyclerView
    private lateinit var photos: Photos
    private val adapter = AppAdapter(::launch, ::tileMenu)
    private val io = Executors.newSingleThreadExecutor()

    /** Drawables are expensive to load, so entries are cached by package and only rebuilt when the installed set changes. */
    private var entries: Map<String, AppEntry> = emptyMap()

    private companion object {
        const val TEST_MS = 4000L
        const val SPAN = 4
        const val IDLE_MS = 12_000L
        val WEATHER_MS = TimeUnit.HOURS.toMillis(1)
    }

    /** Fallback ranking signal when usage access is not granted: launches made through this launcher. */
    private val launches by lazy { getSharedPreferences("usage", MODE_PRIVATE) }
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private var hidden: Set<String>
        get() = prefs.getStringSet("hidden", emptySet())!!
        set(v) = prefs.edit().putStringSet("hidden", v).apply()
    /** Packages the user arranged by hand; they lead the grid, the rest follow by use. */
    private var pinned: List<String>
        get() = prefs.getString("order", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("order", v.joinToString("\n")).apply()

    private lateinit var popover: View
    private lateinit var netBtn: View
    private lateinit var popNet: TextView
    private lateinit var popVpn: TextView
    private lateinit var popRate: TextView
    private lateinit var vpnDot: View
    private var vpnUp = false
    private var vpnCountry: String? = null
    private var online = true

    /** Green dot on the globe while any VPN tunnel is up; the system does not say which app owns it. */
    private val vpnWatch = object : ConnectivityManager.NetworkCallback() {
        private val up = HashSet<Network>()
        override fun onAvailable(n: Network) { up += n; set() }
        override fun onLost(n: Network) { up -= n; set() }
        /** Call on unregister: onLost never arrives for a tunnel that drops while the callback is off. */
        fun reset() { up.clear(); set() }
        private fun set() = vpnDot.post {
            val now = up.isNotEmpty()
            if (now != vpnUp) { vpnUp = now; vpnCountry = null; if (popover.isShown) showPopover() }
            vpnDot.visibility = if (now) View.VISIBLE else View.GONE
        }
    }
    private val vpnRequest = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()

    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { online = true }
        override fun onLost(n: Network) { online = false }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { online = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
    }

    private var speedMbps = 0; private var speedAt = 0L; private var testing = false

    /** Short burst download from Cloudflare, measured for at most TEST_MS; cached for two minutes. */
    private fun speedTest() {
        if (testing || System.currentTimeMillis() - speedAt < 120_000) { popRate.text = speedText(); return }
        testing = true; popRate.text = getString(R.string.speed_testing)
        io.execute {
            var bytes = 0L; val t0 = System.nanoTime()
            try {
                val c = java.net.URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000
                c.inputStream.use { s -> val buf = ByteArray(64 * 1024)
                    while (System.nanoTime() - t0 < TEST_MS * 1_000_000L) { val n = s.read(buf); if (n <= 0) break; bytes += n } }
                c.disconnect()
            } catch (_: Exception) { }
            val secs = (System.nanoTime() - t0) / 1e9
            speedMbps = if (bytes > 0) (bytes * 8 / 1e6 / secs).toInt() else 0
            speedAt = System.currentTimeMillis(); testing = false
            popRate.post { popRate.text = speedText() }
        }
    }
    /** Netflix/YouTube guidance: 25 for 4K HDR, 15 for 4K, 5 for 1080p, 3 for 720p. */
    private fun speedText(): String {
        if (speedMbps <= 0) return getString(R.string.net_offline)
        val quality = when { speedMbps >= 25 -> "4K HDR"; speedMbps >= 15 -> "4K"; speedMbps >= 5 -> "1080p"; speedMbps >= 3 -> "720p"; else -> "SD" }
        return getString(R.string.rate_quality, speedMbps, quality)
    }

    private fun showPopover() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        popNet.text = getString(when {
            !online || caps == null -> R.string.net_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.net_wifi
            else -> R.string.net_ethernet
        })
        popVpn.text = when { !vpnUp -> getString(R.string.vpn_off); vpnCountry == null -> getString(R.string.vpn_on); else -> getString(R.string.vpn_country, vpnCountry) }
        popover.visibility = View.VISIBLE
        popover.animate().alpha(1f).setDuration(160).start()
        speedTest()
        if (vpnUp && vpnCountry == null) io.execute {
            // ipwho.is goes by IP registration, which is how VPN providers label virtual locations; ipinfo is the fallback.
            val country = (fetch("https://ipwho.is/?fields=country_code")?.let { json(it)?.optString("country_code") }
                ?.takeIf { it.length == 2 } ?: fetch("https://ipinfo.io/country")?.trim())
                ?.takeIf { it.length == 2 }?.let { java.util.Locale("", it).displayCountry }
            popVpn.post { if (vpnUp && country != null) { vpnCountry = country; popVpn.text = getString(R.string.vpn_country, country) } }
        }
    }

    /** A captive portal answers 200 with HTML; that must not take the launcher down. */
    private fun json(s: String) = try { org.json.JSONObject(s) } catch (_: Exception) { null }

    private fun fetch(url: String): String? = try {
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("User-Agent", "LilLauncher (Android TV)")  // OpenStreetMap refuses requests without one
            try { inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { disconnect() }
        }
    } catch (_: Exception) { null }

    private fun hidePopover() {
        popover.animate().alpha(0f).setDuration(120).withEndAction { popover.visibility = View.GONE }.start()
    }

    // Move mode: the picked tile wiggles and follows the D-pad until Select or Back.
    private var moving = -1
    private var swallowUp = -1
    private var wiggle: ValueAnimator? = null
    private var movingTile: View? = null

    private fun startMove(e: AppEntry) {
        moving = adapter.items.indexOf(e)
        val tile = tileAt(moving) ?: run { moving = -1; return }
        movingTile = tile
        tile.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
        wiggle = ObjectAnimator.ofFloat(tile, View.ROTATION, -1.2f, 1.2f).apply {
            duration = 130; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    private fun moveKey(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) { if (e.keyCode == swallowUp) swallowUp = -1; return true }
        val items = adapter.items
        val to = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moving - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> moving + 1
            KeyEvent.KEYCODE_DPAD_UP -> moving - SPAN
            KeyEvent.KEYCODE_DPAD_DOWN -> moving + SPAN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { swallowUp = e.keyCode; endMove(); return true }
            else -> return true
        }.coerceIn(0, items.size - 1)
        if (to != moving) { adapter.move(moving, to); moving = to; grid.scrollToPosition(to) }
        return true
    }

    private fun endMove() {
        wiggle?.cancel(); wiggle = null
        movingTile?.apply { rotation = 0f; val sc = if (isFocused) AppAdapter.FOCUS_SCALE else 1f; animate().scaleX(sc).scaleY(sc).setDuration(200).start() }
        movingTile = null
        val apps = adapter.items.map { it.pkg }
        pinned = apps.take(maxOf(pinned.count { it in apps }, moving + 1))
        moving = -1
        refresh()
    }

    // Weather and the sun: current conditions and the next sunrise or sunset under the greeting, refreshed at most hourly.
    private lateinit var weather: TextView
    private lateinit var city: TextView

    /**
     * Android 14 regional preferences put the temperature unit on the locale as the "mu" extension. Older systems
     * have no such setting, and the region alone is a poor guess, so they stay on Celsius.
     */
    private fun fahrenheit() = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT).getUnicodeLocaleType("mu") == "fahrenhe"

    private fun updateWeather() {
        val f = fahrenheit()
        val key = if (f) "sky_f" else "sky_c"  // one cache per unit, so changing the setting refetches
        showWeather(key)
        val now = System.currentTimeMillis()
        val fresh = now - prefs.getLong("${key}_at", 0) < WEATHER_MS
        if (fresh && prefs.contains("city")) return
        io.execute {
            val (lat, lon) = locate() ?: return@execute
            city.post { showCity() }
            if (fresh) return@execute  // only the city was missing
            val res = fetch("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code" +
                "&daily=sunrise,sunset&forecast_days=2&timezone=auto" + if (f) "&temperature_unit=fahrenheit" else "")?.let { json(it) } ?: return@execute
            val cur = res.optJSONObject("current") ?: return@execute
            val temp = cur.optDouble("temperature_2m").takeIf { !it.isNaN() } ?: return@execute
            val text = getString(R.string.weather, temp.roundToInt(), getString(sky(cur.optInt("weather_code"))))
            // The place's time zone, then sunrise and sunset for today and tomorrow in its local time ("2026-10-19T07:22").
            val d = res.optJSONObject("daily")
            val sun = listOf(res.optString("timezone")) + (0..1).flatMap { i -> listOf(d?.optJSONArray("sunrise")?.optString(i), d?.optJSONArray("sunset")?.optString(i)) }
            val sunText = sun.takeIf { l -> l.all { !it.isNullOrEmpty() } }?.joinToString(" ")
            prefs.edit().putString(key, text).putString("${key}_sun", sunText).putLong("${key}_at", now).apply()
            weather.post { showWeather(key) }
        }
    }

    private fun showWeather(key: String) {
        weather.text = listOfNotNull(prefs.getString(key, null), sunText(prefs.getString("${key}_sun", null))).joinToString("  ·  ")
    }

    /**
     * The next sunrise or sunset from two cached days, read in the place's own time zone; the clock shown is the
     * device's. Null when both days have passed.
     */
    private fun sunText(sun: String?): String? {
        val parts = sun?.split(' ')?.takeIf { it.size == 5 } ?: return null
        val zone = try { ZoneId.of(parts[0]) } catch (_: Exception) { return null }
        val times = parts.drop(1).map { try { LocalDateTime.parse(it).atZone(zone) } catch (_: Exception) { return null } }
        val now = ZonedDateTime.now(zone)
        val i = times.indexOfFirst { it.isAfter(now) }.takeIf { it >= 0 } ?: return null
        val res = when { i % 2 == 1 -> R.string.sunset; times[i].toLocalDate() == now.toLocalDate() -> R.string.sunrise; else -> R.string.sunrise_tomorrow }
        if (i == 3 && times[i].toLocalDate() != now.toLocalDate()) return null  // a sunset tomorrow means the cache is a day old
        return getString(res, DateFormat.getTimeFormat(this).format(Date.from(times[i].toInstant())))
    }

    /**
     * The name set in the TV's settings ("Living room"), which can change while Home is away. The factory default is
     * the bare model word ("SHIELD"), so that one gets the maker in front: "Nvidia Shield".
     */
    private fun deviceName(): String {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.trim().orEmpty()
        val model = android.os.Build.MODEL.substringBefore(' ')
        if (name.isNotEmpty() && !name.equals(model, ignoreCase = true)) return name
        fun word(w: String) = w.lowercase().replaceFirstChar { it.titlecase() }
        return "${word(android.os.Build.MANUFACTURER)} ${word(model)}"
    }

    /** Blank until the city is known: the time zone's city is often hundreds of miles off. */
    private fun showCity() {
        val name = prefs.getString("city", null)
        city.text = name
        city.visibility = if (name.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // Greeting: follows the clock, checked on every minute tick.
    private lateinit var greeting: TextView
    private lateinit var subtitle: TextView
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updateGreeting() }
    }

    private fun updateGreeting() {
        val (g, sub) = when (LocalTime.now().hour) {
            in 5..11 -> R.string.greet_morning to R.string.sub_morning
            in 12..16 -> R.string.greet_afternoon to R.string.sub_afternoon
            in 17..21 -> R.string.greet_evening to R.string.sub_evening
            else -> R.string.greet_night to R.string.sub_night
        }
        greeting.setText(g); subtitle.setText(sub)
        showWeather(if (fahrenheit()) "sky_f" else "sky_c")  // the sun line moves on with the clock
    }

    /**
     * The exit IP of a VPN would put the weather in another country, so the IP location is only trusted, and
     * remembered, while no tunnel is up; a TV does not move, so one lookup is enough. Until then the time zone's city stands in.
     */
    private fun locate(): Pair<String, String>? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val vpn = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (!vpn && !prefs.contains("loc")) fetch("https://ipwho.is/?fields=success,latitude,longitude")?.let { json(it) }
            ?.takeIf { it.optBoolean("success") && it.has("latitude") && it.has("longitude") }
            ?.let { prefs.edit().putString("loc", "${it.optDouble("latitude")},${it.optDouble("longitude")}").apply() }
        prefs.getString("loc", null)?.split(',')?.let { (lat, lon) ->
            // The city name comes from the remembered coordinates, never from the IP, so a VPN cannot move it.
            if (!prefs.contains("city")) fetch("https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&lat=$lat&lon=$lon" +
                    "&accept-language=" + java.util.Locale.getDefault().toLanguageTag())
                ?.let { json(it)?.optString("name") }?.takeIf { it.isNotBlank() }
                ?.let { prefs.edit().putString("city", it).apply() }
            return lat to lon
        }
        val city = java.util.TimeZone.getDefault().id.substringAfterLast('/').replace('_', ' ')
        return fetch("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + java.net.URLEncoder.encode(city, "UTF-8"))
            ?.let { json(it)?.optJSONArray("results")?.optJSONObject(0) }
            ?.takeIf { it.has("latitude") && it.has("longitude") }
            ?.let { it.optDouble("latitude").toString() to it.optDouble("longitude").toString() }
    }

    /** WMO weather codes, grouped the way Apple's Weather names them. */
    private fun sky(code: Int) = when (code) {
        0 -> R.string.sky_clear; 1 -> R.string.sky_mostly_clear; 2 -> R.string.sky_partly_cloudy; 3 -> R.string.sky_cloudy
        45, 48 -> R.string.sky_fog; in 51..57 -> R.string.sky_drizzle; in 71..77, 85, 86 -> R.string.sky_snow
        in 95..99 -> R.string.sky_storms; else -> R.string.sky_rain
    }

    // Photo caption: fades in after a quiet spell, out on the next key, and only where no tile sits.
    private lateinit var caption: TextView
    private val idle = Runnable {
        val last = grid.getChildAt(grid.childCount - 1)
        if (!caption.text.isNullOrEmpty() && moving < 0 && (last == null || grid.top + last.bottom < caption.top))
            caption.animate().alpha(1f).setDuration(900).start()
    }
    private fun poke() {
        caption.removeCallbacks(idle)
        if (caption.alpha > 0f) caption.animate().alpha(0f).setDuration(200).start()
        caption.postDelayed(idle, IDLE_MS)
    }

    // Inputs: HDMI and other passthrough ports, for TVs whose stock launcher was the only way to switch.
    private lateinit var inputBtn: View
    /** Input id to label; HDMI-CEC devices carry their own names. */
    private var inputs: List<Pair<String, String>> = emptyList()
    private val liveTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV) }

    private fun updateInputs() {
        io.execute {
            val list = if (!liveTv) emptyList() else getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty()
                .filter { it.isPassthroughInput && !it.isHidden(this) }
                .map { it.id to (it.loadCustomLabel(this) ?: it.loadLabel(this)).toString() }
            inputBtn.post { inputs = list; inputBtn.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE }
        }
    }

    /** The TV's own input app tunes to a passthrough channel URI. */
    private fun inputMenu() {
        if (inputs.isEmpty()) return
        val list = inputs
        AlertDialog.Builder(this).setTitle(R.string.inputs)
            .setItems(list.map { it.second }.toTypedArray()) { _, i ->
                open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(list[i].first)))
            }.show()
    }

    /** Starts [i], or the main Settings screen when nothing on this device handles it. */
    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun options() {
        // Without the secure-settings grant, open the settings where the sleep service can be switched on by hand.
        val opts = mutableListOf<Pair<Int, () -> Unit>>(R.string.sleep to { if (!SleepService.sleep(this)) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        if (hidden.any { it in entries }) opts += R.string.hidden_apps to ::unhideMenu
        if (pinned.isNotEmpty()) opts += R.string.reset_order to { pinned = emptyList(); refresh() }
        AlertDialog.Builder(this).setItems(opts.map { getString(it.first) }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, SPAN)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)
        caption = findViewById(R.id.caption)
        weather = findViewById(R.id.weather)
        city = findViewById(R.id.city)
        greeting = findViewById(R.id.greeting)
        subtitle = findViewById(R.id.subtitle)
        netBtn = findViewById(R.id.netBtn)
        photos = Photos(findViewById(R.id.photo), onShow = { caption.text = it })
        findViewById<View>(R.id.settingsBtn).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            setOnLongClickListener { options(); true }
        }

        inputBtn = findViewById<View>(R.id.inputBtn).apply { setOnClickListener { inputMenu() } }
        popover = findViewById(R.id.popover); popNet = findViewById(R.id.popNet); popVpn = findViewById(R.id.popVpn); popRate = findViewById(R.id.popRate)
        vpnDot = findViewById(R.id.vpnDot)
        // Centered under the globe and inside the right overscan line, again whenever the speed text changes its width.
        popover.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val at = IntArray(2).also { netBtn.getLocationInWindow(it) }
            val max = (v.parent as View).width - resources.getDimension(R.dimen.overscan_h) - v.width
            v.x = (at[0] + netBtn.width / 2f - v.width / 2f).coerceAtMost(max)
        }
        netBtn.apply {
            setOnFocusChangeListener { _, f -> if (f) showPopover() else hidePopover() }
            setOnClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        // The header rides on top of the grid's first rows and scrolls away with them, fading as it goes.
        val header = findViewById<View>(R.id.header)
        header.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, _ ->
            val top = bottom - v.paddingBottom + (resources.displayMetrics.density * 32).roundToInt()
            if (grid.paddingTop != top) grid.post { grid.setPadding(grid.paddingLeft, top, grid.paddingRight, grid.paddingBottom) }
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val off = rv.computeVerticalScrollOffset().toFloat()
                header.translationY = -off
                header.alpha = (1f - off / (header.height * 0.3f)).coerceIn(0f, 1f)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        photos.start()
        registerReceiver(packageChanges, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        getSystemService(ConnectivityManager::class.java).apply {
            registerNetworkCallback(vpnRequest, vpnWatch)
            registerDefaultNetworkCallback(netWatch)
        }
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK))
        updateGreeting()
        showCity()
        findViewById<TextView>(R.id.deviceName).text = deviceName()
        updateInputs()
        updateWeather()
        poke()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (moving >= 0) endMove()
        caption.removeCallbacks(idle)
        photos.stop()
        unregisterReceiver(packageChanges)
        unregisterReceiver(tick)
        getSystemService(ConnectivityManager::class.java).apply { unregisterNetworkCallback(vpnWatch); unregisterNetworkCallback(netWatch) }
        vpnWatch.reset()
    }

    private fun refresh() {
        io.execute {
            val apps = rankedApps()
            grid.post { render(apps) }
        }
    }

    private fun render(list: List<AppEntry>) {
        if (moving >= 0) return
        val old = adapter.items
        if (list == old) return
        // Keep focus on the same app when the ranking reshuffles tiles.
        val focused = focusedPos()?.let { old.getOrNull(it)?.pkg }
        adapter.submit(list)
        val at = focused?.let { f -> list.indexOfFirst { it.pkg == f } }?.takeIf { it >= 0 }
        // The first fill also claims focus: the system would otherwise give it to the globe, the first focusable view.
        if (at != null || old.isEmpty() || currentFocus == null) focusTile(at ?: 0)
    }

    /** Tiles from a new submit exist only after the next layout pass, so the focus request waits for it. */
    private fun focusTile(pos: Int) {
        grid.scrollToPosition(pos)
        grid.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                tileAt(pos)?.requestFocus()
            }
        })
    }

    private fun tileAt(pos: Int) = (grid.findViewHolderForAdapterPosition(pos) as? AppAdapter.VH)?.tile
    /** Adapter position of the focused tile, or null when focus is elsewhere. */
    private fun focusedPos() = currentFocus?.takeIf { it.id == R.id.tile }?.let { grid.getChildAdapterPosition(it.parent as View) }?.takeIf { it >= 0 }

    override fun onDestroy() { super.onDestroy(); photos.close(); io.shutdown() }

    private fun rankedApps(): List<AppEntry> {
        val pm = packageManager
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val resolved = pm.queryIntentActivities(leanback, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
        if (resolved.map { it.activityInfo.packageName }.toSet() != entries.keys) {
            entries = resolved.associate { ri ->
                val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                ri.activityInfo.packageName to AppEntry(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), banner, ri.loadIcon(pm))
            }
        }
        val seconds = foregroundSeconds()
        val counts = launches.all
        val hide = hidden
        val pin = pinned.withIndex().associate { it.value to it.index }
        return entries.values
            .filter { it.pkg !in hide }
            .map { e -> Triple(seconds[e.pkg] ?: 0L, (counts[e.pkg] as? Int) ?: 0, e) }
            .sortedWith(compareBy<Triple<Long, Int, AppEntry>> { pin[it.third.pkg] ?: Int.MAX_VALUE }
                .thenByDescending { it.first }.thenByDescending { it.second }.thenBy { it.third.label.lowercase() })
            .map { it.third }
    }

    /** Foreground seconds per package over the last 30 days; empty when usage access is not granted. */
    private fun foregroundSeconds(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(30), now)
            .mapValues { it.value.totalTimeInForeground / 1000 }
    }

    private fun launch(e: AppEntry) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(e.pkg)
            ?: packageManager.getLaunchIntentForPackage(e.pkg) ?: return
        launches.edit().putInt(e.pkg, launches.getInt(e.pkg, 0) + 1).apply()
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private fun tileMenu(e: AppEntry) {
        AlertDialog.Builder(this).setTitle(e.label)
            .setItems(arrayOf(getString(R.string.move), getString(R.string.hide_app), getString(R.string.app_info))) { _, which ->
                when (which) {
                    0 -> startMove(e)
                    1 -> { hidden = hidden + e.pkg; refresh() }
                    else -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
                }
            }.show()
    }

    /** Long-press on the gear: pick hidden apps to show again. */
    private fun unhideMenu() {
        val hide = hidden.filter { it in entries }.sortedBy { entries[it]!!.label.lowercase() }
        if (hide.isEmpty()) return
        val picked = BooleanArray(hide.size)
        AlertDialog.Builder(this).setTitle(R.string.hidden_apps)
            .setMultiChoiceItems(hide.map { entries[it]!!.label }.toTypedArray(), picked) { _, i, on -> picked[i] = on }
            .setPositiveButton(R.string.show) { _, _ ->
                hidden = hidden - hide.filterIndexed { i, _ -> picked[i] }.toSet(); refresh()
            }.show()
    }

    /**
     * Up from the first row goes to the globe; the default focus search would pick the gear. Reaching the first row
     * scrolls all the way back so the header is whole again.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) poke()
        if (moving >= 0 || (e.keyCode == swallowUp && e.action == KeyEvent.ACTION_UP)) return moveKey(e)
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val pos = focusedPos() ?: return super.dispatchKeyEvent(e)
            if (pos < SPAN) { netBtn.requestFocus(); return true }
            val handled = super.dispatchKeyEvent(e)
            if ((focusedPos() ?: SPAN) < SPAN) grid.smoothScrollBy(0, -grid.computeVerticalScrollOffset())
            return handled
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Home never exits; the platform still routes Back here on this target")
    override fun onBackPressed() { }
}

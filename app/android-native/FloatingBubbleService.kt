package com.omnidrop.app.plugin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

class FloatingBubbleService : Service() {

    companion object {
        var instance: FloatingBubbleService? = null
        const val CHANNEL_ID = "omnidrop_edge_service_channel"
        private const val PREFS_NAME = "omnidrop_native_prefs"
        private const val PREF_TAB_IS_LEFT = "tab_is_left"
        private const val PREF_TAB_Y = "tab_y_pos"
        private const val PREF_AUTO_DOWNLOAD = "auto_download"
        private const val PREF_BACKEND_URL = "backend_url"
        private const val PREF_API_KEY = "api_key"
        private const val PREF_DEFAULT_QUALITY = "default_quality"
        private const val PREF_FLOATING_AUTO_CLEAR = "floating_auto_clear"
        private const val PREF_FLOATING_VIBRATE = "floating_vibrate"
        private const val PREF_FLOATING_HIDE_INSIDE_APP = "floating_hide_inside_app"
        private const val PREF_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val PREF_SKIPPED_UPDATE_VERSION = "skipped_update_version"
        private const val PREF_LAST_NOTIFIED_UPDATE_VERSION = "last_notified_update_version"
        private const val GITHUB_REPO = "fatormajohn999-gif/J-TEC-downloader-"
        const val UPDATE_CHANNEL_ID = "omnidrop_update_channel"
        const val NOTIFICATION_ID_UPDATE = 1002
        const val PREF_HISTORY_KEY = "native_download_history"

        @Volatile var isOmniDropInForeground: Boolean = false

        fun setOmniDropForeground(inForeground: Boolean) {
            isOmniDropInForeground = inForeground
            instance?.onAppForegroundChanged(inForeground)
        }

        val SUPPORTED_DOMAINS = arrayOf(
            "tiktok.com",
            "vt.tiktok.com",
            "youtube.com",
            "youtu.be",
            "instagram.com",
            "facebook.com",
            "fb.watch"
        )

        fun extractSupportedUrl(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val regex = "(https?://[^\\s]+)".toRegex()
            val match = regex.find(text) ?: return null
            val raw = match.value.trim()
            try {
                val host = Uri.parse(raw).host?.lowercase() ?: return null
                val isSupported = SUPPORTED_DOMAINS.any { d -> host == d || host.endsWith(".$d") }
                return if (isSupported) VideoDownloaderPlugin.normalizeUrl(raw) else null
            } catch (e: Exception) {
                return null
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var prefs: SharedPreferences? = null

    // Edge Tab Views
    private var tabRootView: FrameLayout? = null
    private var tabInnerFrame: EdgeLatchTabView? = null
    private var tabLayoutParams: WindowManager.LayoutParams? = null

    // Panel Views
    private var panelRootOverlay: FrameLayout? = null
    private var panelMachineCard: MachineCardView? = null
    private var serverLedDot: View? = null
    private var serverStatusText: TextView? = null
    private var netLedDot: View? = null
    private var netStatusText: TextView? = null
    private var trafficDnText: TextView? = null
    private var trafficUpText: TextView? = null
    private var queueStatusText: TextView? = null
    private var pingGraphView: PingGraphView? = null
    private var linkEditText: EditText? = null
    private var smartKeyBtn: TextView? = null
    private var retryBtn: View? = null
    private var manualDownloadBtn: View? = null
    private var progressContainer: LinearLayout? = null
    private var progressStatusText: TextView? = null
    private var progressSpeedText: TextView? = null
    private var segmentedProgressBar: SegmentedProgressBar? = null
    private var finishedActionsLayout: LinearLayout? = null
    private var recentDownloadsContainer: LinearLayout? = null
    private var autoDownloadToggleLed: View? = null
    private var autoDownloadToggleText: TextView? = null

    // State
    private var isLeftEdge: Boolean = true
    private var tabYPosition: Int = 300
    private var isPanelOpen: Boolean = false
    private var isDownloading: Boolean = false
    private var autoDownloadEnabled: Boolean = true
    private var autoClearEnabled: Boolean = true
    private var vibrateEnabled: Boolean = true
    private var hideInsideAppEnabled: Boolean = true
    private var backendUrl: String = ""
    private var apiKey: String = ""
    private var defaultQuality: String = "best"
    private var lastFinishedFileUri: String? = null
    private var lastFailedUrl: String? = null

    // Active Download Tracking for Cancellation
    @Volatile private var isDownloadCancelled: Boolean = false
    private var activeDownloadConnection: HttpURLConnection? = null
    private var activeDownloadOutputStream: OutputStream? = null
    private var activeTargetUri: Uri? = null
    private var activeNormalizedUrl: String? = null

    // Download Queue
    private val downloadQueue = ArrayDeque<String>()

    // Typing timestamp and Auto-Clear Runnable
    private var lastTypingTimestamp: Long = 0L
    private var autoClearRunnable: Runnable? = null

    // Idle Fade Runnable (5 seconds without interaction)
    private val idleFadeRunnable = Runnable {
        if (!isDownloading && !isPanelOpen) {
            tabRootView?.animate()?.alpha(0.45f)?.setDuration(400L)?.start()
        }
    }

    // Dual Ping Readings
    private var serverPingMs: Int = -1
    private var serverState: String = "OFF" // "ONLINE", "SLEEP", "WAKING", "OFF"
    private var netPingMs: Int = -1
    private var isScreenOn: Boolean = true

    // Traffic Rates
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastTrafficSampleTime: Long = 0

    // Deduplication
    private val recentDownloads = ConcurrentHashMap<String, Long>()

    // Auto-check updates state
    private var autoCheckUpdatesEnabled: Boolean = true

    // Threading
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newFixedThreadPool(3)

    // Periodic Update Check Runnable (5s after start, then every 4 hours)
    private val updateCheckRunnable = object : Runnable {
        override fun run() {
            if (autoCheckUpdatesEnabled) {
                checkAppUpdatesInBackground()
            }
            mainHandler.postDelayed(this, 4 * 3600 * 1000L)
        }
    }

    // Periodic Dual-Ping Runnable (every 5 seconds)
    private val dualPingRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                measureDualPing()
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    // Traffic Sampling Runnable (every 1 second when panel is open)
    private val trafficRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && isPanelOpen) {
                sampleTrafficStats()
            }
            if (isPanelOpen) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    // Screen Receiver
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action
            if (Intent.ACTION_SCREEN_OFF == act) {
                isScreenOn = false
            } else if (Intent.ACTION_SCREEN_ON == act) {
                isScreenOn = true
                resetIdleFade()
                measureDualPing()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadSavedSettings()
        createNotificationChannel()
        startForeground(1001, buildServiceNotification())

        val filter = IntentFilter().apply {
            this.addAction(Intent.ACTION_SCREEN_ON)
            this.addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        initEdgeTab()
        initPanelOverlay()

        mainHandler.post(dualPingRunnable)
        mainHandler.postDelayed(updateCheckRunnable, 5000L)
        resetIdleFade()
    }

    private fun loadSavedSettings() {
        prefs?.let { p ->
            isLeftEdge = p.getBoolean(PREF_TAB_IS_LEFT, true)
            tabYPosition = p.getInt(PREF_TAB_Y, 350)
            autoDownloadEnabled = p.getBoolean(PREF_AUTO_DOWNLOAD, true)
            backendUrl = p.getString(PREF_BACKEND_URL, "") ?: ""
            apiKey = p.getString(PREF_API_KEY, "") ?: ""
            defaultQuality = p.getString(PREF_DEFAULT_QUALITY, "best") ?: "best"
            autoClearEnabled = p.getBoolean(PREF_FLOATING_AUTO_CLEAR, true)
            vibrateEnabled = p.getBoolean(PREF_FLOATING_VIBRATE, true)
            hideInsideAppEnabled = p.getBoolean(PREF_FLOATING_HIDE_INSIDE_APP, true)
            autoCheckUpdatesEnabled = p.getBoolean(PREF_AUTO_CHECK_UPDATES, true)
        }
    }

    fun updateSettings(
        newUrl: String,
        newApiKey: String,
        newQuality: String,
        newAutoDownload: Boolean,
        newAutoClear: Boolean = true,
        newVibrate: Boolean = true,
        newHideInsideApp: Boolean = true,
        newAutoCheckUpdates: Boolean = true
    ) {
        backendUrl = newUrl.trim()
        apiKey = newApiKey.trim()
        defaultQuality = newQuality
        autoDownloadEnabled = newAutoDownload
        autoClearEnabled = newAutoClear
        vibrateEnabled = newVibrate
        hideInsideAppEnabled = newHideInsideApp
        autoCheckUpdatesEnabled = newAutoCheckUpdates
        prefs?.edit()?.apply {
            this.putString(PREF_BACKEND_URL, backendUrl)
            this.putString(PREF_API_KEY, apiKey)
            this.putString(PREF_DEFAULT_QUALITY, defaultQuality)
            this.putBoolean(PREF_AUTO_DOWNLOAD, autoDownloadEnabled)
            this.putBoolean(PREF_FLOATING_AUTO_CLEAR, autoClearEnabled)
            this.putBoolean(PREF_FLOATING_VIBRATE, vibrateEnabled)
            this.putBoolean(PREF_FLOATING_HIDE_INSIDE_APP, hideInsideAppEnabled)
            this.putBoolean(PREF_AUTO_CHECK_UPDATES, autoCheckUpdatesEnabled)
            this.apply()
        }
        mainHandler.post {
            updateAutoDownloadUI()
            measureDualPing()
            updateForegroundVisibility()
            resetIdleFade()
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        mainHandler.post {
            updateForegroundVisibility()
        }
    }

    fun updateForegroundVisibility() {
        if (hideInsideAppEnabled && isOmniDropInForeground) {
            if (isPanelOpen) {
                closePanel()
            }
            tabRootView?.visibility = View.GONE
        } else {
            tabRootView?.visibility = View.VISIBLE
        }
    }

    fun handleSharedUrl(sharedUrl: String) {
        mainHandler.post {
            resetIdleFade()
            if (!isPanelOpen) {
                openPanel()
            }
            linkEditText?.setText(sharedUrl)
            enqueueOrStartDownload(sharedUrl, isManual = false)
        }
    }

    private fun resetIdleFade() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        tabRootView?.animate()?.alpha(1.0f)?.setDuration(150L)?.start()
        if (!isDownloading && !isPanelOpen) {
            mainHandler.postDelayed(idleFadeRunnable, 5000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniDrop Edge Tab Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                this.description = "Keeps the OmniDrop hardware edge download tool active"
                this.setShowBadge(false)
            }
            val updateChannel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "OmniDrop Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                this.description = "Notifications for new OmniDrop app and firmware updates"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(updateChannel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val clickIntent = Intent(this, ClipboardBridgeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniDrop Machine Tab Active")
            .setContentText("Swipe or tap metal latch to open download space")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // -------------------------------------------------------------------------
    // 1. METAL LATCH EDGE TAB
    // -------------------------------------------------------------------------
    private fun initEdgeTab() {
        val density = resources.displayMetrics.density
        val tabWidth = (30 * density).toInt()
        val tabHeight = (74 * density).toInt()

        val root = FrameLayout(this).apply {
            this.visibility = if (hideInsideAppEnabled && isOmniDropInForeground) View.GONE else View.VISIBLE
        }
        tabRootView = root

        val latch = EdgeLatchTabView(this, isLeftEdge).apply {
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            this.layoutParams = lp
        }
        tabInnerFrame = latch
        root.addView(latch)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            tabWidth,
            tabHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = Gravity.TOP or Gravity.START
            this.x = if (isLeftEdge) 0 else (resources.displayMetrics.widthPixels - tabWidth)
            this.y = tabYPosition
        }
        tabLayoutParams = lp

        setupTabTouchListener(root, lp, tabWidth, tabHeight)

        try {
            windowManager?.addView(root, lp)
            updateGestureExclusion(root)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateGestureExclusion(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.post {
                val rect = Rect(0, 0, view.width, view.height)
                view.systemGestureExclusionRects = listOf(rect)
            }
        }
    }

    private fun setupTabTouchListener(view: View, lp: WindowManager.LayoutParams, tabWidth: Int, tabHeight: Int) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var touchStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resetIdleFade()
                        if (tabInnerFrame?.isFinishedOrFailed() == true) {
                            tabInnerFrame?.resetToIdle()
                        }
                        initialX = lp.x
                        initialY = lp.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        touchStartTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (event.rawY - touchStartY).toInt()
                        lp.y = (initialY + dy).coerceIn(40, screenHeight - tabHeight - 80)
                        windowManager?.updateViewLayout(v, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        resetIdleFade()
                        val duration = System.currentTimeMillis() - touchStartTime
                        val totalDx = event.rawX - touchStartX
                        val totalDy = event.rawY - touchStartY

                        val isSwipeInward = if (isLeftEdge) {
                            totalDx > 30 && Math.abs(totalDy) < 80
                        } else {
                            totalDx < -30 && Math.abs(totalDy) < 80
                        }
                        val isTap = duration < 300 && Math.abs(totalDx) < 18 && Math.abs(totalDy) < 18

                        if (isSwipeInward || isTap) {
                            openPanel()
                            return true
                        }

                        // Snap to nearest edge
                        val finalCenterX = event.rawX
                        isLeftEdge = finalCenterX < (screenWidth / 2)
                        lp.x = if (isLeftEdge) 0 else (screenWidth - tabWidth)
                        tabYPosition = lp.y

                        prefs?.edit()?.apply {
                            this.putBoolean(PREF_TAB_IS_LEFT, isLeftEdge)
                            this.putInt(PREF_TAB_Y, tabYPosition)
                            this.apply()
                        }

                        tabInnerFrame?.setIsLeft(isLeftEdge)
                        windowManager?.updateViewLayout(v, lp)
                        updateGestureExclusion(v)
                        return true
                    }
                }
                return false
            }
        })
    }

    // -------------------------------------------------------------------------
    // 2. ROBOTIC MACHINE GLASS PANEL OVERLAY
    // -------------------------------------------------------------------------
    private fun initPanelOverlay() {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.88f).toInt().coerceIn((320 * density).toInt(), (430 * density).toInt())

        val overlay = FrameLayout(this).apply {
            this.visibility = View.GONE
        }
        panelRootOverlay = overlay

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                resetIdleFade()
                val card = panelMachineCard
                if (card != null) {
                    val location = IntArray(2)
                    card.getLocationOnScreen(location)
                    val cardX = location[0]
                    val cardY = location[1]
                    val cardW = card.width
                    val cardH = card.height
                    val touchX = event.rawX.toInt()
                    val touchY = event.rawY.toInt()

                    if (touchX < cardX || touchX > cardX + cardW || touchY < cardY || touchY > cardY + cardH) {
                        closePanel()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        val card = MachineCardView(this).apply {
            this.setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }
        panelMachineCard = card

        val cardLp = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = if (isLeftEdge) (Gravity.START or Gravity.CENTER_VERTICAL) else (Gravity.END or Gravity.CENTER_VERTICAL)
            this.setMargins((10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt())
        }
        overlay.addView(card, cardLp)

        buildMachineContent(card, density)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val overlayLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(overlay, overlayLp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildMachineContent(card: MachineCardView, density: Float) {
        val rootScroll = ScrollView(this).apply {
            this.isVerticalScrollBarEnabled = false
            this.overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
        }
        rootScroll.addView(content)
        card.addView(rootScroll)

        // --- 1. MACHINED HEADER ROW ---
        val header = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(0, 0, 0, (6 * density).toInt())
        }

        val emblem = TextView(this).apply {
            this.text = "⚡"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            this.setPadding((4 * density).toInt(), 0, (6 * density).toInt(), 0)
        }
        val titleTv = TextView(this).apply {
            this.text = "OMNIDROP"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#E0F2FE"))
            this.letterSpacing = 0.12f
            this.setSingleLine(true)
            this.includeFontPadding = false
            val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = tlp
        }
        header.addView(emblem)
        header.addView(titleTv)

        // Close Mechanical Key "✕"
        val closeBtn = createMechanicalButton(
            text = "✕",
            density = density,
            isCompact = true,
            onClick = { closePanel() }
        )
        header.addView(closeBtn)
        content.addView(header)

        // --- 2. FIXED-HEIGHT DUAL STATUS INSTRUMENT PANEL (TWO ROWS) ---
        val statusBoard = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8 * density
                this.setColor(Color.parseColor("#0F141F"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#263248"))
            }
            this.background = shape
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (2 * density).toInt()
                this.bottomMargin = (8 * density).toInt()
            }
            this.layoutParams = lp
        }

        // ROW 1: SRV & NET READOUTS (Fixed-width slots, singleLine, never wraps or pushes)
        val row1 = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (22 * density).toInt())
            this.layoutParams = rlp
        }

        // SERVER Slot
        val sLed = View(this).apply {
            val dlp = LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                this.rightMargin = (4 * density).toInt()
            }
            this.layoutParams = dlp
            this.background = createCircleDrawable(Color.parseColor("#EF4444"))
        }
        serverLedDot = sLed
        val sLabel = TextView(this).apply {
            this.text = "SRV: "
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#94A3B8"))
        }
        val sVal = TextView(this).apply {
            this.text = "OFF"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setSingleLine(true)
            this.maxLines = 1
            this.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val vlp = LinearLayout.LayoutParams((46 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            this.layoutParams = vlp
        }
        serverStatusText = sVal
        row1.addView(sLed)
        row1.addView(sLabel)
        row1.addView(sVal)

        // Spacer between SRV and NET
        val spacer1 = View(this).apply {
            val slp = LinearLayout.LayoutParams((12 * density).toInt(), 1)
            this.layoutParams = slp
        }
        row1.addView(spacer1)

        // NET Slot
        val nLed = View(this).apply {
            val dlp = LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                this.rightMargin = (4 * density).toInt()
            }
            this.layoutParams = dlp
            this.background = createCircleDrawable(Color.parseColor("#10B981"))
        }
        netLedDot = nLed
        val nLabel = TextView(this).apply {
            this.text = "NET: "
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#94A3B8"))
        }
        val nVal = TextView(this).apply {
            this.text = "—"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setSingleLine(true)
            this.maxLines = 1
            this.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val vlp = LinearLayout.LayoutParams((46 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            this.layoutParams = vlp
        }
        netStatusText = nVal
        row1.addView(nLed)
        row1.addView(nLabel)
        row1.addView(nVal)

        statusBoard.addView(row1)

        // ROW 2: SPEED READOUTS & QUEUE BADGE (Fixed-width slots, capped at 999 MB/s)
        val row2 = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (18 * density).toInt()).apply {
                this.topMargin = (3 * density).toInt()
            }
            this.layoutParams = rlp
        }

        val dnLabel = TextView(this).apply {
            this.text = "DN "
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#64748B"))
        }
        val dnVal = TextView(this).apply {
            this.text = "0 B/s"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setSingleLine(true)
            this.maxLines = 1
            val dnlp = LinearLayout.LayoutParams((68 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            this.layoutParams = dnlp
        }
        trafficDnText = dnVal
        row2.addView(dnLabel)
        row2.addView(dnVal)

        val upLabel = TextView(this).apply {
            this.text = "UP "
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#64748B"))
        }
        val upVal = TextView(this).apply {
            this.text = "0 B/s"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setSingleLine(true)
            this.maxLines = 1
            val uplp = LinearLayout.LayoutParams((68 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            this.layoutParams = uplp
        }
        trafficUpText = upVal
        row2.addView(upLabel)
        row2.addView(upVal)

        val queueTv = TextView(this).apply {
            this.text = ""
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            this.setSingleLine(true)
            val qlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = qlp
        }
        queueStatusText = queueTv
        row2.addView(queueTv)

        statusBoard.addView(row2)
        content.addView(statusBoard)

        // --- 3. PING MONITOR GRAPH ---
        val graph = PingGraphView(this).apply {
            val glp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt()).apply {
                this.bottomMargin = (10 * density).toInt()
            }
            this.layoutParams = glp
        }
        pingGraphView = graph
        content.addView(graph)

        // --- 4. INPUT & ONE SMART KEY + RETRY KEY ---
        val inputContainer = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding((10 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 10 * density
                this.setColor(Color.parseColor("#070A10"))
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#1F2A3E"))
            }
            this.background = shape
        }

        val edit = EditText(this).apply {
            val elp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.rightMargin = (6 * density).toInt()
            }
            this.layoutParams = elp
            this.hint = "Paste TikTok, YT, IG, FB link..."
            this.setHintTextColor(Color.parseColor("#64748B"))
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.background = null
            this.setSingleLine(true)
            this.ellipsize = TextUtils.TruncateAt.END
        }
        linkEditText = edit
        inputContainer.addView(edit)

        // RETRY Key (shows beside CLEAR key on failed download)
        val retry = createMechanicalButton(
            text = "RETRY",
            density = density,
            isAccent = true,
            isCompact = true,
            onClick = { handleRetryButtonClick() }
        ).apply {
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.rightMargin = (6 * density).toInt()
            }
            this.layoutParams = rlp
            this.visibility = View.GONE
        }
        retryBtn = retry
        inputContainer.addView(retry)

        // ONE SMART KEY: Dynamic 3 states: PASTE (cyan), CLEAR (amber), CANCEL (red)
        val smartBtn = TextView(this).apply {
            this.text = "PASTE"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.WHITE)
            this.gravity = Gravity.CENTER
            this.setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                this.setColor(Color.parseColor("#083344"))
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#00F0FF"))
            }
            this.background = shape

            this.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.translationY = 2 * density
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.translationY = 0f
                }
                false
            }
            this.setOnClickListener { handleSmartKeyClick() }
        }
        smartKeyBtn = smartBtn
        inputContainer.addView(smartBtn)

        // Optional manual Download button if auto-download is disabled
        val dlBtn = createMechanicalButton(
            text = "DOWNLOAD",
            density = density,
            isGreen = true,
            onClick = {
                val url = linkEditText?.text?.toString()?.trim()
                if (!url.isNullOrBlank()) {
                    enqueueOrStartDownload(url, isManual = true)
                }
            }
        ).apply {
            val dlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.leftMargin = (6 * density).toInt()
            }
            this.layoutParams = dlp
            this.visibility = if (autoDownloadEnabled) View.GONE else View.VISIBLE
        }
        manualDownloadBtn = dlBtn
        inputContainer.addView(dlBtn)

        content.addView(inputContainer)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                lastTypingTimestamp = System.currentTimeMillis()
                autoClearRunnable?.let { mainHandler.removeCallbacks(it) }
            }
            override fun afterTextChanged(s: Editable?) {
                updateSmartKeyUI()
                val text = s?.toString()?.trim() ?: ""
                if (text.isNotBlank() && retryBtn?.visibility == View.VISIBLE && text != lastFailedUrl) {
                    retryBtn?.visibility = View.GONE
                }
                val supportedUrl = extractSupportedUrl(text)
                if (supportedUrl != null && autoDownloadEnabled && !isDownloading) {
                    enqueueOrStartDownload(supportedUrl, isManual = false)
                }
            }
        })

        // --- 5. PROGRESS & STATUS AREA (Segmented LED Bar) ---
        val progLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.visibility = View.GONE
            val plp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (10 * density).toInt()
            }
            this.layoutParams = plp
            this.setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8 * density
                this.setColor(Color.parseColor("#090D14"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#1E293B"))
            }
            this.background = shape
        }
        progressContainer = progLayout

        val progHeader = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
        }
        val statusTv = TextView(this).apply {
            this.text = "Initializing..."
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            this.setTextColor(Color.parseColor("#E0F2FE"))
            val slp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = slp
        }
        progressStatusText = statusTv
        val speedProgTv = TextView(this).apply {
            this.text = ""
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#00F0FF"))
        }
        progressSpeedText = speedProgTv
        progHeader.addView(statusTv)
        progHeader.addView(speedProgTv)
        progLayout.addView(progHeader)

        val segBar = SegmentedProgressBar(this).apply {
            val sblp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (10 * density).toInt()).apply {
                this.topMargin = (6 * density).toInt()
                this.bottomMargin = (6 * density).toInt()
            }
            this.layoutParams = sblp
        }
        segmentedProgressBar = segBar
        progLayout.addView(segBar)

        val finishedRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.END
            this.visibility = View.GONE
        }
        finishedActionsLayout = finishedRow

        val openBtn = createMechanicalButton(
            text = "OPEN VIDEO",
            density = density,
            isGreen = true,
            onClick = { openFinishedVideo() }
        )
        val dismissBtn = createMechanicalButton(
            text = "CLOSE",
            density = density,
            onClick = {
                progressContainer?.visibility = View.GONE
                finishedActionsLayout?.visibility = View.GONE
            }
        )
        finishedRow.addView(openBtn)
        finishedRow.addView(dismissBtn)
        progLayout.addView(finishedRow)

        content.addView(progLayout)

        // --- 6. ACTION TILES ROW ---
        val tilesRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (10 * density).toInt()
            }
            this.layoutParams = rlp
        }

        val tileApp = createMachineTile("OPEN APP", "OmniDrop", density) {
            closePanel()
            launchMainActivity(null)
        }
        val tileGallery = createMachineTile("GALLERY", "Movies/OmniDrop", density) {
            closePanel()
            openGalleryFolder()
        }
        val tileAuto = createAutoDownloadMachineTile(density)

        tilesRow.addView(tileApp)
        tilesRow.addView(tileGallery)
        tilesRow.addView(tileAuto)
        content.addView(tilesRow)

        // --- 7. RECENT DOWNLOADS SECTION (Last 3) ---
        val recentsHeader = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val rhlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (12 * density).toInt()
                this.bottomMargin = (4 * density).toInt()
            }
            this.layoutParams = rhlp
        }
        val recentsTitle = TextView(this).apply {
            this.text = "RECENT DOWNLOADS"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.letterSpacing = 0.08f
            val rlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = rlp
        }
        recentsHeader.addView(recentsTitle)
        content.addView(recentsHeader)

        val recentsContainer = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
        }
        recentDownloadsContainer = recentsContainer
        content.addView(recentsContainer)

        refreshRecentDownloadsUI()
        updateSmartKeyUI()
    }

    private fun updateSmartKeyUI() {
        val btn = smartKeyBtn ?: return
        val density = resources.displayMetrics.density
        val text = linkEditText?.text?.toString()?.trim() ?: ""

        if (isDownloading) {
            btn.text = "CANCEL"
            btn.setTextColor(Color.WHITE)
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                this.setColor(Color.parseColor("#7F1D1D")) // Deep Red
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#EF4444")) // Bright Red
            }
            btn.background = shape
        } else if (text.isNotEmpty()) {
            btn.text = "CLEAR"
            btn.setTextColor(Color.WHITE)
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                this.setColor(Color.parseColor("#78350F")) // Deep Amber
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#F59E0B")) // Bright Amber
            }
            btn.background = shape
        } else {
            btn.text = "PASTE"
            btn.setTextColor(Color.WHITE)
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                this.setColor(Color.parseColor("#083344")) // Deep Cyan
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#00F0FF")) // Bright Cyan
            }
            btn.background = shape
        }
    }

    private fun handleSmartKeyClick() {
        resetIdleFade()
        if (isDownloading) {
            cancelCurrentDownload()
        } else if (!linkEditText?.text.isNullOrBlank()) {
            // CLEAR state
            linkEditText?.setText("")
            progressContainer?.visibility = View.GONE
            finishedActionsLayout?.visibility = View.GONE
            progressStatusText?.text = ""
            progressSpeedText?.text = ""
            segmentedProgressBar?.setProgress(0)
            retryBtn?.visibility = View.GONE
            lastFailedUrl = null
            tabInnerFrame?.resetToIdle()
            panelMachineCard?.setGlowColor(Color.parseColor("#344259"))
            updateSmartKeyUI()
        } else {
            // PASTE state
            handlePasteButtonClick()
        }
    }

    private fun handleRetryButtonClick() {
        resetIdleFade()
        val urlToRetry = lastFailedUrl ?: linkEditText?.text?.toString()?.trim()
        if (!urlToRetry.isNullOrBlank()) {
            retryBtn?.visibility = View.GONE
            enqueueOrStartDownload(urlToRetry, isManual = true, force = true)
        }
    }

    private fun cancelCurrentDownload() {
        isDownloadCancelled = true
        try { activeDownloadOutputStream?.close() } catch (_: Exception) {}
        try { activeDownloadConnection?.disconnect() } catch (_: Exception) {}
        activeTargetUri?.let { uri ->
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        activeNormalizedUrl?.let { url ->
            VideoDownloaderPlugin.inFlightDownloads.remove(url)
        }
        isDownloading = false
        mainHandler.post {
            progressStatusText?.text = "Cancelled"
            progressSpeedText?.text = ""
            segmentedProgressBar?.setProgress(0)
            tabInnerFrame?.resetToIdle()
            panelMachineCard?.setGlowColor(Color.parseColor("#344259"))
            updateSmartKeyUI()
            resetIdleFade()
            processNextInQueue()
        }
    }

    private fun refreshRecentDownloadsUI() {
        val container = recentDownloadsContainer ?: return
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val jsonStr = VideoDownloaderPlugin.getHistoryJson(this)
        var count = 0

        try {
            val jsonArray = JSONArray(jsonStr)
            val maxToShow = Math.min(3, jsonArray.length())
            for (i in 0 until maxToShow) {
                val item = jsonArray.getJSONObject(i)
                val row = createRecentDownloadRow(item, density)
                container.addView(row)
                count++
            }
        } catch (_: Exception) {}

        if (count == 0) {
            val emptyTv = TextView(this).apply {
                this.text = "No recent downloads recorded."
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                this.setTextColor(Color.parseColor("#475569"))
                this.setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            container.addView(emptyTv)
        }
    }

    private fun createRecentDownloadRow(item: JSONObject, density: Float): View {
        val row = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (4 * density).toInt()
            }
            this.layoutParams = rlp

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                this.setColor(Color.parseColor("#0E131E"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#1E293B"))
            }
            this.background = shape
        }

        val platform = item.optString("platform", "video").uppercase()
        val badge = TextView(this).apply {
            this.text = platform.take(4)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setPadding((5 * density).toInt(), (2 * density).toInt(), (5 * density).toInt(), (2 * density).toInt())
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 4 * density
                this.setColor(Color.parseColor("#162338"))
            }
            this.background = shape
            val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.rightMargin = (8 * density).toInt()
            }
            this.layoutParams = blp
        }
        row.addView(badge)

        val title = item.optString("title", "Video")
        val titleTv = TextView(this).apply {
            this.text = title
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.setTextColor(Color.WHITE)
            this.setSingleLine(true)
            this.ellipsize = TextUtils.TruncateAt.END
            val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.rightMargin = (8 * density).toInt()
            }
            this.layoutParams = tlp
        }
        row.addView(titleTv)

        val fileUri = item.optString("fileUri", "")
        val openBtn = createMechanicalButton(
            text = "OPEN",
            density = density,
            isCompact = true,
            onClick = {
                if (fileUri.isNotBlank()) {
                    try {
                        val playerIntent = Intent(this@FloatingBubbleService, VideoPlayerActivity::class.java).apply {
                            this.action = Intent.ACTION_VIEW
                            this.data = Uri.parse(fileUri)
                            this.putExtra("video_title", title)
                            this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(playerIntent)
                    } catch (_: Exception) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            this.setDataAndType(Uri.parse(fileUri), "video/*")
                            this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            openGalleryFolder()
                        }
                    }
                } else {
                    openGalleryFolder()
                }
            }
        )
        row.addView(openBtn)

        return row
    }

    private fun createMachineTile(title: String, subtitle: String, density: Float, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            this.layoutParams = tlp
            this.orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER
            this.setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8 * density
                this.setColor(Color.parseColor("#101622"))
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#253147"))
            }
            this.background = shape

            val t1 = TextView(this@FloatingBubbleService).apply {
                this.text = title
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.setTextColor(Color.WHITE)
            }
            val t2 = TextView(this@FloatingBubbleService).apply {
                this.text = subtitle
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                this.setTextColor(Color.parseColor("#64748B"))
                this.setSingleLine(true)
                this.ellipsize = TextUtils.TruncateAt.END
            }
            this.addView(t1)
            this.addView(t2)

            this.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.translationY = 2 * density
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.translationY = 0f
                }
                false
            }
            this.setOnClickListener { onClick() }
        }
    }

    private fun createAutoDownloadMachineTile(density: Float): View {
        return LinearLayout(this).apply {
            val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            this.layoutParams = tlp
            this.orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER
            this.setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8 * density
                this.setColor(Color.parseColor("#101622"))
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#253147"))
            }
            this.background = shape

            val t1 = TextView(this@FloatingBubbleService).apply {
                this.text = "AUTO DL"
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.setTextColor(Color.WHITE)
            }

            val ledRow = LinearLayout(this@FloatingBubbleService).apply {
                this.orientation = LinearLayout.HORIZONTAL
                this.gravity = Gravity.CENTER
            }
            val led = View(this@FloatingBubbleService).apply {
                val dlp = LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                    this.rightMargin = (4 * density).toInt()
                }
                this.layoutParams = dlp
                this.background = createCircleDrawable(if (autoDownloadEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            }
            autoDownloadToggleLed = led
            val t2 = TextView(this@FloatingBubbleService).apply {
                this.text = if (autoDownloadEnabled) "ON" else "OFF"
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.setTextColor(if (autoDownloadEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            }
            autoDownloadToggleText = t2
            ledRow.addView(led)
            ledRow.addView(t2)

            this.addView(t1)
            this.addView(ledRow)

            this.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.translationY = 2 * density
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.translationY = 0f
                }
                false
            }
            this.setOnClickListener {
                autoDownloadEnabled = !autoDownloadEnabled
                prefs?.edit()?.apply {
                    this.putBoolean(PREF_AUTO_DOWNLOAD, autoDownloadEnabled)
                    this.apply()
                }
                updateAutoDownloadUI()
                Toast.makeText(this@FloatingBubbleService, "Auto-Download ${if (autoDownloadEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAutoDownloadUI() {
        autoDownloadToggleLed?.background = createCircleDrawable(
            if (autoDownloadEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        )
        autoDownloadToggleText?.text = if (autoDownloadEnabled) "ON" else "OFF"
        autoDownloadToggleText?.setTextColor(
            if (autoDownloadEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        )
        manualDownloadBtn?.visibility = if (autoDownloadEnabled) View.GONE else View.VISIBLE
    }

    private fun createMechanicalButton(
        text: String,
        density: Float,
        isAccent: Boolean = false,
        isGreen: Boolean = false,
        isCompact: Boolean = false,
        onClick: () -> Unit
    ): View {
        return TextView(this).apply {
            this.text = text
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCompact) 10f else 11f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.WHITE)
            this.gravity = Gravity.CENTER

            val padH = (if (isCompact) 8 * density else 12 * density).toInt()
            val padV = (if (isCompact) 5 * density else 7 * density).toInt()
            this.setPadding(padH, padV, padH, padV)

            val baseColor = when {
                isGreen -> Color.parseColor("#059669")
                isAccent -> Color.parseColor("#4F46E5")
                else -> Color.parseColor("#1E293B")
            }
            val strokeColor = when {
                isGreen -> Color.parseColor("#10B981")
                isAccent -> Color.parseColor("#818CF8")
                else -> Color.parseColor("#334155")
            }

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                this.setColor(baseColor)
                this.setStroke((1.5f * density).toInt(), strokeColor)
            }
            this.background = shape

            this.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.translationY = 2 * density
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.translationY = 0f
                }
                false
            }
            this.setOnClickListener { onClick() }
        }
    }

    // -------------------------------------------------------------------------
    // 3. SLIDE-OUT / DISMISS CONTROLS
    // -------------------------------------------------------------------------
    private fun openPanel() {
        if (isPanelOpen) return
        isPanelOpen = true
        resetIdleFade()

        if (tabInnerFrame?.isFinishedOrFailed() == true) {
            tabInnerFrame?.resetToIdle()
        }
        panelMachineCard?.setGlowColor(Color.parseColor("#344259"))

        val overlay = panelRootOverlay ?: return
        val card = panelMachineCard ?: return

        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f

        val cardLp = card.layoutParams as FrameLayout.LayoutParams
        cardLp.gravity = if (isLeftEdge) (Gravity.START or Gravity.CENTER_VERTICAL) else (Gravity.END or Gravity.CENTER_VERTICAL)
        card.layoutParams = cardLp

        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val startX = if (isLeftEdge) -screenWidth else screenWidth
        card.translationX = startX

        val anim = ValueAnimator.ofFloat(startX, 0f).apply {
            this.duration = 200L
            this.addUpdateListener { va ->
                val v = va.animatedValue as Float
                card.translationX = v
            }
        }
        anim.start()

        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastTrafficSampleTime = System.currentTimeMillis()
        mainHandler.post(trafficRunnable)

        refreshRecentDownloadsUI()
        updateSmartKeyUI()
        checkClipboardOnOpen()
    }

    private fun closePanel() {
        if (!isPanelOpen) return
        val card = panelMachineCard ?: return
        val overlay = panelRootOverlay ?: return

        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val targetX = if (isLeftEdge) -screenWidth else screenWidth

        val anim = ValueAnimator.ofFloat(0f, targetX).apply {
            this.duration = 200L
            this.addUpdateListener { va ->
                val v = va.animatedValue as Float
                card.translationX = v
            }
            this.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.visibility = View.GONE
                    isPanelOpen = false
                    resetIdleFade()
                }
            })
        }
        anim.start()
    }

    private fun checkClipboardOnOpen() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.coerceToText(this)?.toString()
                val supported = extractSupportedUrl(clipText)
                if (supported != null) {
                    val lastDl = recentDownloads[supported] ?: 0L
                    if (System.currentTimeMillis() - lastDl > 120_000L) {
                        linkEditText?.setText(supported)
                        updateSmartKeyUI()
                        if (autoDownloadEnabled && !isDownloading) {
                            enqueueOrStartDownload(supported, isManual = false)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handlePasteButtonClick() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                if (clipText.isNotBlank()) {
                    linkEditText?.setText(clipText)
                    updateSmartKeyUI()
                    val supported = extractSupportedUrl(clipText)
                    if (supported != null) {
                        if (autoDownloadEnabled) {
                            enqueueOrStartDownload(supported, isManual = false)
                        }
                    } else {
                        Toast.makeText(this, "Clipboard URL not in supported platforms", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Paste failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // 4. QUEUE & AUTO-DOWNLOAD PIPELINE
    // -------------------------------------------------------------------------
    private fun enqueueOrStartDownload(url: String, isManual: Boolean, force: Boolean = false) {
        val supportedUrl = extractSupportedUrl(url)
        if (supportedUrl == null) {
            if (isManual) {
                Toast.makeText(this, "Supported: TikTok, YouTube, Instagram, Facebook", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val normUrl = VideoDownloaderPlugin.normalizeUrl(supportedUrl)

        if (isDownloading) {
            if (!downloadQueue.contains(normUrl) && normUrl != activeNormalizedUrl) {
                downloadQueue.addLast(normUrl)
                updateQueueUI()
                Toast.makeText(this, "Queued (${downloadQueue.size} in queue)", Toast.LENGTH_SHORT).show()
            } else {
                if (isManual) {
                    Toast.makeText(this, "Already downloading or in queue", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // Duplicate check before downloading
        if (!force) {
            val dup = VideoDownloaderPlugin.checkDuplicateDownload(this, normUrl)
            if (dup != null) {
                showAlreadyDownloadedUI(dup, normUrl)
                return
            }
        }

        val lastTime = recentDownloads[normUrl] ?: 0L
        if (!isManual && !force && (System.currentTimeMillis() - lastTime < 120_000L)) {
            return
        }

        startBackgroundDownload(normUrl, force)
    }

    private fun updateQueueUI() {
        tabInnerFrame?.setQueueCount(downloadQueue.size)
        queueStatusText?.text = if (downloadQueue.isNotEmpty()) "QUEUE: ${downloadQueue.size}" else ""
    }

    private fun processNextInQueue() {
        if (!isDownloading && downloadQueue.isNotEmpty()) {
            val nextUrl = downloadQueue.removeFirst()
            updateQueueUI()
            startBackgroundDownload(nextUrl, force = false)
        }
    }

    private fun showAlreadyDownloadedUI(dup: JSONObject, normUrl: String) {
        val density = resources.displayMetrics.density
        val title = dup.optString("title", "Media")
        val fileUri = dup.optString("fileUri", "")
        val isPhoto = dup.optString("type") == "photo"

        progressContainer?.visibility = View.VISIBLE
        progressStatusText?.text = if (isPhoto) "Already downloaded: $title (${dup.optInt("imageCount", 0)} photos)" else "Already downloaded: $title"
        progressSpeedText?.text = "SAVED"
        segmentedProgressBar?.setProgress(100)
        segmentedProgressBar?.setProgressColor(Color.parseColor("#10B981"))

        val row = finishedActionsLayout ?: return
        row.removeAllViews()
        row.visibility = View.VISIBLE

        val actionBtn = createMechanicalButton(
            text = if (isPhoto) "OPEN" else "PLAY",
            density = density,
            isGreen = true,
            onClick = {
                if (isPhoto) {
                    openPicturesFolder(fileUri)
                } else if (fileUri.isNotBlank()) {
                    try {
                        val intent = Intent(this@FloatingBubbleService, VideoPlayerActivity::class.java).apply {
                            this.action = Intent.ACTION_VIEW
                            this.data = Uri.parse(fileUri)
                            this.putExtra("video_title", title)
                            this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        openGalleryFolder()
                    }
                } else {
                    openGalleryFolder()
                }
            }
        )

        val dlAgainBtn = createMechanicalButton(
            text = "DOWNLOAD AGAIN",
            density = density,
            onClick = {
                startBackgroundDownload(normUrl, force = true)
            }
        )

        val closeBtn = createMechanicalButton(
            text = "CLOSE",
            density = density,
            onClick = {
                progressContainer?.visibility = View.GONE
                row.visibility = View.GONE
            }
        )

        row.addView(actionBtn)
        row.addView(dlAgainBtn)
        row.addView(closeBtn)
    }

    private fun openPicturesFolder(firstUri: String? = null) {
        if (!firstUri.isNullOrBlank()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    this.setDataAndType(Uri.parse(firstUri), "image/*")
                    this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            openGalleryFolder()
        }
    }

    private fun startBackgroundDownload(videoUrl: String, force: Boolean = false) {
        if (backendUrl.isBlank()) {
            Toast.makeText(this, "Set Render Backend URL in OmniDrop Settings", Toast.LENGTH_LONG).show()
            progressContainer?.visibility = View.VISIBLE
            progressStatusText?.text = "Server URL missing in Settings."
            segmentedProgressBar?.setProgress(0)
            return
        }

        val normalizedUrl = VideoDownloaderPlugin.normalizeUrl(videoUrl)
        if (VideoDownloaderPlugin.inFlightDownloads.putIfAbsent(normalizedUrl, true) != null) {
            Toast.makeText(this, "Download already in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        isDownloading = true
        isDownloadCancelled = false
        activeNormalizedUrl = normalizedUrl
        activeTargetUri = null
        activeDownloadConnection = null
        activeDownloadOutputStream = null
        lastFailedUrl = null

        mainHandler.post {
            resetIdleFade()
            updateSmartKeyUI()
            retryBtn?.visibility = View.GONE
            tabInnerFrame?.setDownloading(0)
            panelMachineCard?.setGlowColor(Color.parseColor("#00F0FF"))
            segmentedProgressBar?.setProgressColor(Color.parseColor("#00F0FF"))
            progressContainer?.visibility = View.VISIBLE
            finishedActionsLayout?.visibility = View.GONE
            segmentedProgressBar?.setProgress(0)
            progressStatusText?.text = "Connecting to server..."
            progressSpeedText?.text = ""
        }

        backgroundExecutor.execute {
            var targetUri: Uri? = null
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            var output: OutputStream? = null

            try {
                // Step 1: /health wake-up polling
                var isReady = false
                val healthUrl = "${backendUrl.removeSuffix("/")}/health"

                for (attempt in 1..15) {
                    if (isDownloadCancelled) throw InterruptedException("Cancelled")
                    mainHandler.post {
                        progressStatusText?.text = if (attempt > 1) "Waking server... ($attempt/15)" else "Connecting..."
                    }
                    try {
                        val hConn = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                            this.connectTimeout = 3000
                            this.readTimeout = 3000
                            if (apiKey.isNotBlank()) this.setRequestProperty("x-api-key", apiKey)
                        }
                        if (hConn.responseCode == HttpURLConnection.HTTP_OK) {
                            isReady = true
                            hConn.disconnect()
                            break
                        }
                        hConn.disconnect()
                    } catch (_: Exception) {}
                    Thread.sleep(2000L)
                }

                if (isDownloadCancelled) throw InterruptedException("Cancelled")

                if (!isReady) {
                    mainHandler.post {
                        progressStatusText?.text = "Backend unreachable."
                        progressSpeedText?.text = "ERR"
                        isDownloading = false
                        lastFailedUrl = normalizedUrl
                        tabInnerFrame?.setFailed()
                        panelMachineCard?.setGlowColor(Color.parseColor("#EF4444"))
                        segmentedProgressBar?.setProgressColor(Color.parseColor("#EF4444"))
                        retryBtn?.visibility = View.VISIBLE
                        updateSmartKeyUI()
                        performHapticFeedback(isSuccess = false)
                        mainHandler.postDelayed({ processNextInQueue() }, 1200L)
                    }
                    return@execute
                }

                // Step 2: Fetch metadata via /info for History
                mainHandler.post {
                    progressStatusText?.text = "Extracting video metadata..."
                }

                var title = ""
                var thumbnail = ""
                var uploader = "Creator"
                var platform = ""

                try {
                    val infoEndpoint = "${backendUrl.removeSuffix("/")}/info"
                    val iConn = (URL(infoEndpoint).openConnection() as HttpURLConnection).apply {
                        this.requestMethod = "POST"
                        this.connectTimeout = 10000
                        this.readTimeout = 12000
                        this.doOutput = true
                        this.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        if (apiKey.isNotBlank()) this.setRequestProperty("x-api-key", apiKey)
                    }
                    val iPayload = JSONObject().apply { this.put("url", normalizedUrl) }
                    iConn.outputStream.use { os ->
                        os.write(iPayload.toString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    if (iConn.responseCode == HttpURLConnection.HTTP_OK) {
                        val resp = iConn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(resp)
                        title = json.optString("title", "")
                        thumbnail = json.optString("thumbnail", "")
                        uploader = json.optString("uploader", "Creator")
                        platform = json.optString("platform", "")
                    }
                    iConn.disconnect()
                } catch (_: Exception) {}

                if (isDownloadCancelled) throw InterruptedException("Cancelled")

                val cleanPlatform = VideoDownloaderPlugin.cleanPlatformName(platform, normalizedUrl)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val dateStr = sdf.format(java.util.Date())

                if (title.isBlank() || title.equals("video", ignoreCase = true) || title.startsWith("OmniDrop", ignoreCase = true)) {
                    title = "$cleanPlatform video - $dateStr"
                }

                // Step 3: Stream download to MediaStore
                mainHandler.post {
                    progressStatusText?.text = "Streaming full-res media..."
                }

                val downloadEndpoint = "${backendUrl.removeSuffix("/")}/download"
                val urlObj = URL(downloadEndpoint)
                conn = (urlObj.openConnection() as HttpURLConnection).apply {
                    this.requestMethod = "POST"
                    this.connectTimeout = 30000
                    this.readTimeout = 180000
                    this.doInput = true
                    this.doOutput = true
                    this.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    if (apiKey.isNotBlank()) this.setRequestProperty("x-api-key", apiKey)
                }
                activeDownloadConnection = conn

                val payload = JSONObject().apply {
                    this.put("url", normalizedUrl)
                    this.put("quality", defaultQuality)
                }
                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val respCode = conn.responseCode
                if (respCode != HttpURLConnection.HTTP_OK) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $respCode"
                    val shortReason = parseShortServerError(err)
                    lastFailedUrl = normalizedUrl
                    mainHandler.post {
                        progressStatusText?.text = "Failed: $shortReason"
                        progressSpeedText?.text = "ERR"
                        isDownloading = false
                        tabInnerFrame?.setFailed()
                        panelMachineCard?.setGlowColor(Color.parseColor("#EF4444"))
                        segmentedProgressBar?.setProgressColor(Color.parseColor("#EF4444"))
                        retryBtn?.visibility = View.VISIBLE
                        updateSmartKeyUI()
                        performHapticFeedback(isSuccess = false)
                        mainHandler.postDelayed({ processNextInQueue() }, 1200L)
                    }
                    return@execute
                }

                val contentType = conn.contentType ?: ""
                if (contentType.contains("application/zip", ignoreCase = true)) {
                    mainHandler.post {
                        progressStatusText?.text = "Extracting photo slideshow archive..."
                        segmentedProgressBar?.setProgress(30)
                        tabInnerFrame?.setDownloading(30)
                    }

                    val zis = ZipInputStream(conn.inputStream)
                    val resolver = contentResolver
                    val savedUris = ArrayList<Uri>()
                    var zipEntry = zis.nextEntry
                    var photoIndex = 0

                    while (zipEntry != null) {
                        if (isDownloadCancelled) throw InterruptedException("Cancelled")
                        val entryName = zipEntry.name
                        if (!zipEntry.isDirectory && (entryName.endsWith(".jpg", ignoreCase = true) || entryName.endsWith(".jpeg", ignoreCase = true) || entryName.endsWith(".png", ignoreCase = true) || entryName.endsWith(".webp", ignoreCase = true))) {
                            photoIndex++
                            val paddedNum = String.format(Locale.US, "%02d", photoIndex)
                            val cleanTitle = title
                                .replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_")
                                .replace("\\s+".toRegex(), " ")
                                .trim()
                                .take(60)
                                .ifBlank { "TikTok_photo" }
                            val photoDisplayName = "${cleanTitle}_$paddedNum.jpg"

                            val imgValues = ContentValues().apply {
                                this.put(MediaStore.MediaColumns.DISPLAY_NAME, photoDisplayName)
                                this.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    this.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OmniDrop")
                                    this.put(MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                            }

                            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            } else {
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            }

                            val itemUri = resolver.insert(collectionUri, imgValues)
                            if (itemUri != null) {
                                resolver.openOutputStream(itemUri)?.use { outStream ->
                                    val buf = ByteArray(8192)
                                    var len: Int
                                    while (zis.read(buf).also { len = it } != -1) {
                                        if (isDownloadCancelled) throw InterruptedException("Cancelled")
                                        outStream.write(buf, 0, len)
                                    }
                                    outStream.flush()
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val updateValues = ContentValues().apply {
                                        this.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    }
                                    resolver.update(itemUri, updateValues, null, null)
                                }
                                savedUris.add(itemUri)
                                val currentCount = photoIndex
                                mainHandler.post {
                                    val pct = (30 + (currentCount * 5)).coerceAtMost(95)
                                    segmentedProgressBar?.setProgress(pct)
                                    tabInnerFrame?.setDownloading(pct)
                                    progressStatusText?.text = "Saving photo $currentCount to Pictures/OmniDrop..."
                                    progressSpeedText?.text = "SAVING"
                                }
                            }
                        }
                        zis.closeEntry()
                        zipEntry = zis.nextEntry
                    }
                    zis.close()

                    val totalPhotos = savedUris.size
                    val firstUri = savedUris.firstOrNull()?.toString() ?: ""
                    val archiveFileName = "$title [TikTok photos]"
                    val savedTimestamp = System.currentTimeMillis()
                    recentDownloads[normalizedUrl] = savedTimestamp
                    lastFinishedFileUri = firstUri

                    val metaObj = JSONObject().apply {
                        this.put("type", "photo")
                        this.put("title", title)
                        this.put("platform", "TikTok")
                        this.put("uploader", uploader)
                        this.put("thumbnailUrl", thumbnail)
                        this.put("sourceUrl", normalizedUrl)
                        this.put("imageCount", totalPhotos)
                        this.put("downloadTime", savedTimestamp)
                        this.put("fileName", archiveFileName)
                        this.put("fileUri", firstUri)
                    }
                    VideoDownloaderPlugin.saveVideoMetadata(this@FloatingBubbleService, archiveFileName, metaObj)

                    val historyEntry = JSONObject().apply {
                        this.put("id", "dl_$savedTimestamp")
                        this.put("type", "photo")
                        this.put("url", normalizedUrl)
                        this.put("title", title)
                        this.put("thumbnail", thumbnail)
                        this.put("uploader", uploader)
                        this.put("platform", "TikTok")
                        this.put("quality", "photo")
                        this.put("imageCount", totalPhotos)
                        this.put("savedAt", savedTimestamp)
                        this.put("fileName", archiveFileName)
                        this.put("fileUri", firstUri)
                    }
                    VideoDownloaderPlugin.saveHistoryEntry(this@FloatingBubbleService, historyEntry)
                    VideoDownloaderPlugin.instance?.emitNativeDownloadFinished(historyEntry)

                    NotificationHelper.showDownloadFinishedNotification(
                        this@FloatingBubbleService,
                        "Saved $totalPhotos photos",
                        "Saved to Pictures/OmniDrop",
                        firstUri,
                        isImage = true
                    )

                    mainHandler.post {
                        tabInnerFrame?.setFinished()
                        panelMachineCard?.setGlowColor(Color.parseColor("#10B981"))
                        segmentedProgressBar?.setProgressColor(Color.parseColor("#10B981"))
                        isDownloading = false
                        segmentedProgressBar?.setProgress(100)
                        progressStatusText?.text = "Saved $totalPhotos photos"
                        progressSpeedText?.text = "READY"
                        updateSmartKeyUI()
                        performHapticFeedback(isSuccess = true)

                        val row = finishedActionsLayout
                        if (row != null) {
                            row.removeAllViews()
                            row.visibility = View.VISIBLE
                            val openBtn = createMechanicalButton(
                                text = "OPEN",
                                density = resources.displayMetrics.density,
                                isGreen = true,
                                onClick = { openPicturesFolder(firstUri) }
                            )
                            val dismissBtn = createMechanicalButton(
                                text = "CLOSE",
                                density = resources.displayMetrics.density,
                                onClick = {
                                    progressContainer?.visibility = View.GONE
                                    row.visibility = View.GONE
                                }
                            )
                            row.addView(openBtn)
                            row.addView(dismissBtn)
                        }

                        refreshRecentDownloadsUI()
                        scheduleAutoClear()
                        mainHandler.postDelayed({ processNextInQueue() }, 800L)
                    }
                    return@execute
                }

                val isAudio = defaultQuality.equals("audio", ignoreCase = true)
                val ext = if (isAudio) ".mp3" else ".mp4"
                val mediaType = if (isAudio) "audio/mpeg" else "video/mp4"
                val cleanTitle = title
                    .replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_")
                    .replace("\\s+".toRegex(), " ")
                    .trim()
                    .take(80)
                    .ifBlank { "$cleanPlatform video - $dateStr" }
                val fileName = "$cleanTitle [$cleanPlatform]$ext"

                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    this.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    this.put(MediaStore.MediaColumns.MIME_TYPE, mediaType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        this.put(MediaStore.MediaColumns.RELATIVE_PATH, if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop")
                        this.put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val collectionUri = if (isAudio) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                }

                targetUri = resolver.insert(collectionUri, contentValues)
                if (targetUri == null) {
                    mainHandler.post {
                        progressStatusText?.text = "Failed to allocate gallery storage."
                        progressSpeedText?.text = "ERR"
                        isDownloading = false
                        tabInnerFrame?.setFailed()
                        panelMachineCard?.setGlowColor(Color.parseColor("#EF4444"))
                        segmentedProgressBar?.setProgressColor(Color.parseColor("#EF4444"))
                        updateSmartKeyUI()
                        performHapticFeedback(isSuccess = false)
                    }
                    return@execute
                }
                activeTargetUri = targetUri

                output = resolver.openOutputStream(targetUri)
                activeDownloadOutputStream = output
                input = conn.inputStream
                val totalBytes = conn.contentLengthLong

                var bytesCopied: Long = 0
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastProgressUpdate = System.currentTimeMillis()
                var bytesSinceLastSample: Long = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isDownloadCancelled) throw InterruptedException("Cancelled")
                    output?.write(buffer, 0, bytesRead)
                    bytesCopied += bytesRead
                    bytesSinceLastSample += bytesRead

                    val now = System.currentTimeMillis()
                    val diff = now - lastProgressUpdate
                    if (diff >= 300) {
                        val speedMbPerSec = (bytesSinceLastSample.toDouble() / (1024 * 1024)) / (diff / 1000.0)
                        val totalMb = String.format(Locale.US, "%.1f", bytesCopied / (1024.0 * 1024.0))
                        val pct = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt().coerceIn(1, 99) else 50

                        lastProgressUpdate = now
                        bytesSinceLastSample = 0

                        mainHandler.post {
                            segmentedProgressBar?.setProgress(pct)
                            tabInnerFrame?.setDownloading(pct)
                            progressStatusText?.text = "$totalMb MB ($pct%)"
                            progressSpeedText?.text = String.format(Locale.US, "DN %.1f MB/s", speedMbPerSec)
                        }
                    }
                }
                output?.flush()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = ContentValues().apply {
                        this.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(targetUri, updateValues, null, null)
                }

                val savedTimestamp = System.currentTimeMillis()
                recentDownloads[normalizedUrl] = savedTimestamp
                lastFinishedFileUri = targetUri.toString()

                val metaObj = JSONObject().apply {
                    this.put("title", title)
                    this.put("platform", cleanPlatform)
                    this.put("uploader", uploader)
                    this.put("thumbnailUrl", thumbnail)
                    this.put("sourceUrl", normalizedUrl)
                    this.put("downloadTime", savedTimestamp)
                    this.put("fileName", fileName)
                    this.put("fileUri", targetUri.toString())
                }
                VideoDownloaderPlugin.saveVideoMetadata(this, fileName, metaObj)

                val historyEntry = JSONObject().apply {
                    this.put("id", "dl_$savedTimestamp")
                    this.put("url", normalizedUrl)
                    this.put("title", title)
                    this.put("thumbnail", thumbnail)
                    this.put("uploader", uploader)
                    this.put("platform", cleanPlatform)
                    this.put("quality", defaultQuality)
                    this.put("savedAt", savedTimestamp)
                    this.put("fileName", fileName)
                    this.put("fileUri", targetUri.toString())
                }
                VideoDownloaderPlugin.saveHistoryEntry(this, historyEntry)
                VideoDownloaderPlugin.instance?.emitNativeDownloadFinished(historyEntry)

                NotificationHelper.showDownloadFinishedNotification(
                    this,
                    "Saved to Gallery",
                    "$fileName saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}",
                    targetUri.toString()
                )

                mainHandler.post {
                    tabInnerFrame?.setFinished()
                    panelMachineCard?.setGlowColor(Color.parseColor("#10B981"))
                    segmentedProgressBar?.setProgressColor(Color.parseColor("#10B981"))
                    isDownloading = false
                    segmentedProgressBar?.setProgress(100)
                    progressStatusText?.text = "Saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}"
                    progressSpeedText?.text = "READY"
                    updateSmartKeyUI()
                    performHapticFeedback(isSuccess = true)

                    val row = finishedActionsLayout
                    if (row != null) {
                        row.removeAllViews()
                        row.visibility = View.VISIBLE
                        val openBtn = createMechanicalButton(
                            text = "OPEN VIDEO",
                            density = resources.displayMetrics.density,
                            isGreen = true,
                            onClick = { openFinishedVideo() }
                        )
                        val dismissBtn = createMechanicalButton(
                            text = "CLOSE",
                            density = resources.displayMetrics.density,
                            onClick = {
                                progressContainer?.visibility = View.GONE
                                row.visibility = View.GONE
                            }
                        )
                        row.addView(openBtn)
                        row.addView(dismissBtn)
                    }

                    refreshRecentDownloadsUI()
                    scheduleAutoClear()
                    mainHandler.postDelayed({ processNextInQueue() }, 800L)
                }

            } catch (e: Exception) {
                if (targetUri != null) {
                    try { contentResolver.delete(targetUri, null, null) } catch (_: Exception) {}
                }
                if (isDownloadCancelled) {
                    mainHandler.post {
                        progressStatusText?.text = "Cancelled"
                        progressSpeedText?.text = ""
                        segmentedProgressBar?.setProgress(0)
                        tabInnerFrame?.resetToIdle()
                        panelMachineCard?.setGlowColor(Color.parseColor("#344259"))
                        isDownloading = false
                        updateSmartKeyUI()
                    }
                } else {
                    val rawMsg = e.message ?: "Failed"
                    val shortReason = parseShortServerError(rawMsg)
                    lastFailedUrl = normalizedUrl
                    mainHandler.post {
                        progressStatusText?.text = "Failed: $shortReason"
                        progressSpeedText?.text = "ERR"
                        isDownloading = false
                        tabInnerFrame?.setFailed()
                        panelMachineCard?.setGlowColor(Color.parseColor("#EF4444"))
                        segmentedProgressBar?.setProgressColor(Color.parseColor("#EF4444"))
                        retryBtn?.visibility = View.VISIBLE
                        updateSmartKeyUI()
                        performHapticFeedback(isSuccess = false)
                        mainHandler.postDelayed({ processNextInQueue() }, 1200L)
                    }
                }
            } finally {
                VideoDownloaderPlugin.inFlightDownloads.remove(normalizedUrl)
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }
    }

    private fun scheduleAutoClear() {
        if (!autoClearEnabled) return
        autoClearRunnable?.let { mainHandler.removeCallbacks(it) }
        autoClearRunnable = Runnable {
            if (!isDownloading && (System.currentTimeMillis() - lastTypingTimestamp >= 3800L)) {
                linkEditText?.setText("")
                progressContainer?.visibility = View.GONE
                finishedActionsLayout?.visibility = View.GONE
                progressStatusText?.text = ""
                progressSpeedText?.text = ""
                segmentedProgressBar?.setProgress(0)
                updateSmartKeyUI()
            }
        }
        mainHandler.postDelayed(autoClearRunnable!!, 4000L)
    }

    private fun parseShortServerError(rawErr: String): String {
        if (rawErr.isBlank()) return "Server error"
        try {
            if (rawErr.startsWith("{")) {
                val json = JSONObject(rawErr)
                if (json.has("detail")) {
                    val d = json.getString("detail").trim()
                    if (d.isNotBlank()) return d.take(85)
                }
                if (json.has("error")) {
                    val e = json.getString("error").trim()
                    if (e.isNotBlank()) return e.take(85)
                }
            }
        } catch (_: Exception) {}
        val cleaned = rawErr.replace("\n", " ").trim()
        return if (cleaned.length > 85) cleaned.take(82) + "..." else cleaned
    }

    private fun performHapticFeedback(isSuccess: Boolean) {
        if (!vibrateEnabled) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null && audioManager.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) {
                return
            }

            val hasVibratePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.VIBRATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasVibratePermission) return

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (isSuccess) {
                    val effect = android.os.VibrationEffect.createOneShot(45L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator.vibrate(effect)
                } else {
                    val pattern = longArrayOf(0, 40, 60, 40)
                    val effect = android.os.VibrationEffect.createWaveform(pattern, -1)
                    vibrator.vibrate(effect)
                }
            } else {
                @Suppress("DEPRECATION")
                if (isSuccess) {
                    vibrator.vibrate(45L)
                } else {
                    val pattern = longArrayOf(0, 40, 60, 40)
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun openFinishedVideo() {
        val uriStr = lastFinishedFileUri
        if (!uriStr.isNullOrBlank()) {
            try {
                val playerIntent = Intent(this, VideoPlayerActivity::class.java).apply {
                    this.action = Intent.ACTION_VIEW
                    this.data = Uri.parse(uriStr)
                    this.putExtra("video_title", "OmniDrop Video")
                    this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(playerIntent)
            } catch (_: Exception) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    this.setDataAndType(Uri.parse(uriStr), "video/*")
                    this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    openGalleryFolder()
                }
            }
        }
    }

    private fun openGalleryFolder() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/video")
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_MAIN).apply {
                this.addCategory(Intent.CATEGORY_APP_GALLERY)
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(this, "Files saved in Movies/OmniDrop", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchMainActivity(urlToPass: String?) {
        try {
            val mainClass = Class.forName("com.omnidrop.app.MainActivity")
            val intent = Intent(this, mainClass).apply {
                this.action = Intent.ACTION_VIEW
                if (!urlToPass.isNullOrBlank()) {
                    this.putExtra("url_to_download", urlToPass)
                }
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -------------------------------------------------------------------------
    // 5. DUAL PING (SERVER & NET) AND TRAFFIC MONITORING
    // -------------------------------------------------------------------------
    private fun measureDualPing() {
        backgroundExecutor.execute {
            // 1. Measure NET (https://www.gstatic.com/generate_204)
            var netElapsed = -1
            try {
                val nStart = System.currentTimeMillis()
                val nConn = (URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                    this.connectTimeout = 3000
                    this.readTimeout = 3000
                }
                if (nConn.responseCode in 200..204) {
                    netElapsed = (System.currentTimeMillis() - nStart).toInt()
                }
                nConn.disconnect()
            } catch (_: Exception) {}

            // 2. Measure SERVER ({backendUrl}/health)
            var srvElapsed = -1
            var srvState = "OFF"

            if (backendUrl.isNotBlank()) {
                val sEndpoint = "${backendUrl.removeSuffix("/")}/health"
                val sStart = System.currentTimeMillis()
                try {
                    val sConn = (URL(sEndpoint).openConnection() as HttpURLConnection).apply {
                        this.connectTimeout = 3000
                        this.readTimeout = 3000
                        if (apiKey.isNotBlank()) this.setRequestProperty("x-api-key", apiKey)
                    }
                    val code = sConn.responseCode
                    val diff = (System.currentTimeMillis() - sStart).toInt()
                    sConn.disconnect()

                    if (code == HttpURLConnection.HTTP_OK) {
                        srvElapsed = diff
                        srvState = if (diff > 3000) "SLEEP" else "ONLINE"
                    } else {
                        srvState = "SLEEP"
                    }
                } catch (_: Exception) {
                    val diff = (System.currentTimeMillis() - sStart).toInt()
                    srvState = if (diff >= 3000) "SLEEP" else "OFF"
                }
            }

            mainHandler.post {
                serverPingMs = srvElapsed
                serverState = srvState
                netPingMs = netElapsed

                updateInstrumentUI()
            }
        }
    }

    private fun formatPingDisplay(pingMs: Int, stateStr: String): String {
        if (stateStr == "SLEEP") return "ZZ"
        if (stateStr == "WAKING") return "WAKE"
        if (stateStr == "OFF" || pingMs < 0) return "OFF"
        return when {
            pingMs <= 999 -> "${pingMs}ms"
            pingMs < 10000 -> String.format(Locale.US, "%.1fs", pingMs / 1000.0)
            else -> "10s+"
        }
    }

    private fun updateInstrumentUI() {
        tabInnerFrame?.updateStatus(serverState, serverPingMs)

        // Server Instrument
        when (serverState) {
            "ONLINE" -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#10B981"))
                serverStatusText?.text = formatPingDisplay(serverPingMs, serverState)
                serverStatusText?.setTextColor(Color.parseColor("#00F0FF"))
            }
            "SLEEP" -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#F59E0B"))
                serverStatusText?.text = "ZZ"
                serverStatusText?.setTextColor(Color.parseColor("#F59E0B"))
            }
            "WAKING" -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#F59E0B"))
                serverStatusText?.text = "WAKE"
                serverStatusText?.setTextColor(Color.parseColor("#F59E0B"))
            }
            else -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#EF4444"))
                serverStatusText?.text = "OFF"
                serverStatusText?.setTextColor(Color.parseColor("#EF4444"))
            }
        }

        // Net Instrument
        if (netPingMs >= 0) {
            val netColor = if (netPingMs < 300) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
            netLedDot?.background = createCircleDrawable(netColor)
            netStatusText?.text = formatPingDisplay(netPingMs, "ONLINE")
            netStatusText?.setTextColor(Color.parseColor("#00F0FF"))
        } else {
            netLedDot?.background = createCircleDrawable(Color.parseColor("#EF4444"))
            netStatusText?.text = "OFF"
            netStatusText?.setTextColor(Color.parseColor("#EF4444"))
        }

        // Ping Graph
        val sample = when {
            serverPingMs > 0 -> serverPingMs
            netPingMs > 0 -> netPingMs
            else -> 1200
        }
        pingGraphView?.addSample(sample)
    }

    private fun sampleTrafficStats() {
        val now = System.currentTimeMillis()
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()

        val timeDiff = now - lastTrafficSampleTime
        if (timeDiff in 500..2000 && lastRxBytes > 0) {
            val rxDiff = (currentRx - lastRxBytes).coerceAtLeast(0)
            val txDiff = (currentTx - lastTxBytes).coerceAtLeast(0)

            val rxSpeed = rxDiff.toDouble() / (timeDiff / 1000.0)
            val txSpeed = txDiff.toDouble() / (timeDiff / 1000.0)

            trafficDnText?.text = formatCappedSpeed(rxSpeed)
            trafficUpText?.text = formatCappedSpeed(txSpeed)
        }

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTrafficSampleTime = now
    }

    private fun formatCappedSpeed(bytesPerSec: Double): String {
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return when {
            mb >= 999.0 -> "999 MB/s"
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB/s", mb)
            bytesPerSec >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec.coerceAtLeast(0.0))
        }
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            this.setColor(color)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacks(dualPingRunnable)
        mainHandler.removeCallbacks(trafficRunnable)
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(updateCheckRunnable)
        autoClearRunnable?.let { mainHandler.removeCallbacks(it) }

        tabRootView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        panelRootOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        backgroundExecutor.shutdownNow()
    }

    private fun checkAppUpdatesInBackground() {
        backgroundExecutor.execute {
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    this.connectTimeout = 8000
                    this.readTimeout = 8000
                    this.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    this.setRequestProperty("User-Agent", "OmniDrop-Android")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val releaseObj = JSONObject(responseText)
                    val tagName = releaseObj.optString("tag_name", "")
                    val body = releaseObj.optString("body", "")

                    val remoteVersionStr = tagName.substringAfterLast(".").filter { it.isDigit() }
                    val remoteCode = remoteVersionStr.toIntOrNull() ?: 0

                    val currentCode = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getPackageInfo(packageName, 0).versionCode
                        }
                    } catch (_: Exception) { 0 }

                    val skippedVersion = prefs?.getInt(PREF_SKIPPED_UPDATE_VERSION, 0) ?: 0
                    val lastNotified = prefs?.getInt(PREF_LAST_NOTIFIED_UPDATE_VERSION, 0) ?: 0

                    if (remoteCode > currentCode && remoteCode != skippedVersion && remoteCode != lastNotified) {
                        showUpdateNotification(tagName, body, remoteCode)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {}
        }
    }

    private fun showUpdateNotification(tagName: String, releaseNotes: String, remoteCode: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val mainClass = Class.forName("com.omnidrop.app.MainActivity")
            val intent = Intent(this, mainClass).apply {
                this.action = Intent.ACTION_VIEW
                this.putExtra("open_tab", "settings")
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(this, NOTIFICATION_ID_UPDATE, intent, flags)

            val noteSnippet = if (releaseNotes.isNotBlank()) releaseNotes.take(280) else "New version $tagName is ready to install."

            val notif = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("OmniDrop update ready: $tagName")
                .setContentText(noteSnippet)
                .setStyle(NotificationCompat.BigTextStyle().bigText(if (releaseNotes.isNotBlank()) releaseNotes else "New version $tagName is ready to install. Tap to download and install."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(NOTIFICATION_ID_UPDATE, notif)

            prefs?.edit()?.apply {
                this.putInt(PREF_LAST_NOTIFIED_UPDATE_VERSION, remoteCode)
                this.apply()
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // 6. HARDWARE MACHINE CUSTOM VIEWS
    // -------------------------------------------------------------------------

    /**
     * Machined Metal Latch Handle for the Edge Tab:
     * - Dark gunmetal with bevel highlights
     * - Status LED dot
     * - Real-time state colors: IDLE (ping/ZZ/OFF), DOWNLOADING (cyan bottom-up fill + %), FINISHED (green + ✓), FAILED (red + !)
     * - Small queue count badge
     */
    class EdgeLatchTabView(context: Context, private var isLeft: Boolean) : FrameLayout(context) {
        enum class TabVisualState {
            IDLE,
            DOWNLOADING,
            FINISHED,
            FAILED
        }

        private var visualState = TabVisualState.IDLE
        private var state: String = "OFF"
        private var pingMs: Int = -1
        private var downloadPercent: Int = 0
        private var queueCount: Int = 0
        private var pulseAnimator: ValueAnimator? = null
        private var pulseScale: Float = 1.0f

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dlFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bevelLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#44526B")
            this.style = Paint.Style.STROKE
            this.strokeWidth = 2f * context.resources.displayMetrics.density
        }
        private val bevelDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#05080E")
            this.style = Paint.Style.STROKE
            this.strokeWidth = 2.5f * context.resources.displayMetrics.density
        }
        private val highlightLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#15FFFFFF")
            this.style = Paint.Style.STROKE
            this.strokeWidth = 1f * context.resources.displayMetrics.density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#00F0FF")
            this.textSize = 10f * context.resources.displayMetrics.scaledDensity
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textAlign = Paint.Align.CENTER
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#94A3B8")
            this.textSize = 8f * context.resources.displayMetrics.scaledDensity
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            this.textAlign = Paint.Align.CENTER
        }
        private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            this.textSize = 18f * context.resources.displayMetrics.scaledDensity
            this.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            this.textAlign = Paint.Align.CENTER
        }
        private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#00F0FF")
        }
        private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#083344")
            this.style = Paint.Style.STROKE
            this.strokeWidth = 1.5f * context.resources.displayMetrics.density
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#05080E")
            this.textSize = 8.5f * context.resources.displayMetrics.scaledDensity
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textAlign = Paint.Align.CENTER
        }

        init {
            setWillNotDraw(false)
        }

        fun setIsLeft(left: Boolean) {
            isLeft = left
            postInvalidate()
        }

        fun updateStatus(srvState: String, ping: Int) {
            state = srvState
            pingMs = ping
            if (visualState == TabVisualState.IDLE) {
                postInvalidate()
            }
        }

        fun setDownloading(percent: Int) {
            visualState = TabVisualState.DOWNLOADING
            downloadPercent = percent.coerceIn(0, 100)
            pulseAnimator?.cancel()
            pulseScale = 1.0f
            scaleX = 1.0f
            scaleY = 1.0f
            postInvalidate()
        }

        fun setFinished() {
            visualState = TabVisualState.FINISHED
            startPulseAnimation()
            postInvalidate()
        }

        fun setFailed() {
            visualState = TabVisualState.FAILED
            startPulseAnimation()
            postInvalidate()
        }

        fun isFinishedOrFailed(): Boolean {
            return visualState == TabVisualState.FINISHED || visualState == TabVisualState.FAILED
        }

        fun resetToIdle() {
            visualState = TabVisualState.IDLE
            pulseAnimator?.cancel()
            pulseScale = 1.0f
            scaleX = 1.0f
            scaleY = 1.0f
            postInvalidate()
        }

        fun setQueueCount(count: Int) {
            queueCount = count
            postInvalidate()
        }

        private fun startPulseAnimation() {
            pulseAnimator?.cancel()
            val anim = ValueAnimator.ofFloat(1.0f, 1.14f, 1.0f).apply {
                this.duration = 450L
                this.repeatCount = 2
                this.addUpdateListener { va ->
                    val v = va.animatedValue as Float
                    pulseScale = v
                    scaleX = v
                    scaleY = v
                }
            }
            pulseAnimator = anim
            anim.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = context.resources.displayMetrics.density
            val r = 16f * density

            val path = Path()
            val radii = if (isLeft) {
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            val tabRect = RectF(0f, 0f, w, h)
            path.addRoundRect(tabRect, radii, Path.Direction.CW)

            // 1. Base Body Gradient
            when (visualState) {
                TabVisualState.FINISHED -> {
                    bodyPaint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#059669"), Color.parseColor("#047857"), Shader.TileMode.CLAMP)
                    canvas.drawPath(path, bodyPaint)
                }
                TabVisualState.FAILED -> {
                    bodyPaint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#DC2626"), Color.parseColor("#991B1B"), Shader.TileMode.CLAMP)
                    canvas.drawPath(path, bodyPaint)
                }
                else -> {
                    bodyPaint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#101522"), Color.parseColor("#192231"), Shader.TileMode.CLAMP)
                    canvas.drawPath(path, bodyPaint)

                    // If DOWNLOADING: Fill from bottom up with cyan glow proportional to percent
                    if (visualState == TabVisualState.DOWNLOADING) {
                        val fillTop = h * (1f - (downloadPercent / 100f))
                        canvas.save()
                        canvas.clipPath(path)
                        dlFillPaint.shader = LinearGradient(0f, fillTop, 0f, h, Color.parseColor("#00F0FF"), Color.parseColor("#0284C7"), Shader.TileMode.CLAMP)
                        dlFillPaint.alpha = 180
                        canvas.drawRect(0f, fillTop, w, h, dlFillPaint)
                        canvas.restore()
                    }
                }
            }

            // 2. Bevel & Specular Highlight
            val bevelColor = when (visualState) {
                TabVisualState.FINISHED -> Color.parseColor("#34D399")
                TabVisualState.FAILED -> Color.parseColor("#F87171")
                TabVisualState.DOWNLOADING -> Color.parseColor("#00F0FF")
                else -> if (isLeft) Color.parseColor("#44526B") else Color.parseColor("#05080E")
            }
            bevelLightPaint.color = bevelColor
            canvas.drawPath(path, bevelLightPaint)
            canvas.drawLine(w * 0.5f, h * 0.15f, w * 0.5f, h * 0.85f, highlightLinePaint)

            // 3. Status LED Dot
            val ledColor = when (visualState) {
                TabVisualState.FINISHED -> Color.parseColor("#34D399")
                TabVisualState.FAILED -> Color.parseColor("#F87171")
                TabVisualState.DOWNLOADING -> Color.parseColor("#00F0FF")
                else -> when (state) {
                    "ONLINE" -> Color.parseColor("#10B981")
                    "SLEEP" -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#EF4444")
                }
            }
            ledPaint.color = ledColor
            canvas.drawCircle(w * 0.5f, 15f * density, 3.5f * density, ledPaint)

            // 4. Center Display Text / Symbol
            val textY = 44f * density
            when (visualState) {
                TabVisualState.FINISHED -> {
                    symbolPaint.textSize = 17f * context.resources.displayMetrics.scaledDensity
                    canvas.drawText("✓", w * 0.5f, textY + (2f * density), symbolPaint)
                }
                TabVisualState.FAILED -> {
                    symbolPaint.textSize = 19f * context.resources.displayMetrics.scaledDensity
                    canvas.drawText("!", w * 0.5f, textY + (2f * density), symbolPaint)
                }
                TabVisualState.DOWNLOADING -> {
                    textPaint.color = Color.parseColor("#00F0FF")
                    canvas.drawText("${downloadPercent}%", w * 0.5f, textY, textPaint)
                }
                TabVisualState.IDLE -> {
                    when (state) {
                        "ONLINE" -> {
                            textPaint.color = Color.parseColor("#00F0FF")
                            val display = if (pingMs in 0..999) "$pingMs" else if (pingMs > 999) String.format(Locale.US, "%.1fs", pingMs / 1000.0) else "—"
                            canvas.drawText(display, w * 0.5f, textY, textPaint)
                            if (pingMs in 0..999) {
                                canvas.drawText("ms", w * 0.5f, textY + (12f * density), subTextPaint)
                            }
                        }
                        "SLEEP" -> {
                            textPaint.color = Color.parseColor("#F59E0B")
                            canvas.drawText("ZZ", w * 0.5f, textY, textPaint)
                        }
                        else -> {
                            textPaint.color = Color.parseColor("#EF4444")
                            canvas.drawText("OFF", w * 0.5f, textY, textPaint)
                        }
                    }
                }
            }

            // 5. Queue Badge (if queueCount > 0)
            if (queueCount > 0) {
                val badgeX = if (isLeft) w - (8f * density) else (8f * density)
                val badgeY = 9f * density
                val badgeR = 6.5f * density
                canvas.drawCircle(badgeX, badgeY, badgeR, badgeBgPaint)
                canvas.drawCircle(badgeX, badgeY, badgeR, badgeBorderPaint)
                canvas.drawText("$queueCount", badgeX, badgeY + (3f * density), badgeTextPaint)
            }
        }
    }

    /**
     * Machine Card Body with brushed-metal bevel, corner screws, and solid gunmetal
     */
    class MachineCardView(context: Context) : FrameLayout(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#07FFFFFF")
            this.strokeWidth = 1f
        }
        private val bevelLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#344259")
            this.style = Paint.Style.STROKE
            this.strokeWidth = 2f * context.resources.displayMetrics.density
        }
        private val screwOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#263245")
        }
        private val screwSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#0A0D13")
            this.strokeWidth = 1.5f * context.resources.displayMetrics.density
            this.style = Paint.Style.STROKE
        }

        init {
            setWillNotDraw(false)
        }

        fun setGlowColor(color: Int) {
            bevelLightPaint.color = color
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = context.resources.displayMetrics.density
            val r = 18f * density

            // 1. Opaque Dark Gunmetal Body Gradient
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#0B0E14"), Color.parseColor("#151A24"), Shader.TileMode.CLAMP)
            val rect = RectF(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, bgPaint)

            // 2. Faint Scanlines
            var y = 8f * density
            while (y < h) {
                canvas.drawLine(0f, y, w, y, gridPaint)
                y += 6f * density
            }

            // 3. Beveled Metallic Bezel
            canvas.drawRoundRect(rect, r, r, bevelLightPaint)

            // 4. Four Corner Screws
            val inset = 12f * density
            val screwR = 4f * density
            val corners = arrayOf(
                Pair(inset, inset),
                Pair(w - inset, inset),
                Pair(inset, h - inset),
                Pair(w - inset, h - inset)
            )
            for (p in corners) {
                canvas.drawCircle(p.first, p.second, screwR, screwOuterPaint)
                canvas.drawLine(p.first - 2.5f * density, p.second, p.first + 2.5f * density, p.second, screwSlotPaint)
                canvas.drawLine(p.first, p.second - 2.5f * density, p.first, p.second + 2.5f * density, screwSlotPaint)
            }

            super.onDraw(canvas)
        }
    }

    /**
     * Segmented LED Progress Bar with glowing active blocks
     */
    class SegmentedProgressBar(context: Context) : View(context) {
        private var progressPercent: Int = 0
        private val totalSegments = 16
        private var barColor: Int = Color.parseColor("#00F0FF")
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#00F0FF")
        }
        private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#131B29")
            this.style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#1E2B3E")
            this.style = Paint.Style.STROKE
            this.strokeWidth = 1f
        }

        init {
            setWillNotDraw(false)
        }

        fun setProgress(pct: Int) {
            progressPercent = pct.coerceIn(0, 100)
            postInvalidate()
        }

        fun setProgressColor(color: Int) {
            barColor = color
            activePaint.color = color
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = context.resources.displayMetrics.density
            val gap = 3f * density
            val segmentW = (w - (gap * (totalSegments - 1))) / totalSegments.toFloat()
            val activeCount = ((progressPercent / 100f) * totalSegments).toInt()

            activePaint.color = barColor

            for (i in 0 until totalSegments) {
                val left = i * (segmentW + gap)
                val right = left + segmentW
                val r = RectF(left, 0f, right, h)

                if (i < activeCount) {
                    canvas.drawRoundRect(r, 2f * density, 2f * density, activePaint)
                } else {
                    canvas.drawRoundRect(r, 2f * density, 2f * density, inactivePaint)
                    canvas.drawRoundRect(r, 2f * density, 2f * density, borderPaint)
                }
            }
        }
    }

    /**
     * Hardware Ping Graph with grid and glowing trace line
     */
    class PingGraphView(context: Context) : View(context) {
        private val samples = ArrayList<Int>(30)
        private val maxSamples = 30

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.strokeWidth = 2.5f * context.resources.displayMetrics.density
            this.strokeCap = Paint.Cap.ROUND
            this.strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.color = Color.parseColor("#1A2436")
            this.strokeWidth = 1f
        }
        private val linePath = Path()
        private val fillPath = Path()

        init {
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6f * context.resources.displayMetrics.density
                this.setColor(Color.parseColor("#0A0E16"))
                this.setStroke((1 * context.resources.displayMetrics.density).toInt(), Color.parseColor("#1C2638"))
            }
            this.background = shape
        }

        fun addSample(pingMs: Int) {
            if (samples.size >= maxSamples) {
                samples.removeAt(0)
            }
            samples.add(pingMs)
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            canvas.drawLine(0f, h * 0.33f, w, h * 0.33f, gridPaint)
            canvas.drawLine(0f, h * 0.66f, w, h * 0.66f, gridPaint)

            if (samples.size < 2) return

            linePath.reset()
            fillPath.reset()

            val stepX = w / (maxSamples - 1).toFloat()
            val maxPing = 1200f
            val minPing = 20f

            val startIndex = maxSamples - samples.size

            for (i in samples.indices) {
                val ping = samples[i].toFloat().coerceIn(minPing, maxPing)
                val norm = 1f - ((ping - minPing) / (maxPing - minPing))
                val x = (startIndex + i) * stepX
                val y = (norm * (h * 0.7f)) + (h * 0.15f)

                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            val lastX = (startIndex + samples.size - 1) * stepX
            fillPath.lineTo(lastX, h)
            fillPath.close()

            fillPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#4400F0FF"), Color.parseColor("#0000F0FF"), Shader.TileMode.CLAMP)
            canvas.drawPath(fillPath, fillPaint)

            linePaint.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#00F0FF"), Color.parseColor("#38BDF8"), Shader.TileMode.CLAMP)
            canvas.drawPath(linePath, linePaint)
        }
    }
}

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
                return if (isSupported) raw else null
            } catch (e: Exception) {
                return null
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var prefs: SharedPreferences? = null

    // Edge Tab Views
    private var tabRootView: FrameLayout? = null
    private var tabInnerLayout: LinearLayout? = null
    private var tabDotView: View? = null
    private var tabPingText: TextView? = null
    private var tabMsLabel: TextView? = null
    private var tabLayoutParams: WindowManager.LayoutParams? = null

    // Panel Views
    private var panelRootOverlay: FrameLayout? = null
    private var panelCard: LinearLayout? = null
    private var panelChipDot: View? = null
    private var panelChipText: TextView? = null
    private var panelSpeedText: TextView? = null
    private var pingGraphView: PingGraphView? = null
    private var linkEditText: EditText? = null
    private var manualDownloadBtn: TextView? = null
    private var progressContainer: LinearLayout? = null
    private var progressStatusText: TextView? = null
    private var progressSpeedText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var finishedActionsLayout: LinearLayout? = null
    private var autoDownloadToggleText: TextView? = null
    private var autoDownloadToggleBg: View? = null

    // State
    private var isLeftEdge: Boolean = true
    private var tabYPosition: Int = 300
    private var isPanelOpen: Boolean = false
    private var isDownloading: Boolean = false
    private var autoDownloadEnabled: Boolean = true
    private var backendUrl: String = ""
    private var apiKey: String = ""
    private var defaultQuality: String = "best"
    private var lastFinishedFileUri: String? = null
    private var lastFinishedFileName: String? = null

    // Network & Ping Monitor
    private val pingHistory = ArrayList<Int>(30)
    private var currentPingMs: Int = -1
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastTrafficSampleTime: Long = 0
    private var isScreenOn: Boolean = true

    // Deduplication cache: URL -> timestamp
    private val recentDownloads = ConcurrentHashMap<String, Long>()

    // Threading
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newFixedThreadPool(3)

    // Periodic Ping Runnable (every 5 seconds)
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                measurePing()
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    // Traffic Stats Runnable (every 1 second when panel is visible)
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

    // Screen On/Off Receiver for Battery Saver
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action
            if (Intent.ACTION_SCREEN_OFF == act) {
                isScreenOn = false
            } else if (Intent.ACTION_SCREEN_ON == act) {
                isScreenOn = true
                measurePing()
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

        mainHandler.post(pingRunnable)
    }

    private fun loadSavedSettings() {
        prefs?.let { p ->
            isLeftEdge = p.getBoolean(PREF_TAB_IS_LEFT, true)
            tabYPosition = p.getInt(PREF_TAB_Y, 350)
            autoDownloadEnabled = p.getBoolean(PREF_AUTO_DOWNLOAD, true)
            backendUrl = p.getString(PREF_BACKEND_URL, "") ?: ""
            apiKey = p.getString(PREF_API_KEY, "") ?: ""
            defaultQuality = p.getString(PREF_DEFAULT_QUALITY, "best") ?: "best"
        }
    }

    fun updateSettings(newUrl: String, newApiKey: String, newQuality: String, newAutoDownload: Boolean) {
        backendUrl = newUrl.trim()
        apiKey = newApiKey.trim()
        defaultQuality = newQuality
        autoDownloadEnabled = newAutoDownload
        prefs?.edit()?.apply {
            this.putString(PREF_BACKEND_URL, backendUrl)
            this.putString(PREF_API_KEY, apiKey)
            this.putString(PREF_DEFAULT_QUALITY, defaultQuality)
            this.putBoolean(PREF_AUTO_DOWNLOAD, autoDownloadEnabled)
            this.apply()
        }
        mainHandler.post {
            updateAutoDownloadUI()
            measurePing()
        }
    }

    fun handleSharedUrl(sharedUrl: String) {
        mainHandler.post {
            if (!isPanelOpen) {
                openPanel()
            }
            linkEditText?.setText(sharedUrl)
            triggerLinkProcessing(sharedUrl, isManual = false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniDrop Edge Tab Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                this.description = "Keeps the OmniDrop edge quick-download tab active"
                this.setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
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
            .setContentTitle("OmniDrop Edge Tab Active")
            .setContentText("Swipe edge tab to open Game Space download tool")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // -------------------------------------------------------------------------
    // 1. EDGE TAB IMPLEMENTATION
    // -------------------------------------------------------------------------
    private fun initEdgeTab() {
        val density = resources.displayMetrics.density
        val tabWidth = (28 * density).toInt()
        val tabHeight = (72 * density).toInt()

        val root = FrameLayout(this)
        tabRootView = root

        val inner = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER_HORIZONTAL
            this.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        tabInnerLayout = inner

        // Status colored dot
        val dot = View(this).apply {
            val dotSize = (6 * density).toInt()
            val dotLp = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                this.bottomMargin = (4 * density).toInt()
            }
            this.layoutParams = dotLp
            this.background = createCircleDrawable(Color.parseColor("#10B981"))
        }
        tabDotView = dot
        inner.addView(dot)

        // Ping number TextView
        val pingTv = TextView(this).apply {
            this.text = "—"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(Color.WHITE)
            this.gravity = Gravity.CENTER
            this.includeFontPadding = false
        }
        tabPingText = pingTv
        inner.addView(pingTv)

        // "ms" subtext
        val msTv = TextView(this).apply {
            this.text = "ms"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            this.setTextColor(Color.parseColor("#A78BFA"))
            this.gravity = Gravity.CENTER
            this.includeFontPadding = false
        }
        tabMsLabel = msTv
        inner.addView(msTv)

        root.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        updateTabBackground(isFlashingGreen = false)

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

    private fun updateTabBackground(isFlashingGreen: Boolean) {
        val density = resources.displayMetrics.density
        val r = 16 * density

        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            if (isLeftEdge) {
                // Docked to left: rounded on right side
                this.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                // Docked to right: rounded on left side
                this.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }

            if (isFlashingGreen) {
                this.setColor(Color.parseColor("#E6065F46"))
                this.setStroke((2 * density).toInt(), Color.parseColor("#10B981"))
            } else {
                this.setColor(Color.parseColor("#E60B1120"))
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#A855F7"))
            }
        }
        tabInnerLayout?.background = shape
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
                        initialX = lp.x
                        initialY = lp.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        touchStartTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (event.rawY - touchStartY).toInt()
                        lp.y = (initialY + dy).coerceIn(50, screenHeight - tabHeight - 100)
                        windowManager?.updateViewLayout(v, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchStartTime
                        val totalDx = event.rawX - touchStartX
                        val totalDy = event.rawY - touchStartY

                        // Swipe Inward Detection
                        val isSwipeInward = if (isLeftEdge) {
                            totalDx > 35 && Math.abs(totalDy) < 80
                        } else {
                            totalDx < -35 && Math.abs(totalDy) < 80
                        }

                        // Plain Tap Detection
                        val isTap = duration < 300 && Math.abs(totalDx) < 18 && Math.abs(totalDy) < 18

                        if (isSwipeInward || isTap) {
                            openPanel()
                            return true
                        }

                        // Drag End: Snap to nearest edge
                        val finalCenterX = event.rawX
                        isLeftEdge = finalCenterX < (screenWidth / 2)
                        lp.x = if (isLeftEdge) 0 else (screenWidth - tabWidth)
                        tabYPosition = lp.y

                        prefs?.edit()?.apply {
                            this.putBoolean(PREF_TAB_IS_LEFT, isLeftEdge)
                            this.putInt(PREF_TAB_Y, tabYPosition)
                            this.apply()
                        }

                        updateTabBackground(isFlashingGreen = false)
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
    // 2. OPEN PANEL & GAME SPACE UI
    // -------------------------------------------------------------------------
    private fun initPanelOverlay() {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.86f).toInt().coerceIn((310 * density).toInt(), (420 * density).toInt())

        // Invisible full-screen root overlay to catch outside taps without dimming
        val overlay = FrameLayout(this).apply {
            this.visibility = View.GONE
        }
        panelRootOverlay = overlay

        // Outside touch dismisser
        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val card = panelCard
                if (card != null) {
                    val location = IntArray(2)
                    card.getLocationOnScreen(location)
                    val cardX = location[0]
                    val cardY = location[1]
                    val cardW = card.width
                    val cardH = card.height
                    val touchX = event.rawX.toInt()
                    val touchY = event.rawY.toInt()

                    // If tap is outside card, close panel
                    if (touchX < cardX || touchX > cardX + cardW || touchY < cardY || touchY > cardY + cardH) {
                        closePanel()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        // Inner Glass Card
        val card = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 20 * density
                this.setColor(Color.parseColor("#EBF00B11")) // Dark navy ~93% opacity
                this.setStroke((1.5f * density).toInt(), Color.parseColor("#8B5CF6"))
            }
            this.background = shape
        }
        panelCard = card

        val cardLp = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = if (isLeftEdge) (Gravity.START or Gravity.CENTER_VERTICAL) else (Gravity.END or Gravity.CENTER_VERTICAL)
            this.setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        overlay.addView(card, cardLp)

        // Build Panel Content inside card
        buildPanelContent(card, density)

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

    private fun buildPanelContent(card: LinearLayout, density: Float) {
        // --- 1. HEADER ROW ---
        val header = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
        }

        // App Logo & Title
        val titleLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = lp
        }
        val iconBadge = TextView(this).apply {
            this.text = "⚡"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.setPadding((4 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        }
        val titleText = TextView(this).apply {
            this.text = "OMNIDROP"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(Color.WHITE)
            this.letterSpacing = 0.08f
        }
        titleLayout.addView(iconBadge)
        titleLayout.addView(titleText)
        header.addView(titleLayout)

        // Live Ping Chip
        val pingChip = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 10 * density
                this.setColor(Color.parseColor("#331E1B4B"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#4C8B5CF6"))
            }
            this.background = shape
        }
        val chipDot = View(this).apply {
            val dotLp = LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                this.rightMargin = (5 * density).toInt()
            }
            this.layoutParams = dotLp
            this.background = createCircleDrawable(Color.parseColor("#10B981"))
        }
        panelChipDot = chipDot
        val chipTv = TextView(this).apply {
            this.text = "— ms"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.setTextColor(Color.parseColor("#E0E7FF"))
        }
        panelChipText = chipTv
        pingChip.addView(chipDot)
        pingChip.addView(chipTv)
        header.addView(pingChip)

        // Speed Display
        val speedTv = TextView(this).apply {
            this.text = "↓ 0 B/s"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            this.typeface = Typeface.MONOSPACE
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        panelSpeedText = speedTv
        header.addView(speedTv)

        // Close X Button
        val closeBtn = TextView(this).apply {
            this.text = "✕"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setPadding((6 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            this.setOnClickListener { closePanel() }
        }
        header.addView(closeBtn)
        card.addView(header)

        // --- 2. LIVE PING GRAPH (Last 30 samples) ---
        val graph = PingGraphView(this).apply {
            val glp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (42 * density).toInt()).apply {
                this.topMargin = (10 * density).toInt()
                this.bottomMargin = (10 * density).toInt()
            }
            this.layoutParams = glp
        }
        pingGraphView = graph
        card.addView(graph)

        // --- 3. LINK & PASTE ROW ---
        val inputContainer = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding((8 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 12 * density
                this.setColor(Color.parseColor("#E6030712"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#374151"))
            }
            this.background = shape
        }

        val edit = EditText(this).apply {
            val elp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = elp
            this.hint = "Paste video link from TikTok, YT, IG..."
            this.setHintTextColor(Color.parseColor("#64748B"))
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.background = null
            this.setSingleLine(true)
            this.ellipsize = TextUtils.TruncateAt.END
        }
        linkEditText = edit
        inputContainer.addView(edit)

        // Paste Button
        val pasteBtn = TextView(this).apply {
            this.text = "Paste"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(Color.WHITE)
            this.setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8 * density
                this.colors = intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#8B5CF6"))
                this.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            this.background = shape
            this.setOnClickListener { handlePasteButtonClick() }
        }
        inputContainer.addView(pasteBtn)

        // Manual Download Button (shown only when autoDownloadEnabled is FALSE)
        val downloadBtn = TextView(this).apply {
            this.text = "Download"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(Color.WHITE)
            this.setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8 * density
                this.colors = intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#059669"))
                this.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            this.background = shape
            this.visibility = if (autoDownloadEnabled) View.GONE else View.VISIBLE
            this.setOnClickListener {
                val url = linkEditText?.text?.toString()?.trim()
                if (!url.isNullOrBlank()) {
                    triggerLinkProcessing(url, isManual = true)
                }
            }
        }
        manualDownloadBtn = downloadBtn
        inputContainer.addView(downloadBtn)

        card.addView(inputContainer)

        // TextWatcher: Auto-detect when text changes or is typed/pasted
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                val supportedUrl = extractSupportedUrl(text)
                if (supportedUrl != null && autoDownloadEnabled && !isDownloading) {
                    triggerLinkProcessing(supportedUrl, isManual = false)
                }
            }
        })

        // --- 4. PROGRESS & STATUS AREA ---
        val progLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.visibility = View.GONE
            val plp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (10 * density).toInt()
            }
            this.layoutParams = plp
            this.setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 10 * density
                this.setColor(Color.parseColor("#80030712"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#1F2937"))
            }
            this.background = shape
        }
        progressContainer = progLayout

        val progHeader = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
        }
        val statusTv = TextView(this).apply {
            this.text = "Starting..."
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.setTextColor(Color.parseColor("#E2E8F0"))
            val slp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = slp
        }
        progressStatusText = statusTv
        val speedProgTv = TextView(this).apply {
            this.text = ""
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.setTextColor(Color.parseColor("#38BDF8"))
        }
        progressSpeedText = speedProgTv
        progHeader.addView(statusTv)
        progHeader.addView(speedProgTv)
        progLayout.addView(progHeader)

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.isIndeterminate = false
            this.max = 100
            this.progress = 0
            val pblp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * density).toInt()).apply {
                this.topMargin = (6 * density).toInt()
                this.bottomMargin = (4 * density).toInt()
            }
            this.layoutParams = pblp
        }
        progressBar = pb
        progLayout.addView(pb)

        // Finished action row (Open, Close)
        val finishedRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.END
            this.visibility = View.GONE
            val flp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (4 * density).toInt()
            }
            this.layoutParams = flp
        }
        finishedActionsLayout = finishedRow

        val openBtn = TextView(this).apply {
            this.text = "Open Video"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(Color.parseColor("#34D399"))
            this.setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            this.setOnClickListener { openFinishedVideo() }
        }
        val dismissFinishedBtn = TextView(this).apply {
            this.text = "Close"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setPadding((10 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            this.setOnClickListener {
                progressContainer?.visibility = View.GONE
                finishedActionsLayout?.visibility = View.GONE
            }
        }
        finishedRow.addView(openBtn)
        finishedRow.addView(dismissFinishedBtn)
        progLayout.addView(finishedRow)

        card.addView(progLayout)

        // --- 5. ROW OF 3 ACTION TILES ---
        val tilesRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = (12 * density).toInt()
            }
            this.layoutParams = rlp
        }

        // Tile 1: Open OmniDrop
        val tileApp = createActionTile(
            title = "Open App",
            subtitle = "OmniDrop",
            density = density,
            onClick = {
                closePanel()
                launchMainActivity(null)
            }
        )
        tilesRow.addView(tileApp)

        // Tile 2: Open Gallery Folder
        val tileGallery = createActionTile(
            title = "Gallery",
            subtitle = "Movies/OmniDrop",
            density = density,
            onClick = {
                closePanel()
                openGalleryFolder()
            }
        )
        tilesRow.addView(tileGallery)

        // Tile 3: Auto-Download Toggle
        val tileAuto = createAutoDownloadTile(density)
        tilesRow.addView(tileAuto)

        card.addView(tilesRow)
    }

    private fun createActionTile(title: String, subtitle: String, density: Float, onClick: () -> Unit): View {
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
                this.cornerRadius = 10 * density
                this.setColor(Color.parseColor("#331E1B4B"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#374151"))
            }
            this.background = shape

            val t1 = TextView(this@FloatingBubbleService).apply {
                this.text = title
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                this.typeface = Typeface.DEFAULT_BOLD
                this.setTextColor(Color.WHITE)
            }
            val t2 = TextView(this@FloatingBubbleService).apply {
                this.text = subtitle
                this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                this.setTextColor(Color.parseColor("#94A3B8"))
                this.setSingleLine(true)
                this.ellipsize = TextUtils.TruncateAt.END
            }
            this.addView(t1)
            this.addView(t2)
            this.setOnClickListener { onClick() }
        }
    }

    private fun createAutoDownloadTile(density: Float): View {
        val root = LinearLayout(this).apply {
            val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            this.layoutParams = tlp
            this.orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER
            this.setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())

            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 10 * density
                this.setColor(Color.parseColor("#331E1B4B"))
                this.setStroke((1 * density).toInt(), Color.parseColor("#374151"))
            }
            this.background = shape
        }
        autoDownloadToggleBg = root

        val t1 = TextView(this).apply {
            this.text = "Auto DL"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(Color.WHITE)
        }
        val t2 = TextView(this).apply {
            this.text = if (autoDownloadEnabled) "ON" else "OFF"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setTextColor(if (autoDownloadEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
        }
        autoDownloadToggleText = t2
        root.addView(t1)
        root.addView(t2)

        root.setOnClickListener {
            autoDownloadEnabled = !autoDownloadEnabled
            prefs?.edit()?.apply {
                this.putBoolean(PREF_AUTO_DOWNLOAD, autoDownloadEnabled)
                this.apply()
            }
            updateAutoDownloadUI()
            Toast.makeText(this, "Auto-Download ${if (autoDownloadEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }
        return root
    }

    private fun updateAutoDownloadUI() {
        autoDownloadToggleText?.text = if (autoDownloadEnabled) "ON" else "OFF"
        autoDownloadToggleText?.setTextColor(
            if (autoDownloadEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        )
        manualDownloadBtn?.visibility = if (autoDownloadEnabled) View.GONE else View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // 3. PANEL ANIMATION & CONTROLS
    // -------------------------------------------------------------------------
    private fun openPanel() {
        if (isPanelOpen) return
        isPanelOpen = true

        val overlay = panelRootOverlay ?: return
        val card = panelCard ?: return

        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f

        // Update alignment depending on left/right tab edge
        val cardLp = card.layoutParams as FrameLayout.LayoutParams
        cardLp.gravity = if (isLeftEdge) (Gravity.START or Gravity.CENTER_VERTICAL) else (Gravity.END or Gravity.CENTER_VERTICAL)
        card.layoutParams = cardLp

        // Slide animation (200ms)
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

        // Start TrafficStats sampling
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastTrafficSampleTime = System.currentTimeMillis()
        mainHandler.post(trafficRunnable)

        // Check clipboard for new supported link upon opening
        checkClipboardOnOpen()
    }

    private fun closePanel() {
        if (!isPanelOpen) return
        val card = panelCard ?: return
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
                        if (autoDownloadEnabled && !isDownloading) {
                            triggerLinkProcessing(supported, isManual = false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Background clipboard read restrictions handled silently
        }
    }

    private fun handlePasteButtonClick() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                linkEditText?.setText(clipText)
                val supported = extractSupportedUrl(clipText)
                if (supported != null) {
                    triggerLinkProcessing(supported, isManual = false)
                } else {
                    Toast.makeText(this, "Link in clipboard is not supported", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Paste failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // 4. AUTO DOWNLOAD & BACKGROUND MEDIASTORE STREAMING
    // -------------------------------------------------------------------------
    private fun triggerLinkProcessing(url: String, isManual: Boolean) {
        val supportedUrl = extractSupportedUrl(url)
        if (supportedUrl == null) {
            Toast.makeText(this, "Unsupported domain. TikTok, YouTube, IG, FB only.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isDownloading) {
            Toast.makeText(this, "Another download is in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        // Deduplication: Ignore if downloaded in last 2 minutes unless manual trigger
        val lastTime = recentDownloads[supportedUrl] ?: 0L
        if (!isManual && (System.currentTimeMillis() - lastTime < 120_000L)) {
            return
        }

        startBackgroundDownload(supportedUrl)
    }

    private fun startBackgroundDownload(videoUrl: String) {
        if (backendUrl.isBlank()) {
            Toast.makeText(this, "Please set Render Backend URL in OmniDrop Settings", Toast.LENGTH_LONG).show()
            progressContainer?.visibility = View.VISIBLE
            progressStatusText?.text = "Render Backend URL missing. Configure in Settings."
            progressBar?.isIndeterminate = false
            progressBar?.progress = 0
            return
        }

        isDownloading = true
        progressContainer?.visibility = View.VISIBLE
        finishedActionsLayout?.visibility = View.GONE
        progressBar?.isIndeterminate = true
        progressStatusText?.text = "Waking up server & preparing video..."
        progressSpeedText?.text = ""

        backgroundExecutor.execute {
            var targetUri: Uri? = null
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            var output: OutputStream? = null

            try {
                // Step 1: Health check & wake up retry (up to 15 attempts)
                var isReady = false
                val healthUrl = "${backendUrl.removeSuffix("/")}/health"

                for (attempt in 1..15) {
                    mainHandler.post {
                        progressStatusText?.text = if (attempt > 1) "Waking server... ($attempt/15)" else "Connecting to backend..."
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
                    } catch (e: Exception) {
                        // Sleeping, wait and retry
                    }
                    Thread.sleep(2000L)
                }

                if (!isReady) {
                    mainHandler.post {
                        progressStatusText?.text = "Backend unreachable. Check Settings."
                        progressBar?.isIndeterminate = false
                        isDownloading = false
                    }
                    return@execute
                }

                // Step 2: Request Download
                mainHandler.post {
                    progressStatusText?.text = "Extracting clean stream (no watermark)..."
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

                val payload = JSONObject().apply {
                    this.put("url", videoUrl)
                    this.put("quality", defaultQuality)
                }
                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val respCode = conn.responseCode
                if (respCode != HttpURLConnection.HTTP_OK) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $respCode"
                    mainHandler.post {
                        progressStatusText?.text = "Download failed: $err"
                        progressBar?.isIndeterminate = false
                        isDownloading = false
                    }
                    return@execute
                }

                // Step 3: Stream straight to MediaStore (Movies/OmniDrop or Music/OmniDrop)
                val isAudio = defaultQuality.equals("audio", ignoreCase = true)
                val ext = if (isAudio) ".mp3" else ".mp4"
                val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
                val timestamp = System.currentTimeMillis()
                val fileName = "OmniDrop_${timestamp}$ext"

                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    this.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    this.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
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
                        progressStatusText?.text = "Failed to create gallery storage file."
                        isDownloading = false
                    }
                    return@execute
                }

                output = resolver.openOutputStream(targetUri)
                input = conn.inputStream
                val totalBytes = conn.contentLengthLong

                mainHandler.post {
                    progressBar?.isIndeterminate = totalBytes <= 0
                }

                var bytesCopied: Long = 0
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastProgressUpdate = System.currentTimeMillis()
                var bytesSinceLastSample: Long = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output?.write(buffer, 0, bytesRead)
                    bytesCopied += bytesRead
                    bytesSinceLastSample += bytesRead

                    val now = System.currentTimeMillis()
                    val diff = now - lastProgressUpdate
                    if (diff >= 300) {
                        val speedMbPerSec = (bytesSinceLastSample.toDouble() / (1024 * 1024)) / (diff / 1000.0)
                        val totalMb = String.format("%.1f", bytesCopied / (1024.0 * 1024.0))
                        val pct = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt().coerceIn(1, 99) else 0

                        lastProgressUpdate = now
                        bytesSinceLastSample = 0

                        mainHandler.post {
                            if (totalBytes > 0) {
                                progressBar?.progress = pct
                                progressStatusText?.text = "Saving: $totalMb MB ($pct%)"
                            } else {
                                progressStatusText?.text = "Saving: $totalMb MB..."
                            }
                            progressSpeedText?.text = String.format("%.1f MB/s", speedMbPerSec)
                        }
                    }
                }
                output?.flush()

                // Mark file pending = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = ContentValues().apply {
                        this.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(targetUri, updateValues, null, null)
                }

                recentDownloads[videoUrl] = System.currentTimeMillis()
                lastFinishedFileUri = targetUri.toString()
                lastFinishedFileName = fileName

                // Show Finished Notification
                NotificationHelper.showDownloadFinishedNotification(
                    this,
                    "Saved to Gallery",
                    "$fileName saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}",
                    targetUri.toString()
                )

                // Flash Edge Tab Green and update Panel
                mainHandler.post {
                    flashTabGreen()
                    isDownloading = false
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = 100
                    progressStatusText?.text = "Saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}"
                    progressSpeedText?.text = "Done"
                    finishedActionsLayout?.visibility = View.VISIBLE
                    linkEditText?.setText("")
                }

            } catch (e: Exception) {
                if (targetUri != null) {
                    try { contentResolver.delete(targetUri, null, null) } catch (_: Exception) {}
                }
                mainHandler.post {
                    progressStatusText?.text = "Error: ${e.message ?: "Failed to download"}"
                    progressBar?.isIndeterminate = false
                    isDownloading = false
                }
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }
    }

    private fun flashTabGreen() {
        updateTabBackground(isFlashingGreen = true)
        mainHandler.postDelayed({
            updateTabBackground(isFlashingGreen = false)
        }, 1800L)
    }

    private fun openFinishedVideo() {
        val uriStr = lastFinishedFileUri
        if (!uriStr.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.setDataAndType(Uri.parse(uriStr), "video/*")
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No video player installed", Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) {
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
    // 5. LIVE PING & TRAFFIC SPEED MONITORING
    // -------------------------------------------------------------------------
    private fun measurePing() {
        if (backendUrl.isBlank()) {
            updatePingUI(-1, "—", Color.parseColor("#94A3B8"))
            return
        }

        backgroundExecutor.execute {
            val endpoint = "${backendUrl.removeSuffix("/")}/health"
            val startTime = System.currentTimeMillis()
            var ping = -1
            var statusStr = "—"
            var dotColor = Color.parseColor("#EF4444") // Red

            try {
                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    this.requestMethod = "GET"
                    this.connectTimeout = 3000
                    this.readTimeout = 3000
                    if (apiKey.isNotBlank()) this.setRequestProperty("x-api-key", apiKey)
                }

                val code = conn.responseCode
                val elapsed = (System.currentTimeMillis() - startTime).toInt()
                conn.disconnect()

                if (code == HttpURLConnection.HTTP_OK) {
                    ping = elapsed
                    if (elapsed < 400) {
                        dotColor = Color.parseColor("#10B981") // Green (<400ms)
                        statusStr = "${elapsed}ms"
                    } else if (elapsed < 1500) {
                        dotColor = Color.parseColor("#F59E0B") // Yellow (<1500ms)
                        statusStr = "${elapsed}ms"
                    } else {
                        dotColor = Color.parseColor("#EF4444") // Red (>=1500ms)
                        statusStr = "${elapsed}ms"
                    }
                } else {
                    dotColor = Color.parseColor("#EF4444")
                    statusStr = "Sleeping"
                }
            } catch (e: Exception) {
                val elapsed = (System.currentTimeMillis() - startTime).toInt()
                if (elapsed >= 3000) {
                    statusStr = "Sleeping"
                } else {
                    statusStr = "Offline"
                }
                dotColor = Color.parseColor("#EF4444")
            }

            mainHandler.post {
                updatePingUI(ping, statusStr, dotColor)
            }
        }
    }

    private fun updatePingUI(pingMs: Int, displayStr: String, dotColor: Int) {
        currentPingMs = pingMs

        // Update Tab
        tabPingText?.text = if (pingMs > 0) "$pingMs" else displayStr.take(3)
        tabDotView?.background = createCircleDrawable(dotColor)

        // Update Panel Chip
        panelChipText?.text = if (pingMs > 0) "$pingMs ms" else displayStr
        panelChipDot?.background = createCircleDrawable(dotColor)

        // Update Line Graph
        val sampleValue = if (pingMs > 0) pingMs else 2500
        pingGraphView?.addSample(sampleValue)
    }

    private fun sampleTrafficStats() {
        val now = System.currentTimeMillis()
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()

        val timeDiff = now - lastTrafficSampleTime
        if (timeDiff in 500..2000 && lastRxBytes > 0) {
            val rxBytesDiff = (currentRx - lastRxBytes).coerceAtLeast(0)
            val txBytesDiff = (currentTx - lastTxBytes).coerceAtLeast(0)

            val rxSpeed = rxBytesDiff.toDouble() / (timeDiff / 1000.0)
            val txSpeed = txBytesDiff.toDouble() / (timeDiff / 1000.0)

            val rxFmt = formatBytesSpeed(rxSpeed)
            val txFmt = formatBytesSpeed(txSpeed)
            panelSpeedText?.text = "↓ $rxFmt  ↑ $txFmt"
        }

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTrafficSampleTime = now
    }

    private fun formatBytesSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= (1024 * 1024) -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
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
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacks(trafficRunnable)

        tabRootView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        panelRootOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        backgroundExecutor.shutdownNow()
    }

    // -------------------------------------------------------------------------
    // 6. CUSTOM PING GRAPH VIEW (30 Samples with Glowing Fill)
    // -------------------------------------------------------------------------
    class PingGraphView(context: Context) : View(context) {
        private val samples = ArrayList<Int>(30)
        private val maxSamples = 30

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.strokeWidth = 3f * context.resources.displayMetrics.density
            this.strokeCap = Paint.Cap.ROUND
            this.strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            this.color = Color.parseColor("#26334155")
            this.strokeWidth = 1f
        }

        private val linePath = Path()
        private val fillPath = Path()

        init {
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 8f * context.resources.displayMetrics.density
                this.setColor(Color.parseColor("#4D030712"))
                this.setStroke((1 * context.resources.displayMetrics.density).toInt(), Color.parseColor("#1E293B"))
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

            // Subtle horizontal grid lines
            canvas.drawLine(0f, h * 0.33f, w, h * 0.33f, gridPaint)
            canvas.drawLine(0f, h * 0.66f, w, h * 0.66f, gridPaint)

            if (samples.size < 2) return

            linePath.reset()
            fillPath.reset()

            val stepX = w / (maxSamples - 1).toFloat()
            val maxPing = 1200f // Scale reference
            val minPing = 20f

            val startIndex = maxSamples - samples.size
            var firstX = 0f
            var firstY = 0f

            for (i in samples.indices) {
                val ping = samples[i].toFloat().coerceIn(minPing, maxPing)
                // Invert Y: 0ms is bottom, 1200ms is top
                val norm = 1f - ((ping - minPing) / (maxPing - minPing))
                val x = (startIndex + i) * stepX
                val y = (norm * (h * 0.7f)) + (h * 0.15f)

                if (i == 0) {
                    firstX = x
                    firstY = y
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

            // Glowing fill gradient (purple glow to transparent)
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#558B5CF6"),
                Color.parseColor("#0506B6D4"),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            // Neon cyan to purple line gradient
            linePaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                Color.parseColor("#06B6D4"),
                Color.parseColor("#A855F7"),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(linePath, linePaint)
        }
    }
}

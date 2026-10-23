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
import android.graphics.drawable.LayerDrawable
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
        const val PREF_HISTORY_KEY = "native_download_history"

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
    private var trafficSpeedText: TextView? = null
    private var pingGraphView: PingGraphView? = null
    private var linkEditText: EditText? = null
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
    private var backendUrl: String = ""
    private var apiKey: String = ""
    private var defaultQuality: String = "best"
    private var lastFinishedFileUri: String? = null

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

    // Threading
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newFixedThreadPool(3)

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
            measureDualPing()
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
                this.description = "Keeps the OmniDrop hardware edge download tool active"
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

        val root = FrameLayout(this)
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

        // --- 2. DUAL STATUS INSTRUMENT PANEL (SERVER & NET & TRAFFIC) ---
        val statusBoard = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
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

        // SERVER Reading
        val serverLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val slp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = slp
        }
        val sLed = View(this).apply {
            val dlp = LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                this.rightMargin = (5 * density).toInt()
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
        }
        serverStatusText = sVal
        serverLayout.addView(sLed)
        serverLayout.addView(sLabel)
        serverLayout.addView(sVal)
        statusBoard.addView(serverLayout)

        // NET Reading
        val netLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val nlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = nlp
        }
        val nLed = View(this).apply {
            val dlp = LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                this.rightMargin = (5 * density).toInt()
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
        }
        netStatusText = nVal
        netLayout.addView(nLed)
        netLayout.addView(nLabel)
        netLayout.addView(nVal)
        statusBoard.addView(netLayout)

        // Speeds (UP/DN without Unicode arrows)
        val speedTv = TextView(this).apply {
            this.text = "DN 0 B/s"
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.setTextColor(Color.parseColor("#38BDF8"))
        }
        trafficSpeedText = speedTv
        statusBoard.addView(speedTv)

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

        // --- 4. INPUT & MECHANICAL PASTE ---
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
            val elp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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

        val pasteBtn = createMechanicalButton(
            text = "PASTE",
            density = density,
            isAccent = true,
            onClick = { handlePasteButtonClick() }
        )
        inputContainer.addView(pasteBtn)

        val dlBtn = createMechanicalButton(
            text = "DOWNLOAD",
            density = density,
            isGreen = true,
            onClick = {
                val url = linkEditText?.text?.toString()?.trim()
                if (!url.isNullOrBlank()) {
                    triggerLinkProcessing(url, isManual = true)
                }
            }
        ).apply {
            this.visibility = if (autoDownloadEnabled) View.GONE else View.VISIBLE
        }
        manualDownloadBtn = dlBtn
        inputContainer.addView(dlBtn)

        content.addView(inputContainer)

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
        } catch (_: Exception) {}
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
                    Toast.makeText(this, "Clipboard URL not in supported platforms", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Paste failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // 4. AUTO-DOWNLOAD & MEDIASTORE PIPELINE
    // -------------------------------------------------------------------------
    private fun triggerLinkProcessing(url: String, isManual: Boolean, force: Boolean = false) {
        val supportedUrl = extractSupportedUrl(url)
        if (supportedUrl == null) {
            if (isManual) {
                Toast.makeText(this, "Supported: TikTok, YouTube, Instagram, Facebook", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val normUrl = VideoDownloaderPlugin.normalizeUrl(supportedUrl)

        if (VideoDownloaderPlugin.inFlightDownloads.containsKey(normUrl) || isDownloading) {
            if (isManual) {
                Toast.makeText(this, "Download already in progress...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Duplicate check before downloading: check if already downloaded & file still exists
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

    private fun showAlreadyDownloadedUI(dup: JSONObject, normUrl: String) {
        val density = resources.displayMetrics.density
        val title = dup.optString("title", "Video")
        val fileUri = dup.optString("fileUri", "")

        progressContainer?.visibility = View.VISIBLE
        progressStatusText?.text = "Already downloaded: $title"
        progressSpeedText?.text = "SAVED"
        segmentedProgressBar?.setProgress(100)

        val row = finishedActionsLayout ?: return
        row.removeAllViews()
        row.visibility = View.VISIBLE

        val playBtn = createMechanicalButton(
            text = "PLAY",
            density = density,
            isGreen = true,
            onClick = {
                if (fileUri.isNotBlank()) {
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

        row.addView(playBtn)
        row.addView(dlAgainBtn)
        row.addView(closeBtn)
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
        progressContainer?.visibility = View.VISIBLE
        finishedActionsLayout?.visibility = View.GONE
        segmentedProgressBar?.setProgress(0)
        progressStatusText?.text = "Connecting to server..."
        progressSpeedText?.text = ""

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

                if (!isReady) {
                    mainHandler.post {
                        progressStatusText?.text = "Backend unreachable."
                        isDownloading = false
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

                val cleanPlatform = VideoDownloaderPlugin.cleanPlatformName(platform, normalizedUrl)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
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
                    mainHandler.post {
                        progressStatusText?.text = "Download failed: $err"
                        isDownloading = false
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
                        isDownloading = false
                    }
                    return@execute
                }

                output = resolver.openOutputStream(targetUri)
                input = conn.inputStream
                val totalBytes = conn.contentLengthLong

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
                        val totalMb = String.format(java.util.Locale.US, "%.1f", bytesCopied / (1024.0 * 1024.0))
                        val pct = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt().coerceIn(1, 99) else 50

                        lastProgressUpdate = now
                        bytesSinceLastSample = 0

                        mainHandler.post {
                            segmentedProgressBar?.setProgress(pct)
                            progressStatusText?.text = "$totalMb MB ($pct%)"
                            progressSpeedText?.text = String.format(java.util.Locale.US, "DN %.1f MB/s", speedMbPerSec)
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

                // Save to metadata store (keyed by fileName)
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

                // Save to native history
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

                // Finished notification
                NotificationHelper.showDownloadFinishedNotification(
                    this,
                    "Saved to Gallery",
                    "$fileName saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}",
                    targetUri.toString()
                )

                mainHandler.post {
                    tabInnerFrame?.flashGreen()
                    isDownloading = false
                    segmentedProgressBar?.setProgress(100)
                    progressStatusText?.text = "Saved to ${if (isAudio) "Music/OmniDrop" else "Movies/OmniDrop"}"
                    progressSpeedText?.text = "READY"

                    // Reset finished buttons
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

                    linkEditText?.setText("")
                    refreshRecentDownloadsUI()
                }

            } catch (e: Exception) {
                if (targetUri != null) {
                    try { contentResolver.delete(targetUri, null, null) } catch (_: Exception) {}
                }
                mainHandler.post {
                    progressStatusText?.text = "Error: ${e.message ?: "Failed"}"
                    isDownloading = false
                }
            } finally {
                VideoDownloaderPlugin.inFlightDownloads.remove(normalizedUrl)
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }
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

    private fun updateInstrumentUI() {
        // 1. Update Edge Tab
        tabInnerFrame?.updateStatus(serverState, serverPingMs)

        // 2. Update Server Instrument
        when (serverState) {
            "ONLINE" -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#10B981"))
                serverStatusText?.text = "${serverPingMs}ms"
                serverStatusText?.setTextColor(Color.parseColor("#00F0FF"))
            }
            "SLEEP" -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#F59E0B"))
                serverStatusText?.text = "SLEEP"
                serverStatusText?.setTextColor(Color.parseColor("#F59E0B"))
            }
            else -> {
                serverLedDot?.background = createCircleDrawable(Color.parseColor("#EF4444"))
                serverStatusText?.text = "OFF"
                serverStatusText?.setTextColor(Color.parseColor("#EF4444"))
            }
        }

        // 3. Update Net Instrument
        if (netPingMs > 0) {
            val netColor = if (netPingMs < 300) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
            netLedDot?.background = createCircleDrawable(netColor)
            netStatusText?.text = "${netPingMs}ms"
            netStatusText?.setTextColor(Color.parseColor("#00F0FF"))
        } else {
            netLedDot?.background = createCircleDrawable(Color.parseColor("#EF4444"))
            netStatusText?.text = "OFF"
            netStatusText?.setTextColor(Color.parseColor("#EF4444"))
        }

        // 4. Update Ping Graph with active reading
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

            val rxFmt = formatBytesSpeed(rxSpeed)
            val txFmt = formatBytesSpeed(txSpeed)
            trafficSpeedText?.text = "DN $rxFmt  UP $txFmt"
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
        mainHandler.removeCallbacks(dualPingRunnable)
        mainHandler.removeCallbacks(trafficRunnable)

        tabRootView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        panelRootOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        backgroundExecutor.shutdownNow()
    }

    // -------------------------------------------------------------------------
    // 6. HARDWARE MACHINE CUSTOM VIEWS
    // -------------------------------------------------------------------------

    /**
     * Machined Metal Latch Handle for the Edge Tab:
     * - Dark gunmetal with bevel highlights
     * - Status LED dot
     * - Digital segment ms number or "ZZ" or "OFF"
     */
    class EdgeLatchTabView(context: Context, private var isLeft: Boolean) : FrameLayout(context) {
        private var state: String = "OFF"
        private var pingMs: Int = -1
        private var isFlashingGreen: Boolean = false

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
            postInvalidate()
        }

        fun flashGreen() {
            isFlashingGreen = true
            postInvalidate()
            postDelayed({
                isFlashingGreen = false
                postInvalidate()
            }, 1800L)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = context.resources.displayMetrics.density
            val r = 16f * density

            // 1. Body Gradient
            if (isFlashingGreen) {
                bodyPaint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#065F46"), Color.parseColor("#047857"), Shader.TileMode.CLAMP)
            } else {
                bodyPaint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#101522"), Color.parseColor("#192231"), Shader.TileMode.CLAMP)
            }

            val path = Path()
            val radii = if (isLeft) {
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            path.addRoundRect(RectF(0f, 0f, w, h), radii, Path.Direction.CW)
            canvas.drawPath(path, bodyPaint)

            // 2. Bevel & Specular Highlight
            canvas.drawPath(path, if (isLeft) bevelLightPaint else bevelDarkPaint)
            canvas.drawLine(w * 0.5f, h * 0.15f, w * 0.5f, h * 0.85f, highlightLinePaint)

            // 3. Status LED Dot
            val ledColor = when {
                isFlashingGreen -> Color.parseColor("#34D399")
                state == "ONLINE" -> Color.parseColor("#10B981")
                state == "SLEEP" -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#EF4444")
            }
            ledPaint.color = ledColor
            canvas.drawCircle(w * 0.5f, 15f * density, 3.5f * density, ledPaint)

            // 4. Digital Text
            val textY = 44f * density
            when (state) {
                "ONLINE" -> {
                    textPaint.color = Color.parseColor("#00F0FF")
                    val display = if (pingMs > 0) "$pingMs" else "—"
                    canvas.drawText(display, w * 0.5f, textY, textPaint)
                    canvas.drawText("ms", w * 0.5f, textY + (12f * density), subTextPaint)
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

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = context.resources.displayMetrics.density
            val r = 18f * density

            // 1. Opaque Dark Gunmetal Body Gradient (BUG FIX: NEVER translucent, NEVER red)
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

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = context.resources.displayMetrics.density
            val gap = 3f * density
            val segmentW = (w - (gap * (totalSegments - 1))) / totalSegments.toFloat()
            val activeCount = ((progressPercent / 100f) * totalSegments).toInt()

            activePaint.color = if (progressPercent >= 100) Color.parseColor("#10B981") else Color.parseColor("#00F0FF")

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

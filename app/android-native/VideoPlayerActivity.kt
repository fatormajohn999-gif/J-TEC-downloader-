package com.omnidrop.app.plugin

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Modern In-App Hardware Video Player.
 * Built with pure Android SDK (VideoView + Canvas/Drawables).
 * Features:
 * - Playlist support: seamlessly browse filtered library clips.
 * - Swipe left/right gestures for next/previous video with smooth slide animations.
 * - Flash HUD banner ("X / Y - Title") and thumbnail placeholder during loading.
 * - Single-time "SWIPE FOR NEXT" onboarding hint.
 * - Autoplay & Repeat modes: OFF, ONE, ALL with cycling mechanical button & LED indicator.
 * - Unobstructed central picture with auto-hiding dark robotic HUD controls.
 * - Target time tooltip above seekbar during drag.
 * - Double-tap left/right 10s skip ripple indicators.
 * - Auto-rotation from video aspect ratio + manual orientation lock.
 * - Persistent per-video playback resumption saved in SharedPreferences.
 */
class VideoPlayerActivity : Activity() {

    companion object {
        private const val PREFS_PLAYBACK = "omnidrop_playback_positions"
        private const val PREFS_PLAYER_UI = "omnidrop_player_prefs"
        private const val KEY_SWIPE_HINT_SHOWN = "swipe_hint_shown"
    }

    data class PlaylistItem(
        val uri: String,
        val title: String,
        val thumbnail: String? = null,
        val platform: String? = null
    )

    private val playlist = ArrayList<PlaylistItem>()
    private var currentIndex = 0

    private lateinit var rootContainer: FrameLayout
    private lateinit var videoContentContainer: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var thumbnailImageView: ImageView
    private lateinit var hudOverlay: FrameLayout
    private lateinit var topBarLayout: LinearLayout
    private lateinit var bottomBarLayout: LinearLayout

    private lateinit var titleTextView: TextView
    private lateinit var playlistCounterTextView: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var skipBackwardButton: TextView
    private lateinit var skipForwardButton: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var seekTooltipTextView: TextView
    private lateinit var speedButton: TextView
    private lateinit var rotateButton: TextView
    private lateinit var repeatButton: LinearLayout
    private lateinit var repeatLabelTextView: TextView
    private lateinit var repeatLedView: View

    // Feedback overlays for gestures & cues
    private lateinit var leftRippleView: TextView
    private lateinit var rightRippleView: TextView
    private lateinit var centerFlashView: TextView
    private lateinit var titleFlashBannerView: TextView
    private lateinit var swipeHintOverlay: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var isControlsVisible = true
    private var isUserTrackingSeek = false
    private var isOrientationLockedByUser = false
    private var currentSpeedIndex = 1 // 1.0x by default
    private val speedOptions = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.5x", "2.0x")

    // Repeat modes: "off", "one", "all"
    private var currentRepeatMode = "off"
    private var autoPlayNext = true

    private var currentVideoUri: Uri? = null
    private var currentVideoTitle: String = "OmniDrop Video"
    private lateinit var gestureDetector: GestureDetector

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val hideTitleFlashRunnable = Runnable {
        titleFlashBannerView.animate().alpha(0f).setDuration(300).withEndAction {
            titleFlashBannerView.visibility = View.GONE
        }.start()
    }

    private val hideSwipeHintRunnable = Runnable {
        dismissSwipeHint()
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isUserTrackingSeek && videoView.isPlaying) {
                val current = videoView.currentPosition
                val total = videoView.duration
                if (total > 0) {
                    seekBar.max = total
                    seekBar.progress = current
                    currentTimeTextView.text = formatTime(current)
                    totalTimeTextView.text = formatTime(total)
                }
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        parseIntentData()
        loadNativeSettings()

        if (savedInstanceState != null) {
            currentSpeedIndex = savedInstanceState.getInt("saved_speed", 1)
            isOrientationLockedByUser = savedInstanceState.getBoolean("orient_locked", false)
            currentIndex = savedInstanceState.getInt("saved_index", currentIndex)
            currentRepeatMode = savedInstanceState.getString("saved_repeat", currentRepeatMode) ?: "off"
        }

        buildPlayerUI()
        setupGestures()
        loadVideoAtCurrentIndex(animate = false, slideLeft = true)
        checkAndShowSwipeHint()
    }

    private fun parseIntentData() {
        val initialUri = intent.data ?: intent.getStringExtra("video_uri")?.let { Uri.parse(it) }
        val initialTitle = intent.getStringExtra("video_title") ?: "OmniDrop Video"
        val playlistJson = intent.getStringExtra("playlist_json") ?: ""
        val targetIndex = intent.getIntExtra("current_index", 0)

        playlist.clear()
        if (playlistJson.isNotBlank()) {
            try {
                val array = JSONArray(playlistJson)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val uri = item.optString("uri", "")
                    val title = item.optString("title", "Video ${i + 1}")
                    val thumb = item.optString("thumbnail", "")
                    val platform = item.optString("platform", "Other")
                    if (uri.isNotBlank()) {
                        playlist.add(PlaylistItem(uri, title, thumb, platform))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (playlist.isEmpty() && initialUri != null) {
            playlist.add(PlaylistItem(initialUri.toString(), initialTitle))
        }

        currentIndex = if (playlist.isNotEmpty()) {
            targetIndex.coerceIn(0, playlist.size - 1)
        } else {
            0
        }
    }

    private fun loadNativeSettings() {
        val prefs = getSharedPreferences(VideoDownloaderPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        autoPlayNext = prefs.getBoolean("auto_play_next", true)
        currentRepeatMode = prefs.getString("repeat_mode", "off") ?: "off"
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun buildPlayerUI() {
        rootContainer = FrameLayout(this).apply {
            this.setBackgroundColor(Color.BLACK)
        }

        // 1. Video Container (VideoView + Thumbnail placeholder for smooth slide animations)
        videoContentContainer = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }

        videoView = VideoView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }
        videoContentContainer.addView(videoView)

        thumbnailImageView = ImageView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
            this.scaleType = ImageView.ScaleType.FIT_CENTER
            this.visibility = View.GONE
        }
        videoContentContainer.addView(thumbnailImageView)

        rootContainer.addView(videoContentContainer)

        // 2. Gesture touch overlay
        val touchOverlay = View(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(touchOverlay)

        // 3. Gesture feedback indicators (ripples + center flash + title flash + swipe hint)
        buildGestureOverlays()

        // 4. Modern HUD Overlay (Top bar + Bottom bar)
        hudOverlay = FrameLayout(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        buildTopBar()
        buildBottomBar()

        rootContainer.addView(hudOverlay)
        setContentView(rootContainer)

        scheduleControlsAutoHide()
    }

    private fun buildGestureOverlays() {
        val pillBg = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(20).toFloat()
            this.setColor(Color.parseColor("#CC0B0E14"))
            this.setStroke(dp(1), Color.parseColor("#00F0FF"))
        }

        // Left -10s ripple
        leftRippleView = TextView(this).apply {
            this.text = "⟲ -10s"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(18), dp(10), dp(18), dp(10))
            this.background = pillBg
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                this.leftMargin = dp(40)
            }
            this.layoutParams = lp
        }
        rootContainer.addView(leftRippleView)

        // Right +10s ripple
        val pillBgRight = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(20).toFloat()
            this.setColor(Color.parseColor("#CC0B0E14"))
            this.setStroke(dp(1), Color.parseColor("#00F0FF"))
        }
        rightRippleView = TextView(this).apply {
            this.text = "+10s ⟳"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(18), dp(10), dp(18), dp(10))
            this.background = pillBgRight
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                this.rightMargin = dp(40)
            }
            this.layoutParams = lp
        }
        rootContainer.addView(rightRippleView)

        // Center flash indicator (play/pause tap feedback)
        val centerBg = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            this.setColor(Color.parseColor("#B3000000"))
            this.setStroke(dp(2), Color.parseColor("#00F0FF"))
        }
        centerFlashView = TextView(this).apply {
            this.text = "❚❚"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            this.background = centerBg
            this.visibility = View.GONE
            val size = dp(64)
            val lp = FrameLayout.LayoutParams(size, size).apply {
                this.gravity = Gravity.CENTER
            }
            this.layoutParams = lp
        }
        rootContainer.addView(centerFlashView)

        // Title Flash Banner (smooth HUD cue on video transition: "2 / 14 - <title>")
        titleFlashBannerView = TextView(this).apply {
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(16), dp(8), dp(16), dp(8))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(8).toFloat()
                this.setColor(Color.parseColor("#E6080B10"))
                this.setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                this.topMargin = dp(76)
            }
            this.layoutParams = lp
        }
        rootContainer.addView(titleFlashBannerView)

        // First-Time "SWIPE FOR NEXT" Onboarding Hint
        swipeHintOverlay = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(14), dp(8), dp(14), dp(8))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(20).toFloat()
                this.setColor(Color.parseColor("#DD080B10"))
                this.setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            this.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                this.bottomMargin = dp(120)
            }
            this.layoutParams = lp
        }

        val hintIcon = TextView(this).apply {
            this.text = "⇄"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            this.typeface = Typeface.DEFAULT_BOLD
            val iconLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(8)
            }
            this.layoutParams = iconLp
        }
        swipeHintOverlay.addView(hintIcon)

        val hintText = TextView(this).apply {
            this.text = "SWIPE FOR NEXT // PREV"
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        swipeHintOverlay.addView(hintText)

        rootContainer.addView(swipeHintOverlay)
    }

    private fun checkAndShowSwipeHint() {
        val prefs = getSharedPreferences(PREFS_PLAYER_UI, Context.MODE_PRIVATE)
        val shown = prefs.getBoolean(KEY_SWIPE_HINT_SHOWN, false)
        if (!shown && playlist.size > 1) {
            swipeHintOverlay.visibility = View.VISIBLE
            swipeHintOverlay.alpha = 0f
            swipeHintOverlay.animate().alpha(1f).setDuration(400).start()
            mainHandler.postDelayed(hideSwipeHintRunnable, 4000)
        }
    }

    private fun dismissSwipeHint() {
        mainHandler.removeCallbacks(hideSwipeHintRunnable)
        if (swipeHintOverlay.visibility == View.VISIBLE) {
            swipeHintOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                swipeHintOverlay.visibility = View.GONE
            }.start()
            getSharedPreferences(PREFS_PLAYER_UI, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    this.putBoolean(KEY_SWIPE_HINT_SHOWN, true)
                    this.apply()
                }
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = rootContainer.width
                if (e.x < screenWidth / 2) {
                    // Double tap left: skip back 10s
                    val target = (videoView.currentPosition - 10000).coerceAtLeast(0)
                    videoView.seekTo(target)
                    flashRippleIndicator(leftRippleView)
                } else {
                    // Double tap right: skip forward 10s
                    val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                    videoView.seekTo(target)
                    flashRippleIndicator(rightRippleView)
                }
                resetAutoHideTimer()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > dp(50) && Math.abs(velocityX) > 350) {
                    dismissSwipeHint()
                    if (diffX < 0) {
                        // Swiped Left -> Play Next Video
                        playNextVideo(userInitiated = true)
                        return true
                    } else {
                        // Swiped Right -> Play Previous Video
                        playPreviousVideo(userInitiated = true)
                        return true
                    }
                }
                return false
            }
        })

        rootContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun flashRippleIndicator(view: TextView) {
        view.visibility = View.VISIBLE
        val anim = AlphaAnimation(1f, 0f).apply {
            this.duration = 600
            this.fillAfter = false
        }
        view.startAnimation(anim)
        mainHandler.postDelayed({ view.visibility = View.GONE }, 600)
    }

    private fun flashCenterIcon(symbol: String) {
        centerFlashView.text = symbol
        centerFlashView.visibility = View.VISIBLE
        val anim = AlphaAnimation(1f, 0f).apply {
            this.duration = 500
            this.fillAfter = false
        }
        centerFlashView.startAnimation(anim)
        mainHandler.postDelayed({ centerFlashView.visibility = View.GONE }, 500)
    }

    private fun flashTitleBanner(text: String) {
        mainHandler.removeCallbacks(hideTitleFlashRunnable)
        titleFlashBannerView.text = text
        titleFlashBannerView.visibility = View.VISIBLE
        titleFlashBannerView.alpha = 1f
        mainHandler.postDelayed(hideTitleFlashRunnable, 1800)
    }

    private fun buildTopBar() {
        topBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(16), dp(16), dp(16), dp(20))
            this.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E6080B10"), Color.TRANSPARENT)
            )
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.TOP
            }
            this.layoutParams = lp
        }

        // Back button
        val backBtn = TextView(this).apply {
            this.text = "‹ BACK"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(12), dp(6), dp(12), dp(6))
            this.background = createMechanicalButtonBackground(false)
            this.setOnClickListener {
                finish()
            }
        }
        topBarLayout.addView(backBtn)

        // Status LED
        val statusLed = View(this).apply {
            val ledLp = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                this.leftMargin = dp(12)
                this.rightMargin = dp(8)
            }
            this.layoutParams = ledLp
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.OVAL
                this.setColor(Color.parseColor("#00F0FF"))
            }
        }
        topBarLayout.addView(statusLed)

        // Video Title
        titleTextView = TextView(this).apply {
            this.text = currentVideoTitle
            this.setTextColor(Color.WHITE)
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.setSingleLine(true)
            this.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.layoutParams = titleLp
        }
        topBarLayout.addView(titleTextView)

        // Playlist Position Badge (e.g. "1 / 8")
        playlistCounterTextView = TextView(this).apply {
            this.text = if (playlist.isNotEmpty()) "${currentIndex + 1}/${playlist.size}" else "1/1"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.setPadding(dp(8), dp(4), dp(8), dp(4))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(4).toFloat()
                this.setColor(Color.parseColor("#2200F0FF"))
                this.setStroke(dp(1), Color.parseColor("#3300F0FF"))
            }
            val counterLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.leftMargin = dp(8)
            }
            this.layoutParams = counterLp
        }
        topBarLayout.addView(playlistCounterTextView)

        hudOverlay.addView(topBarLayout)
    }

    private fun buildBottomBar() {
        bottomBarLayout = LinearLayout(this).apply {
            this.orientation = LinearLayout.VERTICAL
            this.setPadding(dp(16), dp(14), dp(16), dp(18))
            this.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#F5080B10"), Color.TRANSPARENT)
            )
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.BOTTOM
            }
            this.layoutParams = lp
        }

        // Target time tooltip above seekbar
        seekTooltipTextView = TextView(this).apply {
            this.text = "00:00"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(8), dp(3), dp(8), dp(3))
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                this.cornerRadius = dp(4).toFloat()
                this.setColor(Color.parseColor("#DD0B0E14"))
                this.setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
            this.visibility = View.INVISIBLE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.CENTER_HORIZONTAL
                this.bottomMargin = dp(2)
            }
            this.layoutParams = lp
        }
        bottomBarLayout.addView(seekTooltipTextView)

        // SeekBar Row
        val seekRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        currentTimeTextView = TextView(this).apply {
            this.text = "00:00"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
        }
        seekRow.addView(currentTimeTextView)

        seekBar = SeekBar(this).apply {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                this.leftMargin = dp(8)
                this.rightMargin = dp(8)
            }
            this.layoutParams = lp
            this.progressTintList = ColorStateList.valueOf(Color.parseColor("#00F0FF"))
            this.thumbTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            this.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val formatted = formatTime(progress)
                        currentTimeTextView.text = formatted
                        seekTooltipTextView.text = formatted
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserTrackingSeek = true
                    seekTooltipTextView.visibility = View.VISIBLE
                    resetAutoHideTimer()
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserTrackingSeek = false
                    seekTooltipTextView.visibility = View.INVISIBLE
                    sb?.let {
                        videoView.seekTo(it.progress)
                    }
                    resetAutoHideTimer()
                }
            })
        }
        seekRow.addView(seekBar)

        totalTimeTextView = TextView(this).apply {
            this.text = "00:00"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
        }
        seekRow.addView(totalTimeTextView)
        bottomBarLayout.addView(seekRow)

        // Bottom Controls Row: -10s, Play/Pause, +10s, Repeat, Speed, Rotate
        val controlsRow = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = dp(8)
            }
            this.layoutParams = lp
        }

        // -10s button
        skipBackwardButton = TextView(this).apply {
            this.text = "-10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(10), dp(6), dp(10), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(6)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition - 10000).coerceAtLeast(0)
                videoView.seekTo(target)
                flashRippleIndicator(leftRippleView)
            }
        }
        controlsRow.addView(skipBackwardButton)

        // Play/Pause button
        playPauseButton = TextView(this).apply {
            this.text = "❚❚"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            this.setPadding(dp(14), dp(6), dp(14), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(6)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                togglePlayback()
            }
        }
        controlsRow.addView(playPauseButton)

        // +10s button
        skipForwardButton = TextView(this).apply {
            this.text = "+10s"
            this.setTextColor(Color.parseColor("#38BDF8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(10), dp(6), dp(10), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(6)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                videoView.seekTo(target)
                flashRippleIndicator(rightRippleView)
            }
        }
        controlsRow.addView(skipForwardButton)

        // Spacer
        val spacer = View(this).apply {
            val lp = LinearLayout.LayoutParams(0, 1, 1f)
            this.layoutParams = lp
        }
        controlsRow.addView(spacer)

        // Repeat button with LED (Cycles Repeat Off / One / All)
        repeatButton = LinearLayout(this).apply {
            this.orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(6)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                cycleRepeatMode()
            }
        }

        repeatLedView = View(this).apply {
            val ledLp = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                this.rightMargin = dp(5)
            }
            this.layoutParams = ledLp
            this.background = GradientDrawable().apply {
                this.shape = GradientDrawable.OVAL
                this.setColor(Color.parseColor("#64748B"))
            }
        }
        repeatButton.addView(repeatLedView)

        repeatLabelTextView = TextView(this).apply {
            this.text = "OFF"
            this.setTextColor(Color.parseColor("#94A3B8"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        repeatButton.addView(repeatLabelTextView)
        controlsRow.addView(repeatButton)
        updateRepeatButtonUI()

        // Speed button
        speedButton = TextView(this).apply {
            this.text = speedLabels[currentSpeedIndex]
            this.setTextColor(Color.parseColor("#F59E0B"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(true)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.rightMargin = dp(6)
            }
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                cyclePlaybackSpeed()
            }
        }
        controlsRow.addView(speedButton)

        // Rotate button
        rotateButton = TextView(this).apply {
            this.text = "⛶ ROT"
            this.setTextColor(Color.parseColor("#00F0FF"))
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            this.typeface = Typeface.MONOSPACE
            this.gravity = Gravity.CENTER
            this.setPadding(dp(8), dp(6), dp(8), dp(6))
            this.background = createMechanicalButtonBackground(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            this.layoutParams = lp
            this.setOnClickListener {
                resetAutoHideTimer()
                toggleOrientation()
            }
        }
        controlsRow.addView(rotateButton)

        bottomBarLayout.addView(controlsRow)
        hudOverlay.addView(bottomBarLayout)
    }

    private fun cycleRepeatMode() {
        currentRepeatMode = when (currentRepeatMode) {
            "off" -> "one"
            "one" -> "all"
            else -> "off"
        }
        updateRepeatButtonUI()

        // Sync to native settings
        getSharedPreferences(VideoDownloaderPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                this.putString("repeat_mode", currentRepeatMode)
                this.apply()
            }
    }

    private fun updateRepeatButtonUI() {
        when (currentRepeatMode) {
            "one" -> {
                repeatLabelTextView.text = "ONE"
                repeatLabelTextView.setTextColor(Color.parseColor("#00F0FF"))
                (repeatLedView.background as? GradientDrawable)?.setColor(Color.parseColor("#00F0FF"))
            }
            "all" -> {
                repeatLabelTextView.text = "ALL"
                repeatLabelTextView.setTextColor(Color.parseColor("#10B981"))
                (repeatLedView.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
            }
            else -> {
                repeatLabelTextView.text = "OFF"
                repeatLabelTextView.setTextColor(Color.parseColor("#94A3B8"))
                (repeatLedView.background as? GradientDrawable)?.setColor(Color.parseColor("#64748B"))
            }
        }
    }

    private fun loadVideoAtCurrentIndex(animate: Boolean = false, slideLeft: Boolean = true) {
        if (playlist.isEmpty()) {
            finish()
            return
        }

        saveCurrentPlaybackPosition()

        val item = playlist[currentIndex]
        currentVideoUri = Uri.parse(item.uri)
        currentVideoTitle = item.title

        titleTextView.text = currentVideoTitle
        playlistCounterTextView.text = "${currentIndex + 1}/${playlist.size}"

        if (animate) {
            val width = rootContainer.width.toFloat().coerceAtLeast(600f)
            val exitTranslation = if (slideLeft) -width else width
            val enterTranslation = if (slideLeft) width else -width

            videoContentContainer.animate()
                .translationX(exitTranslation)
                .alpha(0.3f)
                .setDuration(160)
                .withEndAction {
                    prepareAndStartVideo(item)
                    videoContentContainer.translationX = enterTranslation
                    videoContentContainer.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .start()
                }
                .start()
        } else {
            prepareAndStartVideo(item)
        }

        flashTitleBanner("${currentIndex + 1} / ${playlist.size} - $currentVideoTitle")
    }

    private fun prepareAndStartVideo(item: PlaylistItem) {
        // Show thumbnail placeholder while video loads
        loadThumbnailIntoPlaceholder(item.thumbnail)

        videoView.stopPlayback()
        videoView.setVideoURI(Uri.parse(item.uri))

        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            applyPlaybackSpeed()

            val duration = videoView.duration
            if (duration > 0) {
                seekBar.max = duration
                totalTimeTextView.text = formatTime(duration)
            }

            // Auto orientation from video aspect ratio if not manually locked
            val width = mp.videoWidth
            val height = mp.videoHeight
            if (width > 0 && height > 0 && !isOrientationLockedByUser) {
                if (width > height) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            // Restore saved playback position
            val uriStr = item.uri
            if (uriStr.isNotBlank()) {
                val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                val savedPos = prefs.getInt(uriStr, 0)
                if (savedPos > 1000 && duration > 0 && savedPos < duration - 3000) {
                    videoView.seekTo(savedPos)
                }
            }

            videoView.start()
            playPauseButton.text = "❚❚"

            // Hide thumbnail placeholder once playback starts
            thumbnailImageView.animate().alpha(0f).setDuration(250).withEndAction {
                thumbnailImageView.visibility = View.GONE
            }.start()

            mainHandler.post(updateProgressRunnable)
        }

        videoView.setOnCompletionListener {
            handleVideoCompletion()
        }

        videoView.setOnErrorListener { _, _, _ ->
            showControls()
            thumbnailImageView.visibility = View.GONE
            false
        }
    }

    private fun loadThumbnailIntoPlaceholder(thumbStr: String?) {
        if (thumbStr.isNullOrBlank()) {
            thumbnailImageView.setImageDrawable(null)
            thumbnailImageView.visibility = View.GONE
            return
        }

        thumbnailImageView.alpha = 1f
        thumbnailImageView.visibility = View.VISIBLE

        Thread {
            try {
                val cleanBase64 = if (thumbStr.contains(",")) {
                    thumbStr.substringAfter(",")
                } else {
                    thumbStr
                }
                val decoded = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                if (bmp != null) {
                    mainHandler.post {
                        thumbnailImageView.setImageBitmap(bmp)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun handleVideoCompletion() {
        // Clear saved position as video reached completion
        currentVideoUri?.toString()?.let { uriStr ->
            if (uriStr.isNotBlank()) {
                getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
                    .edit()
                    .apply {
                        this.remove(uriStr)
                        this.apply()
                    }
            }
        }

        when (currentRepeatMode) {
            "one" -> {
                // Repeat single clip
                videoView.seekTo(0)
                videoView.start()
                playPauseButton.text = "❚❚"
            }
            "all" -> {
                if (currentIndex < playlist.size - 1) {
                    playNextVideo(userInitiated = false)
                } else {
                    // Loop back to first video in playlist
                    currentIndex = 0
                    loadVideoAtCurrentIndex(animate = true, slideLeft = true)
                }
            }
            else -> {
                // Repeat Mode OFF: check autoPlayNext
                if (autoPlayNext && currentIndex < playlist.size - 1) {
                    playNextVideo(userInitiated = false)
                } else {
                    // Stop and show replay button
                    playPauseButton.text = "▶"
                    showControls()
                }
            }
        }
    }

    private fun playNextVideo(userInitiated: Boolean) {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            loadVideoAtCurrentIndex(animate = true, slideLeft = true)
        } else if (userInitiated || currentRepeatMode == "all") {
            currentIndex = 0
            loadVideoAtCurrentIndex(animate = true, slideLeft = true)
        } else {
            // Reached end of playlist without repeat
            showControls()
            flashTitleBanner("END OF PLAYLIST")
        }
    }

    private fun playPreviousVideo(userInitiated: Boolean) {
        if (playlist.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            loadVideoAtCurrentIndex(animate = true, slideLeft = false)
        } else if (userInitiated) {
            currentIndex = playlist.size - 1
            loadVideoAtCurrentIndex(animate = true, slideLeft = false)
        }
    }

    private fun togglePlayback() {
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
            flashCenterIcon("▶")
        } else {
            // If near completion, restart
            val total = videoView.duration
            val current = videoView.currentPosition
            if (total > 0 && current >= total - 500) {
                videoView.seekTo(0)
            }
            videoView.start()
            playPauseButton.text = "❚❚"
            flashCenterIcon("❚❚")
            scheduleControlsAutoHide()
        }
    }

    private fun cyclePlaybackSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
        speedButton.text = speedLabels[currentSpeedIndex]
        applyPlaybackSpeed()
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = speedOptions[currentSpeedIndex]
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleOrientation() {
        isOrientationLockedByUser = true
        val current = resources.configuration.orientation
        requestedOrientation = if (current == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        isControlsVisible = true
        hudOverlay.visibility = View.VISIBLE
        showSystemUI()
        scheduleControlsAutoHide()
    }

    private fun hideControls() {
        if (!videoView.isPlaying) return
        isControlsVisible = false
        hudOverlay.visibility = View.GONE
        hideSystemUI()
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun resetAutoHideTimer() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        scheduleControlsAutoHide()
    }

    private fun scheduleControlsAutoHide() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 3000)
    }

    private fun formatTime(millis: Int): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun saveCurrentPlaybackPosition() {
        val uriStr = currentVideoUri?.toString() ?: ""
        if (uriStr.isNotBlank()) {
            val current = videoView.currentPosition
            val total = videoView.duration
            val prefs = getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (total > 0 && current < total - 2000 && current > 1000) {
                    this.putInt(uriStr, current)
                } else if (current >= total - 2000) {
                    this.remove(uriStr)
                }
                this.apply()
            }
        }
    }

    private fun createMechanicalButtonBackground(isAmber: Boolean): LayerDrawable {
        val accentColor = if (isAmber) Color.parseColor("#F59E0B") else Color.parseColor("#00F0FF")
        val baseColor = Color.parseColor("#141A24")

        val border = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(6).toFloat()
            this.setStroke(dp(1), Color.parseColor("#27354A"))
            this.setColor(baseColor)
        }

        val highlight = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            this.cornerRadius = dp(6).toFloat()
            this.setStroke(dp(1), accentColor)
            this.setColor(Color.TRANSPARENT)
        }

        return LayerDrawable(arrayOf(border, highlight)).apply {
            this.setLayerInset(1, 0, 0, 0, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPlaybackPosition()
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseButton.text = "▶"
        }
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(hideTitleFlashRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (isControlsVisible) {
            scheduleControlsAutoHide()
        } else {
            hideSystemUI()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("saved_speed", currentSpeedIndex)
        outState.putBoolean("orient_locked", isOrientationLockedByUser)
        outState.putInt("saved_index", currentIndex)
        outState.putString("saved_repeat", currentRepeatMode)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPlaybackPosition()
        mainHandler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
    }
}
